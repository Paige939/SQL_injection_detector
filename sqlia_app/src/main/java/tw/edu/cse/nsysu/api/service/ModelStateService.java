package tw.edu.cse.nsysu.api.service;

import tw.edu.cse.nsysu.FeatureExtract;
import tw.edu.cse.nsysu.FeatureForML;
import tw.edu.cse.nsysu.ArmNormalProfile;
import tw.edu.cse.nsysu.api.dto.AccuracyPoint;
import tw.edu.cse.nsysu.OnlineBagging;
import weka.classifiers.bayes.NaiveBayesUpdateable;
import weka.core.Instances;
import weka.core.Attribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.sql.*;
import java.time.Instant;
import java.util.List;

public class ModelStateService{
    private final OnlineBagging bagging;
    private final OnlineBagging ratioBagging;
    private final FeatureForML featureEngine = new FeatureForML();
    private final ArmNormalProfile normalProfile = new ArmNormalProfile();
    private volatile int totalTrained = 0; //total number of training samples used to update the model
    private volatile boolean warmedUp = false; //indicates whether the model has been warmed up with initial training data
    private final java.util.Map<Integer, Integer> warmupLabelCounts = new java.util.concurrent.ConcurrentHashMap<>(); //stores the count of each label in the warmup data
    private static final int METRICS_INTERVAL = 10; //interval for calculating accuracy metrics
    private static final int MAX_ACCURACY_POINTS = 200; //maximum number of accuracy points to store in the accuracy history
    private final List<AccuracyPoint> accuracyHistory = new ArrayList<>(); //stores the history of accuracy points for the model
    private int totalEvaluated = 0; //total number of samples evaluated for accuracy
    private int totalCorrect = 0; //total number of correct predictions made by the model
    //Constructor to initialize the model state service with a database connection, warmup size, and ensemble size
    public ModelStateService(Connection conn, int warmupSize, int emsembleSize) throws Exception{
        this.bagging = new OnlineBagging(new NaiveBayesUpdateable(), emsembleSize, 42L);
        this.ratioBagging = new OnlineBagging(new NaiveBayesUpdateable(), emsembleSize, 84L);
        warmup(conn, warmupSize);
    }
    //A method to warm up the model with initial training data
    //Uses ORDER BY RANDOM() instead of ORDER BY id, otherwise the CSV rows (which are
    //grouped by label, e.g. all label=1 first) would make the warmup set single-class,
    //causing NaiveBayes to always predict one class with saturated (100%) confidence.
    private void warmup(Connection conn, int warmupSize) throws Exception{
        String sql = "SELECT raw_sql, label FROM training_data "
            + "WHERE split_group='train' ORDER BY batch_seq, id LIMIT ?";
        //Fetch the training data from the database using a prepared statement to prevent SQL injection
        try(PreparedStatement pstmt = conn.prepareStatement(sql)){
            pstmt.setInt(1, warmupSize);
            //Execute the query and process the result set
            try(ResultSet rs = pstmt.executeQuery()){
                Instances header = null;
                java.util.Set<Integer> seenLabels = new java.util.HashSet<>();
                while(rs.next()){
                    //Fetch raw SQL and label from the result set, convert to feature vector, and update the bagging model
                    String rawSql = rs.getString("raw_sql");
                    int label = rs.getInt("label");
                    seenLabels.add(label);
                    warmupLabelCounts.merge(label, 1, Integer::sum);
                    float[] x = toFeature(rawSql);
                    if(header == null){
                        header = createHeader(x.length);
                        bagging.initialize(header);
                        ratioBagging.initialize(createHeader(FeatureExtract.extract(rawSql).length));
                    }
                    bagging.update(x, label);
                    ratioBagging.update(toBaseFeature(rawSql), label);
                    if (label == 0) {
                        normalProfile.update(FeatureExtract.extract(rawSql));
                    }
                    totalTrained++;
                }
                //Warn if warmup data is single-class, which would saturate NaiveBayes confidence to 100%
                if(totalTrained > 0 && seenLabels.size() < 2){
                    System.err.println("[ModelStateService] WARNING: warmup data only contains label(s) " + seenLabels
                        + " - model will be biased toward this class. Check training_data ordering/sampling.");
                }
            }
        }
        warmedUp = totalTrained > 0;
        System.out.println("[ModelStateService] warmup complete, trained = " + totalTrained);
    }
    //A method to convert raw SQL to feature vector
    public float[] toFeature(String rawSql) {
        double[] base = FeatureExtract.extract(rawSql);
        float[] arm = normalProfile.deriveFeatures(base);
        float[] fused = new float[base.length + arm.length];
        for (int i = 0; i < base.length; i++) {
            fused[i] = (float) base[i];
        }
        System.arraycopy(arm, 0, fused, base.length, arm.length);
        return fused;
    }
    //Predict the label for the given feature vector using the bagging model
    public synchronized int predict(float[] x) throws Exception{ 
        return bagging.predictLabel(x);
    }
    //Predict the probability distribution for the given feature vector using the bagging model
    public synchronized double[] predictProb(float[] x) throws Exception{
        return bagging.predictProb(x);
    }

