package com.airbnb.aerosolve.training

import com.airbnb.aerosolve.core._
import com.airbnb.aerosolve.core.function.{Function, Linear, MultiDimensionSpline, Spline}
import com.airbnb.aerosolve.core.models.{AdditiveModel, NDTreeModel}
import com.airbnb.aerosolve.core.util.Util
import com.airbnb.aerosolve.training.NDTree.NDTreeBuildOptions
import com.airbnb.aerosolve.training.pipeline.NDTreePipeline
import com.airbnb.aerosolve.training.pipeline.NDTreePipeline.FeatureStats
import com.typesafe.config.Config
import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

/**
  * Additive Model Trainer
  * By default, we use a spline function to represent a float feature; use linear function to represent a string feature.
  * Additionally, float features that are specified as 'linear_feature' in config are also represented by a linear function.
  *
  * Model is fitted using [[https://en.wikipedia.org/wiki/Backfitting_algorithm Backfitting Algorithm]] where weight vectors
  * for each feature is updated independently using SGD and after each iteration spline features are passed under a smoothing
  * operator. This is a simplified implementation of GAM where features are bucketed into exactly where knots are with some
  * flexibility being provided by applying multiscaling to the bucketing scheme.
  */
//noinspection NameBooleanParameters

object AdditiveModelTrainer {
  private final val log: Logger = LoggerFactory.getLogger("AdditiveModelTrainer")

  case class AdditiveTrainerParams(numBins: Int,
                                   numBags: Int,
                                   rankKey: String,
                                   loss: String,
                                   minCount: Int,
                                   learningRate: Double,
                                   dropout: Double,
                                   subsample: Double,
                                   margin: Double,
                                   multiscale: Array[Int],
                                   smoothingTolerance: Double,
                                   linfinityThreshold: Double,
                                   linfinityCap: Double,
                                   threshold: Double,
                                   lossMod: Int,
                                   epsilon: Double, // epsilon used in epsilon-insensitive loss for regression training
                                   initModelPath: String,
                                   linearFeatureFamilies: java.util.List[String],
                                   priors: Array[String],
                                   nDTreeBuildOptions: NDTreeBuildOptions)

  def train(sc: SparkContext,
            input: RDD[Example],
            config: Config,
            key: String): AdditiveModel = {
    val trainConfig = config.getConfig(key)
    val iterations: Int = trainConfig.getInt("iterations")
    val params = loadTrainingParameters(trainConfig)
    val transformed = transformExamples(input, config, key, params)
    val output = config.getString(key + ".model_output")
    log.info("Training using " + params.loss)

    val paramsBC = sc.broadcast(params)
    var model = modelInitialization(sc, transformed, params)
    for (i <- 1 to iterations) {
      log.info(s"Iteration $i")
      val modelBC = sc.broadcast(model)
      model = sgdTrain(transformed, paramsBC, modelBC)
      modelBC.unpersist()

      TrainingUtils.saveModel(model, output)
    }
    model
  }

  /**
    * During each iteration, we:
    *
    * 1. Sample dataset with subsample (this is analogous to mini-batch sgd?)
    * 2. Repartition to numBags (this is analogous to ensemble averaging?)
    * 3. For each bag we run SGD (observation-wise gradient updates)
    * 4. We then average fitted weights for each feature and return them as updated model
    *
    * @param input    collection of examples to be trained in sgd iteration
    * @param paramsBC broadcasted model params
    * @param modelBC  broadcasted current model (weights)
    * @return
    */
  def sgdTrain(input: RDD[Example],
               paramsBC: Broadcast[AdditiveTrainerParams],
               modelBC: Broadcast[AdditiveModel]): AdditiveModel = {
    val model = modelBC.value
    val params = paramsBC.value

    input
      .sample(false, params.subsample)
      .coalesce(params.numBags, true)
      .mapPartitionsWithIndex((index, partition) => sgdPartition(index, partition, modelBC, paramsBC))
      .groupByKey()
      // Average the feature functions
      // Average the weights
      .mapValues(x => {
      val scale = 1.0f / paramsBC.value.numBags.toFloat
      aggregateFuncWeights(x, scale, paramsBC.value.numBins, paramsBC.value.smoothingTolerance.toFloat)
    })
      .collect()
      .foreach(entry => {
        val family = model.getWeights.get(entry._1._1)
        if (family != null && family.containsKey(entry._1._2)) {
          family.put(entry._1._2, entry._2)
        }
      })

    deleteSmallFunctions(model, params.linfinityThreshold)
    model
  }

