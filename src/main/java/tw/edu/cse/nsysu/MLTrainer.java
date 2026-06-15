package tw.edu.cse.nsysu;

import weka.classifiers.trees.RandomForest;
import weka.classifiers.trees.REPTree;
import weka.classifiers.functions.SMO;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.Evaluation;
import weka.core.*;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.Booster;
import java.sql.*;
import java.util.*;
import java.io.*;
public class MLTrainer{
    private Connection conn;
    public MLTrainer(Connection conn){
        this.conn=conn;
    }
    //A Model runner to connect to the database, get features for ML, and do the training
    public void ModelRunner(){
       // Step 1: Data Import & Base Feature Extraction
        List<double[]> allBaseFeatures = new ArrayList<>();
        List<Integer> allLabels = new ArrayList<>();

        String selectSql = "SELECT raw_sql, label from training_data";
        System.out.println("Extracting Base Features from DB...");
        
        try (Statement stmt = conn.createStatement(); ResultSet r = stmt.executeQuery(selectSql)) {
            while (r.next()) {
                String raw_query = r.getString("raw_sql");
                int label = r.getInt("label");
                
                //Extract 8 features only
                double[] baseVector = FeatureExtract.extract(raw_query);
                allBaseFeatures.add(baseVector);
                allLabels.add(label);
            }
        } catch (Exception e) {
            System.err.println("Database error during data import: " + e.getMessage());
            return;
        }

        int totalSize = allBaseFeatures.size();
        if (totalSize == 0) {
            System.out.println("No data found in database!");
            return;
        }

        //Step 2: Split Data (train:test= 8:2)
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < totalSize; i++) indices.add(i);
        Collections.shuffle(indices, new Random(42)); // fixed seed

        int trainSize = (int) (totalSize * 0.8);
        List<double[]> train_base_x = new ArrayList<>();
        List<Integer> train_y = new ArrayList<>();
        List<double[]> test_base_x = new ArrayList<>();
        List<Integer> test_y = new ArrayList<>();

        for (int i = 0; i < totalSize; i++) {
            int originalIdx = indices.get(i);
            if (i < trainSize) {
                train_base_x.add(allBaseFeatures.get(originalIdx));
                train_y.add(allLabels.get(originalIdx));
            } else {
                test_base_x.add(allBaseFeatures.get(originalIdx));
                test_y.add(allLabels.get(originalIdx));
            }
        }
        