    // Select the model path used by the architecture performance benchmark.
    public synchronized int predictArchitecture(String rawSql, String architecture) throws Exception {
        double[] base = FeatureExtract.extract(rawSql);
        if ("ARM_PROFILE".equals(architecture)) {
            return normalProfile.deriveFeatures(base)[2] >= 0.5f ? 1 : 0;
        }
        if ("RATIO_INCREMENTAL_ML".equals(architecture)) {
            return ratioBagging.predictLabel(toBaseFeature(rawSql));
        }
        return bagging.predictLabel(toFeature(rawSql));
    }

    // Return the class probability used as confidence for one architecture.
    public synchronized double[] predictArchitectureProb(String rawSql, String architecture)
            throws Exception {
        double[] base = FeatureExtract.extract(rawSql);
        if ("ARM_PROFILE".equals(architecture)) {
            float anomaly = normalProfile.deriveFeatures(base)[2];
            return new double[] {1.0 - anomaly, anomaly};
        }
        if ("RATIO_INCREMENTAL_ML".equals(architecture)) {
            return ratioBagging.predictProb(toBaseFeature(rawSql));
        }
        return bagging.predictProb(toFeature(rawSql));
    }
    //Update the bagging model with the given feature vector and label
    public synchronized void update(float[] x, int label) throws Exception{
        bagging.update(x, label);
        totalTrained++;
    }

    private float[] toBaseFeature(String rawSql) {
        double[] base = FeatureExtract.extract(rawSql);
        float[] result = new float[base.length];
        for (int i = 0; i < base.length; i++) result[i] = (float) base[i];
        return result;
    }
    // Update ML for every verified label, then update the ARM profile only for verified benign data.
    public synchronized void applyVerifiedFeedback(String rawSql, int verifiedLabel,
                                                    boolean verifiedBenign) throws Exception {
        if (rawSql == null || rawSql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be empty.");
        }
        if (verifiedLabel != 0 && verifiedLabel != 1) {
            throw new IllegalArgumentException("Verified label must be 0 or 1.");
        }
        float[] x = toFeature(rawSql);
        update(x, verifiedLabel);
        ratioBagging.update(toBaseFeature(rawSql), verifiedLabel);
        if (verifiedBenign && verifiedLabel == 0) {
            normalProfile.update(FeatureExtract.extract(rawSql));
        }
    }
    //Record the evaluation of a prediction by comparing the predicted label with the true label, updating accuracy metrics, and maintaining a history of accuracy points
    public synchronized void recordEvaluation(int predictedLabel, int trueLabel){
        totalEvaluated++;
        if(predictedLabel == trueLabel){
            totalCorrect++;
        }
        //Update accuracy metrics at specified intervals and maintain a history of accuracy points, ensuring the history does not exceed the maximum allowed size
        if(totalEvaluated % METRICS_INTERVAL != 0){
            return;
        }
        double accuracy = (double) totalCorrect / totalEvaluated;
        //Maintain a history of accuracy points, ensuring the history does not exceed the maximum allowed size
        if(accuracyHistory.size() >= MAX_ACCURACY_POINTS){
            accuracyHistory.remove(0);
        }
        accuracyHistory.add(new AccuracyPoint(accuracyHistory.size() + 1, accuracy, totalEvaluated, Instant.now().toString()));
    }
    //Get a copy of the accuracy history as an unmodifiable list to prevent external modification
    public synchronized List<AccuracyPoint> getAccuracyHistory(){
        return List.copyOf(accuracyHistory);
    }
    //Create a header for the Weka Instances object with the specified dimension
    private Instances createHeader(int dim){
        ArrayList<Attribute> attrs = new ArrayList<>();
        for(int i = 0 ; i < dim ; i++){
            attrs.add(new Attribute("f_" + i));
        }
        attrs.add(new Attribute("target_label", Arrays.asList("0", "1")));
        Instances header = new Instances("api_model", attrs, 0);
        header.setClassIndex(dim);
        return header;
    }
    public boolean isWarmedUp(){ 
        return warmedUp;   
    }
    public int getTotalTrained(){ 
        return totalTrained; 
    }
    public int getNormalProfileSize() {
        return normalProfile.size();
    }
    //Exposes warmup label distribution so callers/ops can detect a single-class (biased) warmup
    public java.util.Map<Integer, Integer> getWarmupLabelCounts(){
        return java.util.Collections.unmodifiableMap(warmupLabelCounts);
    }
}