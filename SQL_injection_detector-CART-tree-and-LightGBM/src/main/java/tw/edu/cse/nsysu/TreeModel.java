package tw.edu.cse.nsysu;

import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import java.util.*;

public class TreeModel {
    
    public TreeModel() {
    }

    public void runPipeline(List<float[]> featureRecords, List<Integer> labels, String scenarioName) {
        System.out.println("\n=======================================================");
        System.out.println("Running Training Pipeline: " + scenarioName);
        System.out.println("=======================================================");
        
        if (featureRecords == null || featureRecords.isEmpty() || labels == null || labels.isEmpty()) {
            System.err.println("Error: Input data is empty. Aborting pipeline.");
            return;
        }

        if (featureRecords.size() != labels.size()) {
            System.err.println("Error: The number of features does not match the number of labels.");
            return;
        }

        int featureDim = featureRecords.get(0).length;
        System.out.printf("Data loaded! Total count: %d, Feature dimension: %d\n", featureRecords.size(), featureDim);

        try {
            evaluateModels(featureRecords, labels, featureDim);
        } catch (Exception e) {
            System.err.println("Error in executing pipeline: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.gc();
    }

    private void evaluateModels(List<float[]> featureRecords, List<Integer> labels, int featureDim) throws Exception {
        int totalSize = featureRecords.size();
        
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
        
        System.out.println("Data Split complete! (Train: " + train_x.size() + ", Test: " + test_x.size() + ")");

        Instances wekaTrain = createWekaDataset(train_x, train_y, featureDim, "Train_data");
        Instances wekaTest = createWekaDataset(test_x, test_y, featureDim, "Test_data");
        CartTrainer.run(wekaTrain, wekaTest);
        LightGBMTrainer.run(train_x, train_y, test_x, test_y, featureDim);
    }

    private Instances createWekaDataset(List<float[]> features, List<Integer> labels, int featureDim, String name) {
        ArrayList<Attribute> attrs = new ArrayList<>();
        for (int i = 0; i < featureDim; i++) {
            attrs.add(new Attribute("f_" + i));
        }
        attrs.add(new Attribute("target_label", Arrays.asList("0", "1")));
        
        Instances dataset = new Instances(name, attrs, features.size());
        dataset.setClassIndex(featureDim);

        for (int i = 0; i < features.size(); i++) {
            Instance inst = new DenseInstance(featureDim + 1);
            for (int j = 0; j < featureDim; j++) {
                inst.setValue(attrs.get(j), features.get(i)[j]);
            }
            inst.setValue(attrs.get(featureDim), String.valueOf(labels.get(i)));
            dataset.add(inst);
        }
        return dataset;
    }
}