  /**
    * For multiscale feature, we need to resample the model so we can update the model using
    * the particular number of knots
    *
    * @param index     partition index (for multiscale distribution)
    * @param partition list of examples in this partition
    * @param modelBC   broadcasted model weights
    * @param paramsBC  broadcasted model params
    * @return
    */
  def sgdPartition(index: Int,
                   partition: Iterator[Example],
                   modelBC: Broadcast[AdditiveModel],
                   paramsBC: Broadcast[AdditiveTrainerParams]): Iterator[((String, String), Function)] = {
    val workingModel = modelBC.value
    val params = paramsBC.value
    val multiscale = params.multiscale

    if (multiscale.nonEmpty) {
      val newBins = multiscale(index % multiscale.length)

      log.info(s"Resampling to $newBins bins")
      for(family <- workingModel.getWeights.values) {
        for(feature <- family.values) {
          feature.resample(newBins)
        }
      }
    }

    val output = sgdPartitionInternal(partition, workingModel, params)
    output.iterator
  }

  /**
    * Average function weights according to function type. Optionally smooth the weights
    * for spline function.
    *
    * @param input              list of function weights
    * @param scale              scaling factor for aggregation
    * @param numBins            number of bins for final weights
    * @param smoothingTolerance smoothing tolerance for spline
    * @return
    */
  private def aggregateFuncWeights(input: Iterable[Function],
                                   scale: Float,
                                   numBins: Int,
                                   smoothingTolerance: Float): Function = {
    val head: Function = input.head
    // TODO: revisit asJava performance impact
    val output = head.aggregate(input.asJava, scale, numBins)
    output.smooth(smoothingTolerance)
    output
  }

  /**
    * Actually perform SGD on examples by applying approriate gradient updates according
    * to model specification
    *
    * @param partition    list of examples
    * @param workingModel model to be updated
    * @param params       model parameters
    * @return
    */
  private def sgdPartitionInternal(partition: Iterator[Example],
                                   workingModel: AdditiveModel,
                                   params: AdditiveTrainerParams): mutable.HashMap[(String, String), Function] = {
    var lossSum: Double = 0.0
    var lossCount: Int = 0
    partition.foreach(example => {
      lossSum += pointwiseLoss(example.example.get(0), workingModel, params.loss, params)
      lossCount = lossCount + 1
      if (lossCount % params.lossMod == 0) {
        log.info(s"Loss = ${lossSum / params.lossMod.toDouble}, samples = $lossCount")
        lossSum = 0.0
      }
    })
    val output = mutable.HashMap[(String, String), Function]()
    // TODO: weights should be a vector instead of stored in hashmap
    workingModel
      .getWeights
      .foreach(family => {
        family._2.foreach(feature => {
          output.put((family._1, feature._1), feature._2)
        })
      })
    output
  }

  /**
    * Compute loss for a single observation and update model weights during the process
    *
    * @param fv           observation
    * @param workingModel model to be updated
    * @param loss         loss type
    * @param params       model params
    * @return
    */
  def pointwiseLoss(fv: FeatureVector,
                    workingModel: AdditiveModel,
                    loss: String,
                    params: AdditiveTrainerParams): Double = {
    val label: Double = if (loss == "regression") {
      TrainingUtils.getLabel(fv, params.rankKey)
    } else {
      TrainingUtils.getLabel(fv, params.rankKey, params.threshold)
    }

    loss match {
      case "logistic" => updateLogistic(workingModel, fv, label, params)
      case "hinge" => updateHinge(workingModel, fv, label, params)
      case "regression" => updateRegressor(workingModel, fv, label, params)
    }
  }

  // http://www.cs.toronto.edu/~rsalakhu/papers/srivastava14a.pdf
  // We rescale by 1 / p so that at inference time we don't have to scale by p.
  // In our case p = 1.0 - dropout rate
  def updateLogistic(model: AdditiveModel,
                     fv: FeatureVector,
                     label: Double,
                     params: AdditiveTrainerParams): Double = {
    val flatFeatures = Util.flattenFeatureWithDropout(fv, params.dropout)
    // only MultiDimensionSpline use denseFeatures for now
    val denseFeatures = MultiDimensionSpline.featureDropout(fv, params.dropout)
    val prediction = (model.scoreFlatFeatures(flatFeatures) +
      model.scoreDenseFeatures(denseFeatures)) /
      (1.0 - params.dropout)
    // To prevent blowup.
    val corr = scala.math.min(10.0, label * prediction)
    val expCorr = scala.math.exp(corr)
    val loss = scala.math.log(1.0 + 1.0 / expCorr)
    val grad = -label / (1.0 + expCorr)
    val gradWithLearningRate = grad.toFloat * params.learningRate.toFloat
    model.update(gradWithLearningRate,
      params.linfinityCap.toFloat,
      flatFeatures)
    model.updateDense(gradWithLearningRate,
      params.linfinityCap.toFloat,
      denseFeatures)
    loss
  }

