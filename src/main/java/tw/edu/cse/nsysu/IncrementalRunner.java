package tw.edu.cse.nsysu;

import weka.classifiers.bayes.NaiveBayesUpdateable;
import weka.core.Attribute;
import weka.core.Instances;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class IncrementalRunner{
    private final Connection conn; //Database connection
    public IncrementalRunner(Connection conn){
        this.conn = conn;
    }
    public void run(int warmupSize, int batchSize, int ensembleSize){
        try{
            FeatureForML featureEngine = new FeatureForML(); //Feature conversion engine
            int situation = 1;
            List<TrainRow> warmupData = fetchWarmupData(warmupSize); //Fetch warmup data from the database
            if(warmupData.isEmpty()){  //If no warmup data is available, print a message and return
                System.out.println("No warmup data available.");
                return; 
            }
            float[] firstX = toFeature(warmupData.get(0).rawSql, featureEngine, situation); //Convert the first warmup SQL to feature vector
            Instances header = createHeader(firstX.length); //Create the header for the dataset
            OnlineBagging bagging = new OnlineBagging(new NaiveBayesUpdateable(), ensembleSize, 42L); //Initialize the OnlineBagging classifier with Naive Bayes
            bagging.initialize(header); //Initialize the ensemble with the header

            for(TrainRow row : warmupData){ //Train the ensemble with the warmup data
                float[] x = toFeature(row.rawSql, featureEngine, situation);
                bagging.update(x, row.label);
            }
            markAsTrained(warmupData); //Mark the warmup data as trained in the database
            System.out.println("Warmup complete:" + warmupData.size() + " instances trained.");
            int total = 0; //Counter for the total number of instances processed
            while(true){ //Continuous loop to fetch and process new data
                List<TrainRow> batch = fetchUntrainedBatch(batchSize); //Fetch a batch of untrained data from the database
                if(batch.isEmpty()){ //If no new data is available, print a message and break
                    System.out.println("No new data available. Waiting for new data...");
                    break;
                }

                int correct = 0; //Counter for the number of correct prequential predictions in current batch
                int n = 0; //Counter for the number of instances in current batch

                for(TrainRow row : batch){ //Process each instance in the batch
                    float[] x = toFeature(row.rawSql, featureEngine, situation); //Convert SQL to feature vector
                    int predictedLabel = bagging.predictLabel(x); //Predict the label using the ensemble
                    if(predictedLabel == row.label) correct++; //Increment correct counter if prediction is correct
                    bagging.update(x, row.label); //Update the ensemble with the true label
                    n++;
                }
                markAsTrained(batch); //Mark the batch as trained in the database
                total += n; //Update the total number of instances processed
                double accuracy = n == 0 ? 0.0 : (double) correct / n; //Calculate accuracy for the current batch
                System.out.println("Batch processed: " + n + " instances, Correct predictions: " + correct + ", Accuracy: " + (accuracy * 100.0) + "%");
            }
            System.out.println("Incremental learning completed. Total instances processed: " + total);
        }catch(Exception e){
            System.err.println("Incremental learning error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private void markAsTrained(List<TrainRow> rows) throws Exception{
        String sql = "UPDATE training_data SET is_trained=1 WHERE id=?"; //update status
        try(PreparedStatement pstmt = conn.prepareStatement(sql)){
            for(TrainRow r : rows){ //Iterate through each row in the batch
                pstmt.setInt(1, r.id); //Set the ID parameter for the update statement
                pstmt.addBatch(); //Add the update statement to the batch
            }
            pstmt.executeBatch(); //Execute the batch update
        }
    }

    private List<TrainRow> fetchWarmupData(int limit) throws Exception{
        return fetchUntrainedBatch(limit);
    }

    private List<TrainRow> fetchUntrainedBatch(int limit) throws Exception{
        String sql = "SELECT id, raw_sql, label FROM training_data WHERE is_trained=0 ORDER BY id LIMIT ?";
        List<TrainRow> rows = new ArrayList<>();
        try(PreparedStatement pstmt = conn.prepareStatement(sql)){
            pstmt.setInt(1, limit);
            try(ResultSet resultSet = pstmt.executeQuery()){
                while(resultSet.next()){
                    rows.add(new TrainRow(
                            resultSet.getInt("id"),
                            resultSet.getString("raw_sql"),
                            resultSet.getInt("label")
                    ));
                }
            }
        }
        return rows;
    }

    private float[] toFeature(String rawSql, FeatureForML featureEngine, int situation){
        double[] baseFeatures = FeatureExtract.extract(rawSql);
        return featureEngine.getFeatureFromBase(baseFeatures, situation);
    }

    private Instances createHeader(int featureDimension){
        ArrayList<Attribute> attributes = new ArrayList<>();
        for(int i = 0; i < featureDimension; i++){
            attributes.add(new Attribute("f_" + i));
        }
        attributes.add(new Attribute("target_label", Arrays.asList("0", "1")));
        Instances header = new Instances("incremental_data", attributes, 0);
        header.setClassIndex(featureDimension);
        return header;
    }
    private static class TrainRow{
        final int id; //Unique identifier for the training row
        final String rawSql; //Raw SQL query string
        final int label; //Label for the training row (0 or 1)
        TrainRow(int id, String rawSql, int label){ //Constructor
            this.id = id; 
            this.rawSql = rawSql;
            this.label = label;
        }
    }
}