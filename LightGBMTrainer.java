public clapackage tw.edu.cse.nsysu;

import io.github.metarank.lightgbm4j.LGBMDataset;
import io.github.metarank.lightgbm4j.LGBMBooster;
import java.util.List;

public class LightGBMTrainer {

    public static void run(List<float[]> train_x, List<Integer> train_y, List<float[]> test_x, List<Integer> test_y, int featureDim) {
        System.out.println("\n===LightGBM Training===");
        
        LGBMBooster booster = null;
        LGBMDataset train_matrix = null;
        LGBMDataset test_matrix = null;

        try {
            train_matrix = LGBMDataset.createFromMat(flatten(train_x, featureDim), train_x.size(), featureDim, true, "");
            train_matrix.setField("label", flattenLabels(train_y));
            
            test_matrix = LGBMDataset.createFromMat(flatten(test_x, featureDim), test_x.size(), featureDim, true, "");
            test_matrix.setField("label", flattenLabels(test_y));

            String params = "objective=binary metric=binary_logloss learning_rate=0.1 num_leaves=31 max_depth=6 seed=42";
            
            booster = LGBMBooster.create(train_matrix, params);
            int numIterations = 50;
            for (int i = 0; i < numIterations; i++) {
                booster.updateOneIter();
            }
            double[] predictions = booster.predictForMat(flatten(test_x, featureDim), test_x.size(), featureDim, true);
            
            float[][] predicts = new float[test_x.size()][1];
            for (int i = 0; i < predictions.length; i++) {
                predicts[i][0] = (float) predictions[i];
            }

            calculateAndPrintMetrics(predicts, test_y);

        } catch (Exception e) {
            System.err.println("LightGBM Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (booster != null) booster.close();
                if (train_matrix != null) train_matrix.close();
                if (test_matrix != null) test_matrix.close();
            } catch (Exception e) {
                System.err.println("Error : " + e.getMessage());
            }
        }
    }

    private static float[] flatten(List<float[]> list, int dim) {
        float[] flat = new float[list.size() * dim];
        for (int i = 0; i < list.size(); i++) {
            System.arraycopy(list.get(i), 0, flat, i * dim, dim);
        }
        return flat;
    }

    private static float[] flattenLabels(List<Integer> list) {
        float[] flat = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            flat[i] = list.get(i);
        }
        return flat;
    }

    private static void calculateAndPrintMetrics(float[][] predicts, List<Integer> test_y) {
        int correct = 0, tp = 0, fp = 0, fn = 0;
        for (int i = 0; i < test_y.size(); i++) {
            int predLabel = predicts[i][0] > 0.5 ? 1 : 0;
            int trueLabel = test_y.get(i);
            if (predLabel == trueLabel) correct++;
            if (predLabel == 1 && trueLabel == 1) tp++;
            if (predLabel == 1 && trueLabel == 0) fp++;
            if (predLabel == 0 && trueLabel == 1) fn++;
        }
        
        int total = test_y.size();
        double accuracy = (double) correct / total * 100;
        int tn = total - (fp + tp + fn);
        
        double precision1 = (tp + fp == 0) ? 0 : (double) tp / (tp + fp);
        double recall1 = (tp + fn == 0) ? 0 : (double) tp / (tp + fn);
        double f1_1 = (precision1 + recall1 == 0) ? 0 : 2 * (precision1 * recall1) / (precision1 + recall1);
        
        double precision0 = (tn + fn == 0) ? 0 : (double) tn / (tn + fn);
        double recall0 = (tn + fp == 0) ? 0 : (double) tn / (tn + fp);
        double f1_0 = (precision0 + recall0 == 0) ? 0 : 2 * (precision0 * recall0) / (precision0 + recall0);
        
        double macro_f1 = (f1_1 + f1_0) / 2;

        System.out.printf("Total Accuracy: %.2f%%\n", accuracy);
        System.out.println("---Class 1(Malicious)---");
        System.out.printf("Precision: %.4f\n", precision1);
        System.out.printf("Recall: %.4f\n", recall1);
        System.out.printf("F1-Score: %.4f\n", f1_1);
        System.out.println("---Class 0(Benign)---");
        System.out.printf("Precision: %.4f\n", precision0);
        System.out.printf("Recall: %.4f\n", recall0);
        System.out.printf("F1-Score: %.4f\n", f1_0);
        System.out.println("---Macro Average F1-Score---");
        System.out.printf("Macro F1-Score: %.4f\n", macro_f1);
    }
}