  def updateHinge(model: AdditiveModel,
                  fv: FeatureVector,
                  label: Double,
                  params: AdditiveTrainerParams): Double = {
    val flatFeatures = Util.flattenFeatureWithDropout(fv, params.dropout)
    // only MultiDimensionSpline use denseFeatures for now
    val denseFeatures = MultiDimensionSpline.featureDropout(fv, params.dropout)
    val prediction = (model.scoreFlatFeatures(flatFeatures) +
      model.scoreDenseFeatures(denseFeatures)) /
      (1.0 - params.dropout)
    val loss = scala.math.max(0.0, params.margin - label * prediction)
    if (loss > 0.0) {
      val gradWithLearningRate = -label.toFloat * params.learningRate.toFloat
      model.update(gradWithLearningRate,
        params.linfinityCap.toFloat,
        flatFeatures)
      model.updateDense(gradWithLearningRate,
        params.linfinityCap.toFloat,
        denseFeatures)
    }
    loss
  }

  def updateRegressor(model: AdditiveModel,
                      fv: FeatureVector,
                      label: Double,
                      params: AdditiveTrainerParams): Double = {
    val flatFeatures = Util.flattenFeatureWithDropout(fv, params.dropout)
    // only MultiDimensionSpline use denseFeatures for now
    val denseFeatures = MultiDimensionSpline.featureDropout(fv, params.dropout)
    val prediction = (model.scoreFlatFeatures(flatFeatures) +
      model.scoreDenseFeatures(denseFeatures)) /
      (1.0 - params.dropout)
    // absolute difference
    val loss = math.abs(prediction - label)
    if (prediction - label > params.epsilon) {
      model.update(params.learningRate.toFloat,
        params.linfinityCap.toFloat, flatFeatures)
      model.updateDense(params.learningRate.toFloat,
        params.linfinityCap.toFloat, denseFeatures)
    } else if (prediction - label < -params.epsilon) {
      model.update(-params.learningRate.toFloat,
        params.linfinityCap.toFloat, flatFeatures)
      model.updateDense(-params.learningRate.toFloat,
        params.linfinityCap.toFloat, denseFeatures)
    }
    loss
  }

  private def transformExamples(input: RDD[Example],
                                config: Config,
                                key: String,
                                params: AdditiveTrainerParams): RDD[Example] = {
      LinearRankerUtils.makePointwiseFloat(input, config, key)
  }

  private def modelInitialization(sc: SparkContext,
                                  input: RDD[Example],
                                  params: AdditiveTrainerParams): AdditiveModel = {
    // sample examples to be used for model initialization
    if (params.initModelPath == "") {
      val newModel = new AdditiveModel()
      initModel(sc, params, input, newModel, true)
      setPrior(params.priors, newModel)
      newModel
    } else {
      val newModel = TrainingUtils.loadScoreModel(params.initModelPath)
        .get.asInstanceOf[AdditiveModel]
      initModel(sc, params, input, newModel, false)
      newModel
    }
  }

  // Initializes the model
  private def initModel(sc: SparkContext,
                        params: AdditiveTrainerParams,
                        input: RDD[Example],
                        model: AdditiveModel,
                        overwrite: Boolean) = {
    if (params.nDTreeBuildOptions != null) {
      val linearFeatureFamilies = params.linearFeatureFamilies
      val result: Array[((String, String), Any)] = NDTreePipeline.getFeatures(
        sc, input, params.minCount, params.subsample,
        linearFeatureFamilies, params.nDTreeBuildOptions)
      for (((family, name), feature) <- result) {
        // save to disk.
        if (feature.isInstanceOf[FeatureStats]) {
          val stats = feature.asInstanceOf[FeatureStats]
          model.addFunction(family, name,
            new Linear(stats.min.toFloat, stats.max.toFloat), overwrite)
        } else {
          val ndTreeModel = feature.asInstanceOf[NDTreeModel]
          model.addFunction(family, name, new  MultiDimensionSpline(ndTreeModel), overwrite)
        }
      }
    } else {
      initWithoutDynamicBucketModel(params, input, model, overwrite)
    }
  }

  // init spline and linear
  private def initWithoutDynamicBucketModel(params: AdditiveTrainerParams,
                        input: RDD[Example],
                        model: AdditiveModel,
                        overwrite: Boolean) = {
    val linearFeatureFamilies = params.linearFeatureFamilies
    val initExamples = input.sample(false, params.subsample)
    val minMax = TrainingUtils
      .getFeatureStatistics(params.minCount, initExamples)
      .filter(x => x._1._1 != params.rankKey)
    log.info("Num features = %d".format(minMax.length))
    val minMaxSpline = minMax.filter(x => !linearFeatureFamilies.contains(x._1._1))
    val minMaxLinear = minMax.filter(x => linearFeatureFamilies.contains(x._1._1))
    // add splines
    for (((featureFamily, featureName), stats) <- minMaxSpline) {
      val spline = new Spline(stats.min.toFloat, stats.max.toFloat, params.numBins)
      model.addFunction(featureFamily, featureName, spline, overwrite)
    }
    // add linear
    for (((featureFamily, featureName), stats) <- minMaxLinear) {
      // set default linear function as f(x) = 0
      model.addFunction(featureFamily, featureName,
        new Linear(stats.min.toFloat, stats.max.toFloat), overwrite)
    }
  }

