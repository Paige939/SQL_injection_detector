package tw.edu.cse.nsysu;

import weka.classifiers.trees.RandomForest;
import weka.classifiers.Evaluation;
import weka.core.*;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.Booster;
import java.sql.*;
import java.util.*;

public class MLTrainer{
    private FeatureForML features;
    private Connection conn;
    public MLTrainer(FeatureForML features, Connection conn){
        this.features=features;
        this.conn=conn;
    }
    //A Model runner to connect to the database, get features for ML, and do the training
    public void ModelRunner(){
        for(int situation=1;situation<=7;situation++){
            String senario="";
            if(situation==1) senario="Pure Feature vectors";
            else if(situation==2) senario="Feature vectors + Frequent Patterns(MinSup=0.05)";
            else if(situation==3) senario="Feature vectors + Frequent Patterns(MinSup=0.1)";
            else if(situation==4) senario="Feature vectors + Associate Rules(MinSup=0.1, MinConf=0.6)";
            else if(situation==5) senario="Feature vectors + Associate Rules(MinSup=0.05, MinConf=0.6)";
            else if(situation==6) senario="Feature vectors + Associate Rules(MinSup=0.05, MinConf=0.8)";
            else if(situation==7) senario="Feature vectors + Associate Rules(MinSup=0.1, MinConf=0.8)";
            System.out.println("\nSenario "+situation+": "+senario);
            List<float[]> featureRecords=new ArrayList<>();
            List<Integer> labels=new ArrayList<>();
            String selectSql="SELECT raw_sql, label from training_data";
            try(Statement stmt=conn.createStatement(); ResultSet r=stmt.executeQuery(selectSql)){
                conn.setAutoCommit(false);
                int count=0;
                long startTime=System.currentTimeMillis();
                while(r.next()){
                    String raw_query=r.getString("raw_sql");
                    int label=r.getInt("label");
                    float [] feature_for_ml=features.getFeature(raw_query, situation);
                    featureRecords.add(feature_for_ml);
                    labels.add(label);
                    count++;
                    /*if(count%1000==0){
                        System.out.println("Has complete "+count+" batch of feature transmission for ML");
                    }*/
                }
                long endTime=System.currentTimeMillis();
                int featureDim=featureRecords.isEmpty()? 0: featureRecords.get(0).length;
                System.out.printf("Feature engineering complete! Total time taken: %d ms, Total number of data: %d, Feature dimension: %d\n",(endTime-startTime), count, featureDim);
                 if(!featureRecords.isEmpty()){
                    AllModelEvaluate(featureRecords, labels, featureDim);
                }
                else{
                    System.out.println("Feature Records is empty! No data Exists.");
                }
            }catch(Exception e){
                System.err.println("Error in executing senario "+situation+": ");
                e.printStackTrace();

            }
           System.gc();
        }
        System.out.println("===All ML training and Evaluation Complete!===");
    }
    //Use Weka Instances structure
    public static void AllModelEvaluate(List<float[]> featureRecords, List<Integer> labels, int featureDim) throws Exception{
        int totalSize=featureRecords.size();
        //Construct index table and shuffle it to ensure the random data distribution
        List<Integer> indices=new ArrayList<>();
        for(int i=0;i<totalSize;i++){
            indices.add(i);
        }
        Collections.shuffle(indices, new Random(42));
        //Cut data into training data and test data
        int trainSize=(int)(totalSize*0.8);
        List<float[]> train_x=new ArrayList<>();
        List<Integer> train_y=new ArrayList<>();
        List<float[]> test_x=new ArrayList<>();
        List<Integer> test_y=new ArrayList<>();
        for(int i=0;i<totalSize;i++){
            int originalIdx=indices.get(i);
            if(i<trainSize){
                train_x.add(featureRecords.get(originalIdx));
                train_y.add(labels.get(originalIdx));
            }else{
                test_x.add(featureRecords.get(originalIdx));
                test_y.add(labels.get(originalIdx));
            }
        }
        System.out.println("Data Split complete! Total: "+totalSize+"(Training data:"+train_x.size()+", Testing data: "+test_x.size()+")");
        //---Weka models: Random Forest & Cart tree---
        Instances wekaTrain=createWekaDataset(train_x, train_y, featureDim, "Train_data");
        Instances wekaTest=createWekaDataset(test_x, test_y, featureDim, "Test_data");
        runWekaModel("RandomForest", new RandomForest(), wekaTrain, wekaTest);
        //Implementation for CART tree here...

        //---Boosting models: XGBoost & LightGBM---
        runXGBoost(train_x, train_y, test_x, test_y, featureDim);
        //Implementation for LightGBM here...

    }
   
