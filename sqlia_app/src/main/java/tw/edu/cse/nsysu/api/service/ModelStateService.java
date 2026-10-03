package tw.edu.cse.nsysu.api.service;

import tw.edu.cse.nsysu.FeatureExtract;
import tw.edu.cse.nsysu.FeatureForML;
import tw.edu.cse.nsysu.ArmNormalProfile;
import tw.edu.cse.nsysu.api.dto.AccuracyPoint;
import tw.edu.cse.nsysu.OnlineBagging;
import tw.edu.cse.nsysu.FPGrowth;
import weka.classifiers.bayes.NaiveBayesUpdateable;
import weka.core.Instances;
import weka.core.Attribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;

public class ModelStateService{
    private final OnlineBagging fullFlowBagging;
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
    private static final long WARMUP_SAMPLE_SEED = 20261003L;
    private static final double RULE_MIN_SUPPORT = 0.05;
    private static final double RULE_MIN_CONFIDENCE = 0.6;
    private static final double DECISION_THRESHOLD = 0.5;

    //Constructor to initialize the model state service with a database connection, warmup size, and ensemble size
    public ModelStateService(Connection conn, int warmupSize, int emsembleSize) throws Exception{
        this.fullFlowBagging = new OnlineBagging(new NaiveBayesUpdateable(), emsembleSize, 42L);
        this.ratioBagging = new OnlineBagging(new NaiveBayesUpdateable(), emsembleSize, 84L);
        featureEngine.setRules(mineTrainingRules(conn));
        warmup(conn, warmupSize);
    }
    //Use a reproducible shuffled sample instead of always taking the first batch of training rows.
    private void warmup(Connection conn, int warmupSize) throws Exception{
        String sql = "SELECT raw_sql, label FROM training_data "
            + "WHERE split_group='train' ORDER BY batch_seq, id";
        List<WarmupRow> rows = new ArrayList<>();
        try(PreparedStatement pstmt = conn.prepareStatement(sql);
            ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                rows.add(new WarmupRow(rs.getString("raw_sql"), rs.getInt("label")));
            }
        }
        Collections.shuffle(rows, new java.util.Random(WARMUP_SAMPLE_SEED));
        if (warmupSize < 0) {
            throw new IllegalArgumentException("Warmup size must not be negative.");
        }
        if (rows.size() > warmupSize) {
            rows = new ArrayList<>(rows.subList(0, warmupSize));
        }
        java.util.Set<Integer> seenLabels = new java.util.HashSet<>();
        for (WarmupRow row : rows) {
            seenLabels.add(row.label);
            warmupLabelCounts.merge(row.label, 1, Integer::sum);
            if (row.label == 0) {
                normalProfile.update(FeatureExtract.extract(row.rawSql));
            }
        }
        if (!rows.isEmpty() && seenLabels.size() < 2) {
            System.err.println("[ModelStateService] WARNING: warmup data only contains label(s) "
                + seenLabels + " - model will be biased toward this class.");
        }
        if (!rows.isEmpty()) {
            fullFlowBagging.initialize(createHeader(toFeature(rows.get(0).rawSql).length));
            ratioBagging.initialize(createHeader(FeatureExtract.extract(rows.get(0).rawSql).length));
            for (WarmupRow row : rows) {
                fullFlowBagging.update(toFeature(row.rawSql), row.label);
                ratioBagging.update(toBaseFeature(row.rawSql), row.label);
                totalTrained++;
            }
        }
        warmedUp = totalTrained > 0;
        System.out.println("[ModelStateService] warmup complete, trained = " + totalTrained
            + ", FP-Growth rules = " + featureEngine.ruleCount());
    }

    //A method to convert raw SQL to feature vector
    public float[] toFeature(String rawSql) {
        double[] base = FeatureExtract.extract(rawSql);
        float[] arm = normalProfile.deriveFeatures(base);
        float[] baseAndRules = featureEngine.getFeatureFromBase(base, 4);
        int ruleCount = baseAndRules.length - base.length;
        float[] fused = new float[base.length + arm.length + ruleCount];
        for (int i = 0; i < base.length; i++) {
            fused[i] = (float) base[i];
        }
        System.arraycopy(arm, 0, fused, base.length, arm.length);
        System.arraycopy(baseAndRules, base.length, fused, base.length + arm.length, ruleCount);
        return fused;
    }
    //Predict the label for the given feature vector using the bagging model
    public synchronized int predict(float[] x) throws Exception{ 
        return fullFlowBagging.predictProb(x)[1] >= DECISION_THRESHOLD ? 1 : 0;
    }
    //Predict the probability distribution for the given feature vector using the bagging model
    public synchronized double[] predictProb(float[] x) throws Exception{
        return fullFlowBagging.predictProb(x);
    }

    // Select the model path used by the architecture performance benchmark.
    public synchronized int predictArchitecture(String rawSql, String architecture) throws Exception {
        double[] base = FeatureExtract.extract(rawSql);
        if ("ARM_PROFILE".equals(architecture)) {
            return normalProfile.deriveFeatures(base)[2] >= DECISION_THRESHOLD ? 1 : 0;
        }
        if ("RATIO_INCREMENTAL_ML".equals(architecture)) {
            return ratioBagging.predictProb(toBaseFeature(rawSql))[1] >= DECISION_THRESHOLD ? 1 : 0;
        }
        return fullFlowBagging.predictProb(toFeature(rawSql))[1] >= DECISION_THRESHOLD ? 1 : 0;
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
        return fullFlowBagging.predictProb(toFeature(rawSql));
    }
    //Update the bagging model with the given feature vector and label
    public synchronized void update(float[] x, int label) throws Exception{
        fullFlowBagging.update(x, label);
        totalTrained++;
    }

    public synchronized void updateArchitecture(String rawSql, int label, String architecture)
            throws Exception {
        double[] base = FeatureExtract.extract(rawSql);
        if ("ARM_PROFILE".equals(architecture)) {
            if (label == 0) normalProfile.update(base);
            return;
        }
        if ("RATIO_INCREMENTAL_ML".equals(architecture)) {
            ratioBagging.update(toBaseFeature(rawSql), label);
            totalTrained++;
            return;
        }
        fullFlowBagging.update(toFeature(rawSql), label);
        totalTrained++;
        if (label == 0) normalProfile.update(base);
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

    private List<FeatureForML.Rule> mineTrainingRules(Connection conn) throws Exception {
        File transactions = File.createTempFile("sqlia_train_transactions_", ".txt");
        try {
            String sql = "SELECT raw_sql, label FROM training_data WHERE split_group='train' ORDER BY id";
            try (PreparedStatement statement = conn.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery();
                 BufferedWriter writer = new BufferedWriter(new FileWriter(transactions))) {
                while (rows.next()) {
                    java.util.Set<Integer> items = FeatureForML.toTransactionSet(
                        FeatureExtract.extract(rows.getString("raw_sql")));
                    int label = rows.getInt("label");
                    items.add(label == 1
                        ? FeatureForML.SQLI_LABEL_ITEM
                        : FeatureForML.BENIGN_LABEL_ITEM);
                    writer.write(items.stream().sorted().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(" ")));
                    writer.newLine();
                }
            }
            return new FPGrowth().getAssociationRules(
                    transactions.getAbsolutePath(), RULE_MIN_SUPPORT, RULE_MIN_CONFIDENCE)
                .stream()
                .filter(rule -> rule.getConsequent() == FeatureForML.SQLI_LABEL_ITEM)
                .map(rule -> new FeatureForML.Rule(
                    rule.getAntecedent(), rule.getConsequent(),
                    rule.getConfidence(), true))
                .toList();
        } finally {
            if (transactions.exists()) transactions.delete();
        }
    }

    private static final class WarmupRow {
        final String rawSql;
        final int label;
        WarmupRow(String rawSql, int label) {
            this.rawSql = rawSql;
            this.label = label;
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
    public int getRuleFeatureCount() {
        return featureEngine.ruleCount();
    }
    //Exposes warmup label distribution so callers/ops can detect a single-class (biased) warmup
    public java.util.Map<Integer, Integer> getWarmupLabelCounts(){
        return java.util.Collections.unmodifiableMap(warmupLabelCounts);
    }

    public Map<String, Double> getArchitectureThresholds() {
        return Map.of(
                "ARM_PROFILE", DECISION_THRESHOLD,
                "RATIO_INCREMENTAL_ML", DECISION_THRESHOLD,
                "FULL_FLOW", DECISION_THRESHOLD);
    }

}