  def deleteSmallFunctions(model: AdditiveModel,
                           linfinityThreshold: Double) = {
    val toDelete = scala.collection.mutable.ArrayBuffer[(String, String)]()

    model.getWeights.asScala.foreach(family => {
      family._2.asScala.foreach(entry => {
        val func: Function = entry._2
        if (func.LInfinityNorm() < linfinityThreshold) {
          toDelete.append((family._1, entry._1))
        }
      })
    })

    log.info("Deleting %d small functions".format(toDelete.size))
    toDelete.foreach(entry => {
      val family = model.getWeights.get(entry._1)
      if (family != null && family.containsKey(entry._2)) {
        family.remove(entry._2)
      }
    })
  }

  def setPrior(priors: Array[String], model: AdditiveModel): Unit = {
    // set prior for existing functions in the model
    try {
      for (prior <- priors) {
        val tokens: Array[String] = prior.split(",")
        if (tokens.length == 4) {
          val family = tokens(0)
          val name = tokens(1)
          val params = Array(tokens(2).toFloat, tokens(3).toFloat)
          val familyMap = model.getWeights.get(family)
          if (!familyMap.isEmpty) {
            val func: Function = familyMap.get(name)
            if (func != null) {
              log.info("Setting prior %s:%s <- %f to %f".format(family, name, params(0), params(1)))
              func.setPriors(params)
            }
          }
        } else {
          log.error("Incorrect number of parameters for %s".format(prior))
        }
      }
    } catch {
      case _: Throwable => log.info("No prior given")
    }
  }

  def loadTrainingParameters(config: Config): AdditiveTrainerParams = {
    val loss: String = config.getString("loss")
    val numBins: Int = config.getInt("num_bins")
    val numBags: Int = config.getInt("num_bags")
    val rankKey: String = config.getString("rank_key")
    val learningRate: Double = config.getDouble("learning_rate")
    val dropout: Double = config.getDouble("dropout")
    val subsample: Double = config.getDouble("subsample")
    val linfinityCap: Double = config.getDouble("linfinity_cap")
    val smoothingTolerance: Double = config.getDouble("smoothing_tolerance")
    val linfinityThreshold: Double = config.getDouble("linfinity_threshold")
    val initModelPath: String = Try {
      config.getString("init_model")
    }.getOrElse("")
    val threshold: Double = config.getDouble("rank_threshold")
    val epsilon: Double = Try {
      config.getDouble("epsilon")
    }.getOrElse(0.0)
    val minCount: Int = config.getInt("min_count")
    val linearFeatureFamilies: java.util.List[String] = Try(
      config.getStringList("linear_feature"))
      .getOrElse[java.util.List[String]](List.empty.asJava)
    val lossMod: Int = Try {
      config.getInt("loss_mod")
    }.getOrElse(100)
    val priors: Array[String] = Try(
      config.getStringList("prior").toList.toArray)
      .getOrElse(Array[String]())

    val margin: Double = Try(config.getDouble("margin")).getOrElse(1.0)

    val multiscale: Array[Int] = Try(
      config.getIntList("multiscale").asScala.map(x => x.toInt).toArray)
      .getOrElse(Array[Int]())
    val dynamicBucketsConfig = Try(Some(config.getConfig("dynamic_buckets"))).getOrElse(None)
    val options = if (dynamicBucketsConfig.nonEmpty) {
      val cfg = dynamicBucketsConfig.get
      NDTreeBuildOptions(
        maxTreeDepth = cfg.getInt("max_tree_depth"),
        minLeafCount = cfg.getInt("min_leaf_count"))
    } else {
      null
    }

    AdditiveTrainerParams(
      numBins,
      numBags,
      rankKey,
      loss,
      minCount,
      learningRate,
      dropout,
      subsample,
      margin,
      multiscale,
      smoothingTolerance,
      linfinityThreshold,
      linfinityCap,
      threshold,
      lossMod,
      epsilon,
      initModelPath,
      linearFeatureFamilies,
      priors,
      options)
  }

  def trainAndSaveToFile(sc: SparkContext,
                         input: RDD[Example],
                         config: Config,
                         key: String) = {
    val model = train(sc, input, config, key)
    TrainingUtils.saveModel(model, config, key + ".model_output")
  }
}
