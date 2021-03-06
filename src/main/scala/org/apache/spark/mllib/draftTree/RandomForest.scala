package org.apache.spark.mllib.draftTree

/*
 * CHANGES REQUIRED
 * 
 * filling weightMatrix 
 * convert to featurePointRDD needs some changes in FeaturePoint Class 
 * 
 */

import scala.collection.JavaConverters._
import scala.collection.mutable
import cern.jet.random.Poisson
import cern.jet.random.engine.DRand
import org.apache.spark.Logging
import org.apache.spark.annotation.Experimental
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.draftTree.configuration.Algo._
import org.apache.spark.mllib.draftTree.configuration.QuantileStrategy._
import org.apache.spark.mllib.draftTree.configuration.Strategy
import org.apache.spark.mllib.draftTree.impl.{ FeaturePoint, TreePoint, DecisionTreeMetadata, TimeTracker }
import org.apache.spark.mllib.draftTree.impurity.Impurities
import org.apache.spark.mllib.draftTree.model._
import org.apache.spark.mllib.draftTree.impl.FeaturePoint
import org.apache.spark.mllib.draftTree.DecisionTree
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.Utils
import org.apache.spark.Accumulable
import org.apache.spark.AccumulableParam
import org.apache.spark.SparkContext._
import util.Random._
import scala.math._

/**
 * :: Experimental ::
 * A class which implements a random forest learning algorithm for classification and regression.
 * It supports both continuous and categorical features.
 *
 * The settings for featureSubsetStrategy are based on the following references:
 *  - log2: tested in Breiman (2001)
 *  - sqrt: recommended by Breiman manual for random forests
 *  - The defaults of sqrt (classification) and onethird (regression) match the R randomForest
 *    package.
 * @see [[http://www.stat.berkeley.edu/~breiman/randomforest2001.pdf  Breiman (2001)]]
 * @see [[http://www.stat.berkeley.edu/~breiman/Using_random_forests_V3.1.pdf  Breiman manual for
 *     random forests]]
 *
 * @param strategy The configuration parameters for the random forest algorithm which specify
 *                 the type of algorithm (classification, regression, etc.), feature type
 *                 (continuous, categorical), depth of the tree, quantile calculation strategy,
 *                 etc.
 * @param numTrees If 1, then no bootstrapping is used.  If > 1, then bootstrapping is done.
 * @param featureSubsetStrategy Number of features to consider for splits at each node.
 *                              Supported: "auto" (default), "all", "sqrt", "log2", "onethird".
 *                              If "auto" is set, this parameter is set based on numTrees:
 *                                if numTrees == 1, set to "all";
 *                                if numTrees > 1 (forest) set to "sqrt" for classification and
 *                                  to "onethird" for regression.
 * @param seed  Random seed for bootstrapping and choosing feature subsets.
 */