        System.out.println("=== Data Pipeline Initialization Complete ===");
        System.out.println("Total dataset size: " + totalSize + " (Train: " + train_base_x.size() + ", Test: " + test_base_x.size() + ")");
        // Step 3: Transfer training set to transaction list store temporary to spmf txt file ===
        File spmfTrainFile = null;
        try {
            spmfTrainFile = File.createTempFile("spmf_train_", ".txt");
            spmfTrainFile.deleteOnExit(); //auto delete
            
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(spmfTrainFile))) {
                for (double[] base : train_base_x) {
                    Set<Integer> txSet = FeatureForML.toTransactionSet(base);
                    if (!txSet.isEmpty()) {
                        // A string split with spaces "1 3 4"
                        StringBuilder sb = new StringBuilder();
                        for (int item : txSet) {
                            sb.append(item).append(" ");
                        }
                        bw.write(sb.toString().trim());
                    }
                    bw.newLine();
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to create temporary SPMF file.");
            e.printStackTrace();
            return;
        }

        String trainTxtPath = spmfTrainFile.getAbsolutePath();
        FPGrowth fpGrowthMiner = new FPGrowth();
        //Step 4: 7 Scenario loops
        for (int situation = 1; situation <= 7; situation++) {
            System.out.println("\nSenario " + situation + ": " + getScenarioName(situation));
            long startTime = System.currentTimeMillis();
            
            FeatureForML dynamicFeatureEngine = new FeatureForML();

            // Dynamically call FPGrowth for pattern and rules
            if (situation == 2) {
                List<int[]> patterns = fpGrowthMiner.getFrequentPatterns(trainTxtPath, 0.05);
                dynamicFeatureEngine = new FeatureForML(patterns);
            } 
            else if (situation == 3) {
                List<int[]> patterns = fpGrowthMiner.getFrequentPatterns(trainTxtPath, 0.1);
                dynamicFeatureEngine = new FeatureForML(patterns);
            } 
            else if (situation == 4) {
                List<FeatureForML.Rule> rules = fpGrowthMiner.getAssociationRules(trainTxtPath, 0.1, 0.6);
                dynamicFeatureEngine.setRules(rules);
            } 
            else if (situation == 5) {
                List<FeatureForML.Rule> rules = fpGrowthMiner.getAssociationRules(trainTxtPath, 0.05, 0.6);
                dynamicFeatureEngine.setRules(rules);
            }
            // Situation 6 & 7 
            else if (situation == 6) {
                List<FeatureForML.Rule> rules = fpGrowthMiner.getAssociationRules(trainTxtPath, 0.05, 0.8);
                dynamicFeatureEngine.setRules(rules);
            }
            else if (situation == 7) {
                List<FeatureForML.Rule> rules = fpGrowthMiner.getAssociationRules(trainTxtPath, 0.1, 0.8);
                dynamicFeatureEngine.setRules(rules);
            }
            // step 5: Feature expansion
            List<float[]> train_expanded_x = new ArrayList<>();
            List<float[]> test_expanded_x = new ArrayList<>();

            for (double[] base : train_base_x) {
                train_expanded_x.add(dynamicFeatureEngine.getFeatureFromBase(base, situation));
            }
            for (double[] base : test_base_x) {
                test_expanded_x.add(dynamicFeatureEngine.getFeatureFromBase(base, situation));
            }

            long endTime = System.currentTimeMillis();
            int featureDim = train_expanded_x.isEmpty() ? 0 : train_expanded_x.get(0).length;
            System.out.printf("Feature engineering complete! Total time taken: %d ms, Feature dimension: %d\n", (endTime - startTime), featureDim);

            // Step 6: for ML models
            if (!train_expanded_x.isEmpty()) {
                try {
                    Instances wekaTrain = createWekaDataset(train_expanded_x, train_y, featureDim, "Train_data");
                    Instances wekaTest = createWekaDataset(test_expanded_x, test_y, featureDim, "Test_data");
                    System.out.println("== Tree Base ==");
                    //RandomForest
                    runWekaModel("RandomForest", new RandomForest(), wekaTrain, wekaTest);
                    //CART
                    runWekaModel("CART_Tree", new REPTree(), wekaTrain, wekaTest);
                    //XGBoost
                    runXGBoost(train_expanded_x, train_y, test_expanded_x, test_y, featureDim);
                    //LightGBM
                    runLightGBM(train_expanded_x, train_y, test_expanded_x, test_y, featureDim);
                    //SVM
                    runWekaModel("SVM (SMO)", new SMO(), wekaTrain, wekaTest);
                    System.out.println("== Linear Base ==");
                    //Naive Bayes
                    runWekaModel("Naive Bayes", new NaiveBayes(), wekaTrain, wekaTest);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // clean temp files
        if (spmfTrainFile != null && spmfTrainFile.exists()) {
            spmfTrainFile.delete();
        }
        System.out.println("===All ML training and Evaluation Complete!===");
    }

    private String getScenarioName(int situation) {
        switch (situation) {
            case 1: return "Pure Feature vectors";
            case 2: return "Feature vectors + Frequent Patterns(MinSup=0.05)";
            case 3: return "Feature vectors + Frequent Patterns(MinSup=0.1)";
            case 4: return "Feature vectors + Associate Rules(MinSup=0.1, MinConf=0.6)";
            case 5: return "Feature vectors + Associate Rules(MinSup=0.05, MinConf=0.6)";
            case 6: return "Feature vectors + Associate Rules(MinSup=0.05, MinConf=0.8)";
            case 7: return "Feature vectors + Associate Rules(MinSup=0.1, MinConf=0.8)";
            default: return "Unknown";
        }
    }
    // Weka model runner
    private void runWekaModel(String model_name, weka.classifiers.Classifier model, Instances train, Instances test) throws Exception {
        System.out.println("\n===" + model_name + "===");
        // Use training data to construct model
        model.buildClassifier(train);
        // Use testing data to do evaluation
        Evaluation eval = new Evaluation(train);
        eval.evaluateModel(model, test);
        
        // Print metrics
        printPerformanceMetrics(eval.pctCorrect(), eval.precision(1), eval.recall(1), eval.fMeasure(1),
                                eval.precision(0), eval.recall(0), eval.fMeasure(0));
    }
    
    // Boost model: XGBoost Runner
    private void runXGBoost(List<float[]> train_x, List<Integer> train_y, List<float[]> test_x, List<Integer> test_y, int featureDim) throws Exception {
        System.out.println("\n===XGBoost Training===");
        
        // Convert to one-dimension array using your helper functions
        DMatrix train_matrix = new DMatrix(flatten(train_x, featureDim), train_x.size(), featureDim, Float.NaN);
        train_matrix.setLabel(flattenLabels(train_y));
        DMatrix test_matrix = new DMatrix(flatten(test_x, featureDim), test_x.size(), featureDim, Float.NaN);
        test_matrix.setLabel(flattenLabels(test_y));
        
        Map<String, Object> params = new HashMap<>();
        params.put("eta", 0.1);
        params.put("max_depth", 6);
        params.put("objective", "binary:logistic");
        params.put("eval_metric", "logloss");
        params.put("seed", 42);
        
        // Train model
        Booster booster = XGBoost.train(train_matrix, params, 50, new HashMap<>(), null, null);
        
        // Test model
        float[][] predicts = booster.predict(test_matrix);
        calculateAndPrintMetrics(predicts, test_y);
    }
    private void runLightGBM(List<float[]> train_x, List<Integer> train_y, List<float[]> test_x, List<Integer> test_y, int featureDim) throws Exception {
        System.out.println("\n===LightGBM Training (Simulated via Weka Logistic-Trees)===");
        
        weka.classifiers.meta.LogitBoost lgbmAlternative = new weka.classifiers.meta.LogitBoost();
        lgbmAlternative.setNumIterations(50); // 對標 50 棵樹
        
        Instances wekaTrain = createWekaDataset(train_x, train_y, featureDim, "LGBM_Train");
        Instances wekaTest = createWekaDataset(test_x, test_y, featureDim, "LGBM_Test");
        
        lgbmAlternative.buildClassifier(wekaTrain);
        Evaluation eval = new Evaluation(wekaTrain);
        eval.evaluateModel(lgbmAlternative, wekaTest);
        
        printPerformanceMetrics(eval.pctCorrect(), eval.precision(1), eval.recall(1), eval.fMeasure(1),
                                eval.precision(0), eval.recall(0), eval.fMeasure(0));
    }

    // Helper function: create dataset for weka
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

    // Helper function to flatten to one-dimension
    private float[] flatten(List<float[]> list, int dim) {
        float[] flat = new float[list.size() * dim];
        for (int i = 0; i < list.size(); i++) {
            System.arraycopy(list.get(i), 0, flat, i * dim, dim);
        }
        return flat;
    }

    private float[] flattenLabels(List<Integer> list) {
        float[] flat = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            flat[i] = list.get(i);
        }
        return flat;
    }

    // For Boosting model: Evaluation
    private void calculateAndPrintMetrics(float[][] predicts, List<Integer> test_y) {
        int correct = 0, tp = 0, fp = 0, fn = 0;
        int total = test_y.size();
        for (int i = 0; i < total; i++) {
            int predLabel = predicts[i][0] > 0.5 ? 1 : 0;
            int trueLabel = test_y.get(i);
            if (predLabel == trueLabel) correct++;
            if (predLabel == 1 && trueLabel == 1) tp++;
            if (predLabel == 1 && trueLabel == 0) fp++;
            if (predLabel == 0 && trueLabel == 1) fn++;
        }
        
        double accuracy = (double) correct / total * 100;
        int tn = total - (fp + tp + fn);
        
        // Class 1 (Malicious)
        double precision1 = (tp + fp == 0) ? 0 : (double) tp / (tp + fp);
        double recall1 = (tp + fn == 0) ? 0 : (double) tp / (tp + fn);
        double f1_1 = (precision1 + recall1 == 0) ? 0 : 2 * (precision1 * recall1) / (precision1 + recall1);
        
        // Class 0 (Benign)
        double precision0 = (tn + fn == 0) ? 0 : (double) tn / (tn + fn);
        double recall0 = (tn + fp == 0) ? 0 : (double) tn / (tn + fp);
        double f1_0 = (precision0 + recall0 == 0) ? 0 : 2 * (precision0 * recall0) / (precision0 + recall0);
        
        printPerformanceMetrics(accuracy, precision1, recall1, f1_1, precision0, recall0, f1_0);
    }

    // Centralized print method for metrics consistency
    private void printPerformanceMetrics(double acc, double p1, double r1, double f1_1, double p0, double r0, double f1_0) {
        double macro_f1 = (f1_1 + f1_0) / 2;
        System.out.printf("Total Accuracy: %.2f%%\n", acc);
        System.out.println("---Class 1(Malicious)---");
        System.out.printf("Precision: %.4f\n", p1);
        System.out.printf("Recall: %.4f\n", r1);
        System.out.printf("F1-Score: %.4f\n", f1_1);
        System.out.println("---Class 0(Benign)---");
        System.out.printf("Precision: %.4f\n", p0);
        System.out.printf("Recall: %.4f\n", r0);
        System.out.printf("F1-Score: %.4f\n", f1_0);
        System.out.println("---Macro Average F1-Score---");
        System.out.printf("Macro F1-Score: %.4f\n", macro_f1);
    }
}