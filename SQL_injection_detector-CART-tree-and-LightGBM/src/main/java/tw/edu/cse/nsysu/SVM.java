package tw.edu.cse.nsysu;

import weka.classifiers.functions.SMO;
import weka.classifiers.Evaluation;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import java.util.*;

public class SVM {
    
    public static void run(List<float[]> featureRecords, List<Integer> labels, String scenarioName) {
        System.out.println("\n=== Support Vector Machine (SMO) | " + scenarioName + " ===");
        if (featureRecords.isEmpty()) return;

        int featureDim = featureRecords.get(0).length;
        int totalSize = featureRecords.size();
        
        try {

            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < totalSize; i++) indices.add(i);
            Collections.shuffle(indices, new Random(42)); 
            
            int trainSize = (int) (totalSize * 0.8);
            List<float[]> train_x = new ArrayList<>();
            List<Integer> train_y = new ArrayList<>();
            List<float[]> test_x = new ArrayList<>();
            List<Integer> test_y = new ArrayList<>();
            
            for (int i = 0; i < totalSize; i++) {
                int originalIdx = indices.get(i);
                if (i < trainSize) {
                    train_x.add(featureRecords.get(originalIdx));
                    train_y.add(labels.get(originalIdx));
                } else {
                    test_x.add(featureRecords.get(originalIdx));
                    test_y.add(labels.get(originalIdx));
                }
            }

            Instances wekaTrain = createWekaDataset(train_x, train_y, featureDim, "Train_data");
            Instances wekaTest = createWekaDataset(test_x, test_y, featureDim, "Test_data");

            SMO svmModel = new SMO();
            svmModel.buildClassifier(wekaTrain);

            Evaluation eval = new Evaluation(wekaTrain);
            eval.evaluateModel(svmModel, wekaTest);

            printMetrics(eval);

        } catch (Exception e) {
            System.err.println("SVM Training Error: " + e.getMessage());
        }
    }

    private static Instances createWekaDataset(List<float[]> features, List<Integer> labels, int featureDim, String name) {
        ArrayList<Attribute> attrs = new ArrayList<>();
        for (int i = 0; i < featureDim; i++) attrs.add(new Attribute("f_" + i));
        attrs.add(new Attribute("target_label", Arrays.asList("0", "1")));
        
        Instances dataset = new Instances(name, attrs, features.size());
        dataset.setClassIndex(featureDim);

        for (int i = 0; i < features.size(); i++) {
            Instance inst = new DenseInstance(featureDim + 1);
            for (int j = 0; j < featureDim; j++) inst.setValue(attrs.get(j), features.get(i)[j]);
            inst.setValue(attrs.get(featureDim), String.valueOf(labels.get(i)));
            dataset.add(inst);
        }
        return dataset;
    }

    private static void printMetrics(Evaluation eval) {
        double accuracy = eval.pctCorrect();
        double precision1 = eval.precision(1), recall1 = eval.recall(1), f1_1 = eval.fMeasure(1);
        double precision0 = eval.precision(0), recall0 = eval.recall(0), f1_0 = eval.fMeasure(0);
        
        System.out.printf("Total Accuracy: %.2f%%\n", accuracy);
        System.out.printf("Class 1 (Malicious) -> Precision: %.4f | Recall: %.4f | F1: %.4f\n", precision1, recall1, f1_1);
        System.out.printf("Class 0 (Benign)    -> Precision: %.4f | Recall: %.4f | F1: %.4f\n", precision0, recall0, f1_0);
        System.out.printf("Macro Average F1    -> %.4f\n", (f1_1 + f1_0) / 2);
    }
}