@Experimental
private class RandomForest(
  private val strategy: Strategy,
  private val seed: Int)
  extends Serializable with Logging {

  strategy.assertValid()

  /**
   * Method to train a decision tree model over an RDD
   * @param input Training data: RDD of [[org.apache.spark.mllib.regression.LabeledPoint]]
   * @return RandomForestModel that can be used for prediction
   */
  def train(input: RDD[LabeledPoint]): RandomForestModel = {

    val timer = new TimeTracker()

    timer.start("total")

    timer.start("init")

    val retaggedInput = input.retag(classOf[LabeledPoint]).persist

    val label = retaggedInput.map(x => x.label).collect
    val featureInput = FeaturePoint.convertToFeatureRDD(retaggedInput)

    //###########################################################################################################################

    /**
     * feature arity is calculated before creating metadata
     * if categoricalFeaturesInfo is given in strategy it will use it
     * if none is given as ategoricalFeaturesInfo in strategy,the below code will calculate the featureArity from featureInput.
     *
     */
    val featureArity = strategy.categoricalFeaturesInfo match {
      case Some(categoricalFeaturesInfo) => {
        categoricalFeaturesInfo
      }
      case None => {
        // used when partition by features is used
        featureInput.map { fp =>
          val numDistinctValues = fp.featureValues.distinct.length
          val fractionOfDistinctValues = (numDistinctValues.toDouble / fp.featureValues.length.toDouble)
          if(fp.featureValues.forall(x=>x==ceil(x))){
            if (fractionOfDistinctValues > .5) {
              //value are whole numbers but huge arity..so consider them as continuous
            (fp.featureIndex, 0) //continuous feature
            }else{
              (fp.featureIndex, fp.featureValues.distinct.length) //categorical feature
            }
            
          }else {
            (fp.featureIndex, 0) //continuous feature
          }

        }.collect.toMap.filter(_._2 != 0)
      }
    }
    
    println("################# featurearity: " + featureArity )

    featureArity.foreach {
      case (feature, arity) =>
        require(arity >= 2,
          s"DecisionTree Strategy given invalid categoricalFeaturesInfo setting:" +
            s" feature $feature has $arity categories.  The number of categories should be >= 2.")
    }

    //########################################################################################################################### 

    val metadata =
      DecisionTreeMetadata.buildMetadata(retaggedInput, strategy, featureArity)

    logDebug("algo = " + strategy.algo)
    logDebug("numTrees = " + strategy.numTrees)
    logDebug("seed = " + seed)
    logDebug("maxBins = " + metadata.maxBins)
    logDebug("featureSubsetStrategy = " + strategy.featureSubsetStrategy)
    logDebug("numFeaturesPerNode = " + metadata.numFeaturesPerNode)

    // Find the splits and the corresponding bins (interval between the splits) using a sample
    // of the input data.

    timer.start("findSplitsBins")

    val (splits, bins) = metadata.findSplitsBins(retaggedInput)

    timer.stop("findSplitsBins")

    logDebug("numBins: feature: number of bins")
    logDebug(Range(0, metadata.numFeatures).map { featureIndex =>
      s"\t$featureIndex\t${metadata.numBins(featureIndex)}"
    }.mkString("\n"))
    /*
    val label = retaggedInput.map(x => x.label).collect
    val featureInput = FeaturePoint.convertToFeatureRDD(retaggedInput)
    * 
    */

    retaggedInput.unpersist()
    featureInput.persist()

    //bagging

    val weightMatrix = Array.fill[Array[Int]](metadata.numTrees)(Array.fill[Int](metadata.numExamples.toInt)(0))

    weightMatrix.foreach { x =>

      var i = 0
      while (i < metadata.numExamples) {

        val r = new scala.util.Random
        val index = r.nextInt(metadata.numExamples.toInt)
        x(index) = x(index) + 1
        i += 1

      }
    }

    println("@@@@@@@@@@@@@@@@@@@@@@@@@@ weightMatrix for tree 1: " + weightMatrix(0).mkString(",") + "@@@@@@@@@@@@@@@@")

    //NodeInstanceMAtrix,,,initialize all values to 1 for initial root node calculation 
    var nodeInstanceMatrix = Array.fill[Array[Int]](metadata.numTrees)(Array.fill[Int](metadata.numExamples.toInt)(1))

    val maxDepth = strategy.maxDepth
    require(maxDepth <= 30,
      s"DecisionTree currently only supports maxDepth <= 30, but was given maxDepth = $maxDepth.")

    // Max memory usage for aggregates
    // TODO: Calculate memory usage more precisely.
    val maxMemoryUsage: Long = strategy.maxMemoryInMB * 1024L * 1024L
    logDebug("max memory usage for aggregates = " + maxMemoryUsage + " bytes.")
    val maxMemoryPerNode = {
      val featureSubset: Option[Array[Int]] = if (metadata.subsamplingFeatures) {
        // Find numFeaturesPerNode largest bins to get an upper bound on memory usage.
        Some(metadata.numBins.zipWithIndex.sortBy(-_._1)
          .take(metadata.numFeaturesPerNode).map(_._2))
      } else {
        None
      }
      RandomForest.aggregateSizeForNode(metadata, featureSubset) * 8L
    }
    require(maxMemoryPerNode <= maxMemoryUsage,
      s"RandomForest/DecisionTree given maxMemoryInMB = ${strategy.maxMemoryInMB}," +
        " which is too small for the given features." +
        s"  Minimum value = ${maxMemoryPerNode / (1024L * 1024L)}")

    timer.stop("init")

    /*
     * The main idea here is to perform group-wise training of the decision tree nodes thus
     * reducing the passes over the data from (# nodes) to (# nodes / maxNumberOfNodesPerGroup).
     * Each data instance is handled by a particular node (or it reaches a leaf and is not used
     * in lower levels).This info is stored in nodeInstanceMatrix
     */

    // FIFO queue of nodes to train: (treeIndex, node)
    val nodeQueue = new mutable.Queue[(Int, Node)]()

    val rng = new scala.util.Random()
    rng.setSeed(seed)

    // Allocate empty nodes with nodeIndex as 1 for all the trees 
    val topNodes: Array[Node] = Array.fill[Node](metadata.numTrees)(Node.emptyNode(nodeIndex = 1))

    //enqueue those nodes into the node queue along with thier tree index
    Range(0, metadata.numTrees).foreach(treeIndex => nodeQueue.enqueue((treeIndex, topNodes(treeIndex))))

    //finding nodes for training,,splits for those nodes,,updating nodeInstanceMatrix after finding splits   

    while (nodeQueue.nonEmpty) {

      // ************* Choose some nodes to split, and choose features for each node (if subsampling)*******************

      val (nodesForGroup, treeToNodeToIndexInfo) =
        RandomForest.selectNodesToSplit(nodeQueue, maxMemoryUsage, metadata, rng)

      assert(nodesForGroup.size > 0,
        s"RandomForest selected empty nodesForGroup.  Error for unknown reason.")

      //******************** Choose splits for nodes in group, and enqueue new nodes as needed.**************************
      val NodesToTrain = nodesForGroup.values.map(_.size).sum
      println("number of nodes to train in this level of trainig is : " + NodesToTrain + " %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%")
      timer.start("findBestSplits")

      val treeToGlobalIndexToSplit = DecisionTree.findBestSplits(featureInput, metadata, topNodes, nodesForGroup,
        treeToNodeToIndexInfo, splits, bins, nodeQueue, label, weightMatrix, nodeInstanceMatrix, timer)

      timer.stop("findBestSplits")

      //************************* update nodeInstanceMAtrix after a finding the splits for nodes in group*****************

      timer.start("updateNodeInstanceMatrix")
      //need to pass an accumalator to this method ..because normal variable can not get updated on the rdd operation and 
      //return value on master ..so nodeInstanceMatrix can not be updated in the updateNOdeInstanceMatrix method.instead pass an accumalator
      //to get updated and return to master..now assign the value of this accumalator to nodeInstanceMatrix

      val initialValue = Array.fill[Array[Int]](metadata.numTrees)(Array.fill[Int](metadata.numExamples.toInt)(1))

      val accumalator = featureInput.sparkContext.accumulable(initialValue)(MatrixAccumulatorParam)

      val tempMatrix = DecisionTree.updateNodeInstanceMatrix(featureInput, treeToGlobalIndexToSplit, nodeInstanceMatrix, accumalator, metadata)

      nodeInstanceMatrix = tempMatrix.clone

      // println("@@@@@@@@@@@@@@@@@@@@ updated nodeInstanceMatrix for tree 0: " + nodeInstanceMatrix(0).mkString(",") + "@@@@@@@@@@@@@@@@@")

      timer.stop("updateNodeInstanceMatrix")
    }
    timer.stop("total")

    logInfo("Internal timing for DecisionTree:")
    logInfo(s"$timer")

    val trees = topNodes.map(topNode => new DecisionTreeModel(topNode, strategy.algo))

    RandomForestModel.build(trees)

  }

}

