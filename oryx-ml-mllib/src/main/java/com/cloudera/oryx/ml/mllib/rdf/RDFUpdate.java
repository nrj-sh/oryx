/*
 * Copyright (c) 2014, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.oryx.ml.mllib.rdf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.typesafe.config.Config;
import org.apache.hadoop.fs.Path;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.apache.spark.mllib.tree.RandomForest;
import org.apache.spark.mllib.tree.model.RandomForestModel;
import org.dmg.pmml.PMML;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.oryx.common.random.RandomManager;
import com.cloudera.oryx.ml.MLUpdate;
import com.cloudera.oryx.ml.common.fn.MLFunctions;
import com.cloudera.oryx.ml.param.HyperParamValues;
import com.cloudera.oryx.ml.param.HyperParams;
import com.cloudera.oryx.ml.schema.InputSchema;

public final class RDFUpdate extends MLUpdate<String> {

  private static final Logger log = LoggerFactory.getLogger(RDFUpdate.class);

  private final int numTrees;
  private final List<HyperParamValues<?>> hyperParamValues;
  private final InputSchema inputSchema;


  public RDFUpdate(Config config) {
    super(config);
    numTrees = config.getInt("oryx.rdf.hyperparams.num-trees");
    Preconditions.checkArgument(numTrees >= 1);
    hyperParamValues = Arrays.asList(
        HyperParams.fromConfig(config, "oryx.rdf.hyperparams.max-split-candidates"),
        HyperParams.fromConfig(config, "oryx.rdf.hyperparams.max-depth"),
        HyperParams.fromConfig(config, "oryx.rdf.hyperparams.impurity"));

    inputSchema = new InputSchema(config);
    Preconditions.checkNotNull(inputSchema.getTargetFeature());
  }

  @Override
  public List<HyperParamValues<?>> getHyperParameterValues() {
    return hyperParamValues;
  }

  @Override
  public PMML buildModel(JavaSparkContext sparkContext,
                         JavaRDD<String> trainData,
                         List<?> hyperParameters,
                         Path candidatePath) {

    int maxSplitCandidates = (Integer) hyperParameters.get(0);
    int maxDepth = (Integer) hyperParameters.get(1);
    String impurity = (String) hyperParameters.get(2);
    Preconditions.checkArgument(maxSplitCandidates > 0);
    Preconditions.checkArgument(maxDepth > 0);

    JavaRDD<String[]> parsedRDD = trainData.map(MLFunctions.PARSE_FN);
    Map<Integer,Map<String,Double>> distinctValueMaps = getDistinctValueMap(parsedRDD);
    JavaRDD<LabeledPoint> trainPointData = parsedToRatingRDD(parsedRDD, distinctValueMaps);

    Map<Integer,Integer> categoryInfo = new HashMap<>();
    for (Map.Entry<Integer,Map<String,Double>> e : distinctValueMaps.entrySet()) {
      categoryInfo.put(e.getKey(), e.getValue().size());
    }

    int seed = RandomManager.getRandom().nextInt();

    RandomForestModel model;
    if (inputSchema.isClassification()) {
      int numTargetClasses = distinctValueMaps.get(inputSchema.getTargetFeatureIndex()).size();
      model = RandomForest.trainClassifier(trainPointData,
                                           numTargetClasses,
                                           categoryInfo,
                                           numTrees,
                                           "auto",
                                           impurity,
                                           maxDepth,
                                           maxSplitCandidates,
                                           seed);
    } else {
      model = RandomForest.trainRegressor(trainPointData,
                                          categoryInfo,
                                          numTrees,
                                          "auto",
                                          impurity,
                                          maxDepth,
                                          maxSplitCandidates,
                                          seed);
    }
    // TODO



    return null;
  }

  @Override
  public double evaluate(JavaSparkContext sparkContext,
                         PMML model,
                         Path modelParentPath,
                         JavaRDD<String> testData) {
    return 0;
  }

  private Map<Integer,Map<String,Double>> getDistinctValueMap(JavaRDD<String[]> parsedRDD) {
    Map<Integer,Set<String>> distinctValues = getDistinctValues(parsedRDD);
    Map<Integer,Map<String,Double>> distinctValueMaps = new HashMap<>(distinctValues.size());
    for (Map.Entry<Integer,Set<String>> e : distinctValues.entrySet()) {
      distinctValueMaps.put(e.getKey(), mapDistinctValues(e.getValue()));
    }
    return distinctValueMaps;
  }

  private Map<Integer,Set<String>> getDistinctValues(JavaRDD<String[]> parsedRDD) {
    final List<Integer> categoricalIndices = new ArrayList<>();
    for (int i = 0; i < inputSchema.getNumFeatures(); i++) {
      if (inputSchema.isCategorical(i)) {
        categoricalIndices.add(i);
      }
    }

    JavaRDD<Map<Integer,Set<String>>> distinctValuesByPartition = parsedRDD.mapPartitions(
        new FlatMapFunction<Iterator<String[]>, Map<Integer,Set<String>>>() {
          @Override
          public Iterable<Map<Integer,Set<String>>> call(Iterator<String[]> data) {
            Map<Integer,Set<String>> distinctCategoricalValues = new HashMap<>();
            for (int i : categoricalIndices) {
              distinctCategoricalValues.put(i, new HashSet<String>());
            }
            while (data.hasNext()) {
              String[] datum = data.next();
              for (Map.Entry<Integer,Set<String>> e : distinctCategoricalValues.entrySet()) {
                e.getValue().add(datum[e.getKey()]);
              }
            }
            return Collections.singletonList(distinctCategoricalValues);
          }
        });

    return distinctValuesByPartition.reduce(
        new Function2<Map<Integer,Set<String>>, Map<Integer,Set<String>>, Map<Integer,Set<String>>>() {
          @Override
          public Map<Integer,Set<String>> call(Map<Integer,Set<String>> v1,
                                               Map<Integer,Set<String>> v2) {
            for (Map.Entry<Integer,Set<String>> e : v1.entrySet()) {
              e.getValue().addAll(v2.get(e.getKey()));
            }
            return v1;
          }
        });
  }

  private static <T extends Comparable<T>> Map<T,Double> mapDistinctValues(Collection<T> distinct) {
    List<T> sortedDistinct = new ArrayList<>(distinct);
    Collections.sort(sortedDistinct);
    Map<T,Double> mapping = new HashMap<>();
    for (int i = 0; i < sortedDistinct.size(); i++) {
      mapping.put(sortedDistinct.get(i), (double) i);
    }
    return mapping;
  }


  private JavaRDD<LabeledPoint> parsedToRatingRDD(
      JavaRDD<String[]> parsedRDD,
      final Map<Integer,Map<String,Double>> distinctValueMaps) {

    return parsedRDD.map(new Function<String[],LabeledPoint>() {
      @Override
      public LabeledPoint call(String[] data) {
        double[] features = new double[data.length - 1]; // - 1 because one of them is the target
        double target = Double.NaN;
        int offset = 0;
        for (int i = 0; i < data.length; i++) {
          Map<String,Double> mapping = distinctValueMaps.get(i);
          double encoded;
          if (mapping == null) { // Numeric feature
            encoded = Double.parseDouble(data[i]);
          } else { // Categorical feature
            encoded = mapping.get(data[i]);
          }
          if (inputSchema.isTarget(i)) {
            target = encoded;
            offset = 1; // cause the rest of vector to be moved up one relative to data
          } else {
            features[i - offset] = encoded;
          }
        }
        Preconditions.checkState(!Double.isNaN(target));
        return new LabeledPoint(target, Vectors.dense(features));
      }
    });
  }



}