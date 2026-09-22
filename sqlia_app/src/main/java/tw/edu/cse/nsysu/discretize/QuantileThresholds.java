package tw.edu.cse.nsysu.discretize;

import java.util.*;
public class QuantileThresholds {
    public double lengthQ50, lengthQ90;
    public double symbolQ50, symbolQ90;
    public double comparisonQ90;
    public double functionQ90;
    public double discontinuousQ50, discontinuousQ90;

    //Only use training set (Only benign samples for normal profile)
    public static QuantileThresholds fromTrainingFeatures(List<double[]> trainingFeatureVectors){
        QuantileThresholds t = new QuantileThresholds();
        // Compute the quantile thresholds from the training feature vectors and assign them to t
        t.lengthQ50 = quantile(trainingFeatureVectors, 0, 0.5);
        t.lengthQ90 = quantile(trainingFeatureVectors, 0, 0.9);
        t.symbolQ50 = quantile(trainingFeatureVectors, 1, 0.5);
        t.symbolQ90 = quantile(trainingFeatureVectors, 1, 0.9);
        t.comparisonQ90 = quantile(trainingFeatureVectors, 2, 0.9);
        t.functionQ90 = quantile(trainingFeatureVectors, 4, 0.9);
        t.discontinuousQ50 = quantile(trainingFeatureVectors, 5, 0.5);
        t.discontinuousQ90 = quantile(trainingFeatureVectors, 5, 0.9);
        return t;
    }
    // Compute the q-th quantile for the specified feature index from the list of feature vectors
    private static double quantile(List<double[]> vectors, int featureIndex, double q) {
        double[] values = vectors.stream().mapToDouble(v -> v[featureIndex]).sorted().toArray();
        if(values.length == 0) return 0.0;
        int idx = (int) Math.ceil(q * (values.length - 1));
        return values[Math.min(idx, values.length - 1)];
    }
}