object RandomForest extends Serializable with Logging {

  /**
   * Method to train a decision tree model for binary or multiclass classification.
   *
   * @param input Training dataset: RDD of [[org.apache.spark.mllib.regression.LabeledPoint]].
   *              Labels should take values {0, 1, ..., numClasses-1}.
   * @param strategy Parameters for training each tree in the forest.
   * @param numTrees Number of trees in the random forest.
   * @param featureSubsetStrategy Number of features to consider for splits at each node.
   *                              Supported: "auto" (default), "all", "sqrt", "log2", "onethird".
   *                              If "auto" is set, this parameter is set based on numTrees:
   *                                if numTrees == 1, set to "all";
   *                                if numTrees > 1 (forest) set to "sqrt" for classification and
   *                                  to "onethird" for regression.
   * @param seed  Random seed for bootstrapping and choosing feature subsets.
   * @return RandomForestModel that can be used for prediction
   */
  def trainClassifier(
    input: RDD[LabeledPoint],
    strategy: Strategy,
    seed: Int): RandomForestModel = {
    require(strategy.algo == Classification,
      s"RandomForest.trainClassifier given Strategy with invalid algo: ${strategy.algo}")
    val rf = new RandomForest(strategy, seed)
    rf.train(input)
  }

  /**
   * Method to train a decision tree model for binary or multiclass classification.
   *
   * @param input Training dataset: RDD of [[org.apache.spark.mllib.regression.LabeledPoint]].
   *              Labels should take values {0, 1, ..., numClasses-1}.
   * @param numClassesForClassification number of classes for classification.
   * @param categoricalFeaturesInfo Map storing arity of categorical features.
   *                                E.g., an entry (n -> k) indicates that feature n is categorical
   *                                with k categories indexed from 0: {0, 1, ..., k-1}.
   * @param numTrees Number of trees in the random forest.
   * @param featureSubsetStrategy Number of features to consider for splits at each node.
   *                              Supported: "auto" (default), "all", "sqrt", "log2", "onethird".
   *                              If "auto" is set, this parameter is set based on numTrees:
   *                                if numTrees == 1, set to "all";
   *                                if numTrees > 1 (forest) set to "sqrt" for classification and
   *                                  to "onethird" for regression.
   * @param impurity Criterion used for information gain calculation.
   *                 Supported values: "gini" (recommended) or "entropy".
   * @param maxDepth Maximum depth of the tree.
   *                 E.g., depth 0 means 1 leaf node; depth 1 means 1 internal node + 2 leaf nodes.
   *                  (suggested value: 4)
   * @param maxBins maximum number of bins used for splitting features
   *                 (suggested value: 100)
   * @param seed  Random seed for bootstrapping and choosing feature subsets.
   * @return RandomForestModel that can be used for prediction
   */
  def trainClassifier(
    input: RDD[LabeledPoint],
    numClassesForClassification: Int,
    categoricalFeaturesInfo: Map[Int, Int] = Map[Int, Int](),
    numTrees: Int,
    featureSubsetStrategy: String,
    impurity: String,
    maxDepth: Int,
    maxBins: Int,
    seed: Int = Utils.random.nextInt()): RandomForestModel = {

    val impurityType = Impurities.fromString(impurity)
    val strategy = if (categoricalFeaturesInfo == Map[Int, Int]()) {
      new Strategy(Classification, impurityType, maxDepth,
        numClassesForClassification, maxBins, quantileCalculationStrategy = Sort, numTrees = numTrees, featureSubsetStrategy = featureSubsetStrategy)
    } else {
      new Strategy(Classification, impurityType, maxDepth,
        numClassesForClassification, maxBins, Sort, Some(categoricalFeaturesInfo), numTrees, featureSubsetStrategy)
    }

    trainClassifier(input, strategy, seed)
  }

