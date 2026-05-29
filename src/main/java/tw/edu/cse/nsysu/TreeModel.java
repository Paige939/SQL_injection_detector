package tw.edu.cse.nsysu;

import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import java.sql.*;
import java.util.*;

public class TreeModel {
    private FeatureForML features;
    private Connection conn;

    public TreeModel(FeatureForML features, Connection conn) {
        this.features = features;
        this.conn = conn;
    }

    public void runModels() {
        for (int situation = 1; situation <= 7; situation++) {
            String scenario = getScenarioName(situation);
            System.out.println("Scenario " + situation + ": " + scenario);
            
            List<float[]> featureRecords = new ArrayList<>();
            List<Integer> labels = new ArrayList<>();
            String selectSql = "SELECT raw_sql, label from training_data";

            try (Statement stmt = conn.createStatement(); ResultSet r = stmt.executeQuery(selectSql)) {
                conn.setAutoCommit(false);
                int count = 0;
                long startTime = System.currentTimeMillis();

                while (r.next()) {
                    String raw_query = r.getString("raw_sql");
                    int label = r.getInt("label");
                    float[] feature_for_ml = features.getFeature(raw_query, situation);
                    featureRecords.add(feature_for_ml);
                    labels.add(label);
                    count++;
                }
                long endTime = System.currentTimeMillis();
                int featureDim = featureRecords.isEmpty() ? 0 : featureRecords.get(0).length;
                
                System.out.printf("complete Time: %d ms, Data count: %d, Feature dim: %d\n", 
                                  (endTime - startTime), count, featureDim);

                if (!featureRecords.isEmpty()) {
                    evaluateTwoModels(featureRecords, labels, featureDim);
                } else {
                    System.out.println("empty No data");
                }

            } catch (Exception e) {
                System.err.println("Error in executing scenario " + situation + ": ");
                e.printStackTrace();
            }
            
            System.gc();
        }
        System.out.println("\n=== All Complete ===");
    }

    private void evaluateTwoModels(List<float[]> featureRecords, List<Integer> labels, int featureDim) throws Exception {
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
        System.out.println("Data Split complete! Total: " + totalSize + 
                           " (Train: " + train_x.size() + ", Test: " + test_x.size() + ")");

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

    private String getScenarioName(int situation) {
        switch (situation) {
            case 1: return "Pure Feature vectors";
            case 2: return "Feature vectors + Frequent Patterns (MinSup=0.05)";
            case 3: return "Feature vectors + Frequent Patterns (MinSup=0.1)";
            case 4: return "Feature vectors + Associate Rules (MinSup=0.1, MinConf=0.6)";
            case 5: return "Feature vectors + Associate Rules (MinSup=0.05, MinConf=0.6)";
            case 6: return "Feature vectors + Associate Rules (MinSup=0.05, MinConf=0.8)";
            case 7: return "Feature vectors + Associate Rules (MinSup=0.1, MinConf=0.8)";
            default: return "Unknown Scenario";
        }
    }
}