    //Weka model: RandomForest runner & CART tree runner
    private static void runWekaModel(String model_name, weka.classifiers.Classifier model, Instances train, Instances test) throws Exception{
        System.out.println("\n==="+model_name+"===");
        //Use training data to construct model
        model.buildClassifier(train);
        //Use testing data to do evaluation
        Evaluation eval=new Evaluation(train);
        eval.evaluateModel(model, test);
        //Get accuracy
        double accuracy=eval.pctCorrect();
        //Label=1 (Malicious) Evaluation
        double precision1=eval.precision(1);
        double recall1=eval.recall(1);
        double f1_1=eval.fMeasure(1);
        //Label=0 (Benign) Evaluation
        double precision0=eval.precision(0);
        double recall0=eval.recall(0);
        double f1_0=eval.fMeasure(0);
        //Macro average F1
        double macro_f1=(f1_1+f1_0)/2;
        //Print out the result
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
    
    //Boost model: XGBoost Runner
    private static void runXGBoost(List<float[]> train_x, List<Integer> train_y, List<float[]> test_x, List<Integer> test_y, int featureDim) throws Exception{
        System.out.println("\n===XGBoost Taining===");
        //Convert to one-dimension array
        DMatrix train_matrix=new DMatrix(flatten(train_x, featureDim), train_x.size(), featureDim, Float.NaN);
        train_matrix.setLabel(flattenLabels(train_y));
        DMatrix test_matrix=new DMatrix(flatten(test_x, featureDim), test_x.size(), featureDim, Float.NaN);
        test_matrix.setLabel(flattenLabels(test_y));
        Map<String, Object> params=new HashMap<>();
        //Setting trees parameters
        params.put("eta", 0.1);
        params.put("max_depth", 6);
        params.put("objective", "binary:logistic");
        params.put("eval_metric", "logloss");
        params.put("seed", 42);
        //Train model: Use training data
        Booster booster=XGBoost.train(train_matrix, params, 50, new HashMap<>(), null, null);
        //Test model: Use testing data
        float[][] predicts=booster.predict(test_matrix);
        calculateAndPrintMetrics(predicts, test_y);
    }
    //Boost model: LightGBM runner
    

    //Helper function: create dataset for weka
    private static Instances createWekaDataset(List<float[]> features, List<Integer> labels, int featureDim, String name){
        ArrayList<Attribute> attrs=new ArrayList<>();
        for(int i=0;i<featureDim;i++) attrs.add(new Attribute("f_"+i));
        attrs.add(new Attribute("target_label", Arrays.asList("0", "1")));
        Instances dataset=new Instances(name, attrs, features.size());
        dataset.setClassIndex(featureDim);

        for (int i=0; i<features.size(); i++) {
            Instance inst=new DenseInstance(featureDim+1);
            for (int j=0; j<featureDim; j++) inst.setValue(attrs.get(j), features.get(i)[j]);
            inst.setValue(attrs.get(featureDim), String.valueOf(labels.get(i)));
            dataset.add(inst);
        }
        return dataset;
    }
    //Helper function to flatten to one-dimension
    private static float[] flatten(List<float[]> list, int dim) {
        float[] flat=new float[list.size() * dim];
        for (int i=0; i<list.size(); i++) System.arraycopy(list.get(i), 0, flat, i*dim, dim);
        return flat;
    }
    private static float[] flattenLabels(List<Integer> list) {
        float[] flat=new float[list.size()];
        for (int i=0; i<list.size(); i++) flat[i]=list.get(i);
        return flat;
    }
    //For Boosting model: Evaluation
    private static void calculateAndPrintMetrics(float[][] predicts, List<Integer> test_y) {
        int correct=0, tp=0, fp=0, fn=0;
        for (int i=0; i<test_y.size(); i++) {
            int predLabel=predicts[i][0]>0.5 ? 1 : 0;
            int trueLabel=test_y.get(i);
            if (predLabel==trueLabel) correct++;
            if (predLabel==1 && trueLabel==1) tp++;
            if (predLabel==1 && trueLabel==0) fp++;
            if (predLabel==0 && trueLabel==1) fn++;
        }
        printMetrics(correct, tp, fp, fn, test_y.size());
    }
    //For Boosting model: print out result
    private static void printMetrics(int correct, int tp, int fp, int fn, int total) {
        double accuracy=(double) correct/total*100;
        int tn=total-(fp+tp+fn);
        //Class 1 (Malicious)
        double precision1=(tp+fp==0) ? 0 : (double) tp/(tp+fp);
        double recall1=(tp+fn==0) ? 0 : (double) tp/(tp+fn);
        double f1_1=(precision1 + recall1==0) ? 0 : 2*(precision1*recall1)/(precision1+recall1);
        //Class 0 (Begign)
        double precision0=(tn+fn==0) ? 0 : (double) tn/(tn+fn);
        double recall0=(tn+fp==0) ? 0 : (double) tn/(tn+fp);
        double f1_0=(precision0 + recall0==0) ? 0 : 2*(precision0*recall0)/(precision0+recall0);
        //Macro Average
        double macro_f1=(f1_1+f1_0)/2;

        //Print out the result
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