  /**
   * Java-friendly API for [[org.apache.spark.mllib.tree.RandomForest$#trainClassifier]]
   */
  def trainClassifier(
    input: JavaRDD[LabeledPoint],
    numClassesForClassification: Int,
    categoricalFeaturesInfo: java.util.Map[java.lang.Integer, java.lang.Integer],
    numTrees: Int,
    featureSubsetStrategy: String,
    impurity: String,
    maxDepth: Int,
    maxBins: Int,
    seed: Int): RandomForestModel = {
    trainClassifier(input.rdd, numClassesForClassification,
      categoricalFeaturesInfo.asInstanceOf[java.util.Map[Int, Int]].asScala.toMap,
      numTrees, featureSubsetStrategy, impurity, maxDepth, maxBins, seed)
  }

  /**
   * Method to train a decision tree model for regression.
   *
   * @param input Training dataset: RDD of [[org.apache.spark.mllib.regression.LabeledPoint]].
   *              Labels are real numbers.
   * @param strategy Parameters for training each tree in the forest.
   * @param numTrees Number of trees in the random forest.
   * @param featureSubsetStrategy Number of features to consider for splits at each node.
   *                              Supported: "auto" (default), "all", "sqrt", "log2", "onethird".
   *                              If "auto" is set, this parameter is set based on numTrees:
   *                                if numTrees == 1, set to "all";
   *                                if numTrees > 1 (forest) set to "sqrt" for classification and
   *                                  to "onethird" for regression.
   * @param seed  Random seed for bootstrapping and choosing feature subsets.
   * @return RandomForestModel that can be used for prediction
   */
  def trainRegressor(
    input: RDD[LabeledPoint],
    strategy: Strategy,
    seed: Int): RandomForestModel = {
    require(strategy.algo == Regression,
      s"RandomForest.trainRegressor given Strategy with invalid algo: ${strategy.algo}")
    val rf = new RandomForest(strategy, seed)
    rf.train(input)
  }

  /**
   * Method to train a decision tree model for regression.
   *
   * @param input Training dataset: RDD of [[org.apache.spark.mllib.regression.LabeledPoint]].
   *              Labels are real numbers.
   * @param categoricalFeaturesInfo Map storing arity of categorical features.
   *                                E.g., an entry (n -> k) indicates that feature n is categorical
   *                                with k categories indexed from 0: {0, 1, ..., k-1}.
   * @param numTrees Number of trees in the random forest.
   * @param featureSubsetStrategy Number of features to consider for splits at each node.
   *                              Supported: "auto" (default), "all", "sqrt", "log2", "onethird".
   *                              If "auto" is set, this parameter is set based on numTrees:
   *                                if numTrees == 1, set to "all";
   *                                if numTrees > 1 (forest) set to "sqrt" for classification and
   *                                  to "onethird" for regression.
   * @param impurity Criterion used for information gain calculation.
   *                 Supported values: "variance".
   * @param maxDepth Maximum depth of the tree.
   *                 E.g., depth 0 means 1 leaf node; depth 1 means 1 internal node + 2 leaf nodes.
   *                  (suggested value: 4)
   * @param maxBins maximum number of bins used for splitting features
   *                 (suggested value: 100)
   * @param seed  Random seed for bootstrapping and choosing feature subsets.
   * @return RandomForestModel that can be used for prediction
   */
  def trainRegressor(
    input: RDD[LabeledPoint],
    categoricalFeaturesInfo: Map[Int, Int] = Map[Int, Int](),
    numTrees: Int,
    featureSubsetStrategy: String,
    impurity: String,
    maxDepth: Int,
    maxBins: Int,
    seed: Int = Utils.random.nextInt()): RandomForestModel = {
    val impurityType = Impurities.fromString(impurity)
    val strategy = if (categoricalFeaturesInfo == Map[Int, Int]()) {
      new Strategy(Regression, impurityType, maxDepth,
        0, maxBins, Sort, numTrees = numTrees, featureSubsetStrategy = featureSubsetStrategy)
    } else {
      new Strategy(Regression, impurityType, maxDepth,
        0, maxBins, Sort, Some(categoricalFeaturesInfo), numTrees, featureSubsetStrategy)
    }

    trainRegressor(input, strategy, seed)
  }

  /**
   * Java-friendly API for [[org.apache.spark.mllib.tree.RandomForest$#trainRegressor]]
   */
  def trainRegressor(
    input: JavaRDD[LabeledPoint],
    categoricalFeaturesInfo: java.util.Map[java.lang.Integer, java.lang.Integer],
    numTrees: Int,
    featureSubsetStrategy: String,
    impurity: String,
    maxDepth: Int,
    maxBins: Int,
    seed: Int): RandomForestModel = {
    trainRegressor(input.rdd,
      categoricalFeaturesInfo.asInstanceOf[java.util.Map[Int, Int]].asScala.toMap,
      numTrees, featureSubsetStrategy, impurity, maxDepth, maxBins, seed)
  }

  private[draftTree] class NodeIndexInfo(
    val nodeIndexInGroup: Int,
    val featureSubset: Option[Array[Int]]) extends Serializable

  /**
   * Pull nodes off of the queue, and collect a group of nodes to be split on this iteration.
   * This tracks the memory usage for aggregates and stops adding nodes when too much memory
   * will be needed; this allows an adaptive number of nodes since different nodes may require
   * different amounts of memory (if featureSubsetStrategy is not "all").
   *
   * @param nodeQueue  Queue of nodes to split.
   * @param maxMemoryUsage  Bound on size of aggregate statistics.
   * @return  (nodesForGroup, treeToNodeToIndexInfo).
   *          nodesForGroup holds the nodes to split: treeIndex --> nodes in tree.
   *
   *          treeToNodeToIndexInfo holds indices selected features for each node:
   *            treeIndex --> (global) node index --> (node index in group, feature indices).
   *          The (global) node index is the index in the tree; the node index in group is the
   *           index in [0, numNodesInGroup) of the node in this group.
   *          The feature indices are None if not subsampling features.
   */
  private[draftTree] def selectNodesToSplit(
    nodeQueue: mutable.Queue[(Int, Node)],
    maxMemoryUsage: Long,
    metadata: DecisionTreeMetadata,
    rng: scala.util.Random): (Map[Int, Array[Node]], Map[Int, Map[Int, NodeIndexInfo]]) = {
    // Collect some nodes to split:
    //  nodesForGroup(treeIndex) = nodes to split
    val mutableNodesForGroup = new mutable.HashMap[Int, mutable.ArrayBuffer[Node]]()
    val mutableTreeToNodeToIndexInfo =
      new mutable.HashMap[Int, mutable.HashMap[Int, NodeIndexInfo]]()
    var memUsage: Long = 0L
    var numNodesInGroup = 0
    while (nodeQueue.nonEmpty && memUsage < maxMemoryUsage) {
      val (treeIndex, node) = nodeQueue.head
      // Choose subset of features for node (if subsampling).
      val featureSubset: Option[Array[Int]] = if (metadata.subsamplingFeatures) {
        // TODO: Use more efficient subsampling?  (use selection-and-rejection or reservoir)
        Some(rng.shuffle(Range(0, metadata.numFeatures).toList)
          .take(metadata.numFeaturesPerNode).toArray)
      } else {
        None
      }
      // Check if enough memory remains to add this node to the group.
      val nodeMemUsage = RandomForest.aggregateSizeForNode(metadata, featureSubset) * 8L
      if (memUsage + nodeMemUsage <= maxMemoryUsage) {
        nodeQueue.dequeue()
        mutableNodesForGroup.getOrElseUpdate(treeIndex, new mutable.ArrayBuffer[Node]()) += node
        mutableTreeToNodeToIndexInfo
          .getOrElseUpdate(treeIndex, new mutable.HashMap[Int, NodeIndexInfo]())(node.id) = new NodeIndexInfo(numNodesInGroup, featureSubset)
      }
      numNodesInGroup += 1
      memUsage += nodeMemUsage
    }
    // Convert mutable maps to immutable ones.
    val nodesForGroup: Map[Int, Array[Node]] = mutableNodesForGroup.mapValues(_.toArray).toMap
    val treeToNodeToIndexInfo = mutableTreeToNodeToIndexInfo.mapValues(_.toMap).toMap
    (nodesForGroup, treeToNodeToIndexInfo)
  }

  /**
   * Get the number of values to be stored for this node in the bin aggregates.
   * @param featureSubset  Indices of features which may be split at this node.
   *                       If None, then use all features.
   */
  private[draftTree] def aggregateSizeForNode(
    metadata: DecisionTreeMetadata,
    featureSubset: Option[Array[Int]]): Long = {
    val totalBins = if (featureSubset.nonEmpty) {
      featureSubset.get.map(featureIndex => metadata.numBins(featureIndex).toLong).sum
    } else {
      metadata.numBins.map(_.toLong).sum
    }
    if (metadata.isClassification) {
      metadata.numClasses * totalBins
    } else {
      3 * totalBins
    }
  }

}
/**
 * this object is passed as a parametr to spark accumalable object
 * this object implements the methods ofAccumulableParam trait
 */
object MatrixAccumulatorParam extends AccumulableParam[Array[Array[Int]], (Int, Int, Int)] {

  def zero(initialValue: Array[Array[Int]]): Array[Array[Int]] = {
    initialValue
  }

  def addInPlace(m1: Array[Array[Int]], m2: Array[Array[Int]]): Array[Array[Int]] = {
    val columnLength: Int = m1.length
    val rowLength: Int = m1(0).length
    var updatedMatrix = Array.ofDim[Int](columnLength, rowLength)

    var j: Int = 0
    while (j < columnLength) {
      var i: Int = 0
      while (i < rowLength) {
        val a = Math.max(m1(j)(i), m2(j)(i))
        updatedMatrix(j)(i) = a
        i += 1
      }
      j += 1
    }

    updatedMatrix
  }

  def addAccumulator(acc: Array[Array[Int]], value: (Int, Int, Int)): Array[Array[Int]] = {

    acc(value._2)(value._3) = value._1
    acc

  }
}