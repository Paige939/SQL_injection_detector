package tw.edu.cse.nsysu.api.service;

import tw.edu.cse.nsysu.FeatureExtract;
import tw.edu.cse.nsysu.FeatureForML;
import tw.edu.cse.nsysu.ArmNormalProfile;
import tw.edu.cse.nsysu.api.dto.AccuracyPoint;
import tw.edu.cse.nsysu.OnlineBagging;
import tw.edu.cse.nsysu.FPGrowth;
import tw.edu.cse.nsysu.ExtendedRuleMiner;
import tw.edu.cse.nsysu.ExtendedRuleMiner.ExtendedRule;
import tw.edu.cse.nsysu.ExtendedRuleMiner.ItemMode;
import weka.classifiers.bayes.NaiveBayesUpdateable;
import weka.core.Instances;
import weka.core.Attribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.EnumMap;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModelStateService{
    private Connection connection;
    private boolean readOnly;
    private String modelMode = "interactive_training";
    private String checkpointHash;
    private Map<String, Double> decisionThresholds;
    private final OnlineBagging fullFlowBagging;
    private final OnlineBagging ratioBagging;
    private final OnlineBagging extendedBagging;
    private final Map<IncrementalAlgorithm, IncrementalClassifier> supplementalModels =
        new EnumMap<>(IncrementalAlgorithm.class);
    private volatile List<ExtendedRule> extendedRules = List.of();
    private final FeatureForML featureEngine;
    private final ArmNormalProfile normalProfile;
    private volatile int totalTrained = 0; //total number of training samples used to update the model
    private volatile int currentModelTrained = 0;
    private volatile boolean warmedUp = false; //indicates whether the model has been warmed up with initial training data
    private final java.util.Map<Integer, Integer> warmupLabelCounts = new java.util.concurrent.ConcurrentHashMap<>(); //stores the count of each label in the warmup data
    private static final int METRICS_INTERVAL = 10; //interval for calculating accuracy metrics
    private static final int MAX_ACCURACY_POINTS = 200; //maximum number of accuracy points to store in the accuracy history
    private final List<AccuracyPoint> accuracyHistory = new ArrayList<>(); //stores the history of accuracy points for the model
    private int totalEvaluated = 0; //total number of samples evaluated for accuracy
    private int totalCorrect = 0; //total number of correct predictions made by the model
    private final Map<IncrementalAlgorithm, ModelEvaluation> modelEvaluations =
        new EnumMap<>(IncrementalAlgorithm.class);
    private static final long WARMUP_SAMPLE_SEED = 20261003L;
    private static final double RULE_MIN_SUPPORT = 0.05;
    private static final double RULE_MIN_CONFIDENCE = 0.6;
    private static final double DECISION_THRESHOLD = 0.5;
    // Prespecified defaults; ablation tuning is a separate classifier experiment.
    private static final double EXTENDED_MIN_SUPPORT = 0.1;
    private static final double EXTENDED_MIN_CONFIDENCE = 0.6;
    private static final double EXTENDED_MIN_LIFT = 1.5;
    private static final int EXTENDED_MAX_LENGTH = 3;
    private static final int EXTENDED_MAX_RULES = 25;
    private static final double PURE_EXTENDED_ARM_THRESHOLD = EXTENDED_MIN_CONFIDENCE;
    // A benign verdict is escalated to review when a rule this reliable still matches.
    private static final double REVIEW_RULE_CONFIDENCE = 0.95;
    private final TrainingSettings trainingSettings;

    public record TrainingSettings(
            long trainingOrderSeed,
            int ensembleSize,
            long fullFlowSeed,
            long ratioSeed,
            long extendedSeed,
            double standardMinSupport,
            double standardMinConfidence,
            double extendedMinSupport,
            double extendedMinConfidence,
            double extendedMinLift,
            int extendedMaxLength,
            int extendedMaxRulesPerFamily,
            Path miningWorkDirectory) {
        public TrainingSettings {
            if (ensembleSize < 1 || extendedMaxLength < 1 || extendedMaxRulesPerFamily < 1) {
                throw new IllegalArgumentException("Ensemble size and extended-rule limits must be positive.");
            }
            requireUnitInterval("standardMinSupport", standardMinSupport);
            requireUnitInterval("standardMinConfidence", standardMinConfidence);
            requireUnitInterval("extendedMinSupport", extendedMinSupport);
            requireUnitInterval("extendedMinConfidence", extendedMinConfidence);
            if (!Double.isFinite(extendedMinLift) || extendedMinLift < 0) {
                throw new IllegalArgumentException("Extended minimum lift must be finite and nonnegative.");
            }
            if (miningWorkDirectory == null) {
                throw new IllegalArgumentException("Mining workspace must be specified.");
            }
            miningWorkDirectory = miningWorkDirectory.toAbsolutePath().normalize();
        }

        private static void requireUnitInterval(String name, double value) {
            if (!Double.isFinite(value) || value <= 0 || value > 1) {
                throw new IllegalArgumentException(name + " must be within (0, 1].");
            }
        }

        public static TrainingSettings defaults() {
            return new TrainingSettings(WARMUP_SAMPLE_SEED, 15, 42L, 84L, 126L,
                    RULE_MIN_SUPPORT, RULE_MIN_CONFIDENCE, EXTENDED_MIN_SUPPORT,
                    EXTENDED_MIN_CONFIDENCE, EXTENDED_MIN_LIFT, EXTENDED_MAX_LENGTH,
                    EXTENDED_MAX_RULES, Path.of("data/experiments/.work"));
        }

        public TrainingSettings withEnsembleSize(int size) {
            return new TrainingSettings(trainingOrderSeed, size, fullFlowSeed, ratioSeed,
                    extendedSeed, standardMinSupport, standardMinConfidence, extendedMinSupport,
                    extendedMinConfidence, extendedMinLift, extendedMaxLength,
                    extendedMaxRulesPerFamily, miningWorkDirectory);
        }

        public Map<String, Object> asMap() {
            return Map.of(
                    "trainingOrderSeed", trainingOrderSeed,
                    "ensembleSeeds", List.of(fullFlowSeed, ratioSeed, extendedSeed),
                    "ensembleSize", ensembleSize,
                    "standardRules", Map.of("minSupport", standardMinSupport,
                            "minConfidence", standardMinConfidence),
                    "extendedRules", Map.of("minSupport", extendedMinSupport,
                            "minConfidence", extendedMinConfidence, "minLift", extendedMinLift,
                            "maxLength", extendedMaxLength, "maxRulesPerFamily", extendedMaxRulesPerFamily));
        }
    }

    static Map<String, Object> frozenTrainingSettings() {
        return TrainingSettings.defaults().asMap();
    }

    //Constructor to initialize the model state service with a database connection, warmup size, and ensemble size
    public ModelStateService(Connection conn, int warmupSize, int emsembleSize) throws Exception{
        this(conn, warmupSize, TrainingSettings.defaults().withEnsembleSize(emsembleSize));
    }

    public ModelStateService(Connection conn, int warmupSize, TrainingSettings settings) throws Exception {
        this(conn, conn, warmupSize, settings, false);
    }

    // Isolated diagnostics may mine rules on A and fit classifiers/profile on B.
    ModelStateService(Connection classifierRows, Connection ruleRows, TrainingSettings settings)
            throws Exception {
        this(classifierRows, ruleRows, Integer.MAX_VALUE, settings, true);
    }

    ModelStateService(Connection classifierRows, Connection ruleRows, TrainingSettings settings,
            ExtendedRulePolicy rulePolicy) throws Exception {
        this(classifierRows, ruleRows, Integer.MAX_VALUE, settings, true, rulePolicy);
    }

    private ModelStateService(Connection conn, Connection ruleRows, int warmupSize,
            TrainingSettings settings, boolean diagnostic) throws Exception {
        this(conn, ruleRows, warmupSize, settings, diagnostic, ExtendedRulePolicy.BASELINE);
    }

    private ModelStateService(Connection conn, Connection ruleRows, int warmupSize,
            TrainingSettings settings, boolean diagnostic, ExtendedRulePolicy rulePolicy) throws Exception {
        this.trainingSettings = java.util.Objects.requireNonNull(settings, "settings");
        this.connection = conn;
        this.featureEngine = new FeatureForML();
        this.normalProfile = new ArmNormalProfile();
        this.fullFlowBagging = new OnlineBagging(new NaiveBayesUpdateable(),
                settings.ensembleSize(), settings.fullFlowSeed());
        this.ratioBagging = new OnlineBagging(new NaiveBayesUpdateable(),
                settings.ensembleSize(), settings.ratioSeed());
        this.extendedBagging = new OnlineBagging(new NaiveBayesUpdateable(),
                settings.ensembleSize(), settings.extendedSeed());
        if (!diagnostic) {
            supplementalModels.put(IncrementalAlgorithm.SGD, new SgdIncrementalClassifier());
            supplementalModels.put(
                IncrementalAlgorithm.HOEFFDING_TREE,
                new HoeffdingTreeIncrementalClassifier());
        }
        System.out.println("[training] Mining standard training rules.");
        featureEngine.setRules(mineTrainingRules(ruleRows));
        System.out.println("[training] Mining extended training rules.");
        extendedRules = java.util.Objects.requireNonNull(rulePolicy, "rulePolicy")
                .filter(mineExtendedRules(ruleRows));
        System.out.println("[training] Fitting profile and classifiers.");
        warmup(conn, warmupSize);
    }

    public synchronized void calibrateWebsitePureArmThreshold() throws Exception {
        requireMutable();
        if (connection == null) {
            throw new IllegalStateException("Website calibration requires the dataset connection.");
        }
        java.util.Set<String> trainingGroups = new java.util.HashSet<>();
        try (var query = connection.prepareStatement(
                "SELECT raw_sql FROM DataTable WHERE split_group='train'");
             var rows = query.executeQuery()) {
            while (rows.next()) {
                trainingGroups.add(tw.edu.cse.nsysu.preprocess.PayloadNormalizer.groupKey(
                        rows.getString(1)));
            }
        }
        List<ValidationThreshold.Scored> observations = new ArrayList<>();
        try (var query = connection.prepareStatement(
                "SELECT raw_sql,label FROM DataTable WHERE split_group='validation'");
             var rows = query.executeQuery()) {
            while (rows.next()) {
                String raw = rows.getString(1);
                Object label = rows.getObject(2);
                if (raw == null || raw.isBlank() || !(label instanceof Number number)
                        || (number.doubleValue() != 0 && number.doubleValue() != 1)) {
                    throw new IllegalArgumentException("Website Validation rows require nonblank inputs and binary labels.");
                }
                if (trainingGroups.contains(
                        tw.edu.cse.nsysu.preprocess.PayloadNormalizer.groupKey(raw))) {
                    throw new IllegalArgumentException("Website Validation overlaps a Training payload group.");
                }
                observations.add(new ValidationThreshold.Scored(
                        pureExtendedArmConfidence(raw), number.intValue()));
            }
        }
        ValidationThreshold.Choice choice = ValidationThreshold.select(observations);
        Map<String, Double> thresholds = new java.util.HashMap<>(getArchitectureThresholds());
        thresholds.put("PURE_EXTENDED_ARM", choice.threshold());
        decisionThresholds = Map.copyOf(thresholds);
        System.out.println("[website] Pure ARM Validation calibration: rows=" + observations.size()
                + ", threshold=" + choice.threshold() + ", F1=" + choice.f1()
                + ", FPR=" + choice.fpr() + "; Test not read.");
    }

    record FrozenModel(OnlineBagging fullFlow, OnlineBagging ratio, OnlineBagging extended,
            FeatureForML features, ArmNormalProfile profile, List<ExtendedRule> rules,
            int trainingSamples) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }

    private ModelStateService(FrozenModel model) {
        trainingSettings = TrainingSettings.defaults();
        connection = null;
        fullFlowBagging = model.fullFlow();
        ratioBagging = model.ratio();
        extendedBagging = model.extended();
        featureEngine = model.features();
        normalProfile = model.profile();
        extendedRules = model.rules();
        totalTrained = model.trainingSamples();
        currentModelTrained = totalTrained;
        warmedUp = totalTrained > 0;
    }

    synchronized void saveFrozen(java.nio.file.Path path) throws java.io.IOException {
        try (var output = new java.io.ObjectOutputStream(java.nio.file.Files.newOutputStream(path,
                java.nio.file.StandardOpenOption.CREATE_NEW))) {
            output.writeObject(new FrozenModel(fullFlowBagging, ratioBagging, extendedBagging,
                    featureEngine, normalProfile, extendedRules, totalTrained));
        }
    }

    // Only load a locally generated, trusted checkpoint; Java deserialization is not an upload API.
    static ModelStateService loadFrozen(java.nio.file.Path path) throws Exception {
        return loadFrozen(java.nio.file.Files.readAllBytes(path));
    }

    static ModelStateService loadFrozen(byte[] bytes) throws Exception {
        try (var input = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
            Object value = input.readObject();
            if (!(value instanceof FrozenModel model)) {
                throw new java.io.IOException("Not a frozen SQLi model checkpoint.");
            }
            return new ModelStateService(model);
        }
    }
    void configureDeployment(Map<String, Double> thresholds, String hash, boolean frozen) {
        if (!thresholds.keySet().equals(java.util.Set.copyOf(FrozenArchitectureComparisonService.ARCHITECTURES))) {
            throw new IllegalArgumentException("Deployment requires all five architecture thresholds.");
        }
        decisionThresholds = Map.copyOf(thresholds);
        checkpointHash = hash;
        readOnly = frozen;
        modelMode = frozen ? "frozen_deployed_checkpoint" : "interactive_checkpoint_copy";
    }

    void initializeInteractive(Connection conn) throws Exception {
        requireMutable();
        connection = conn;
        supplementalModels.put(IncrementalAlgorithm.SGD, new SgdIncrementalClassifier());
        supplementalModels.put(IncrementalAlgorithm.HOEFFDING_TREE, new HoeffdingTreeIncrementalClassifier());
        List<WarmupRow> rows = new ArrayList<>();
        try (var query = conn.createStatement();
                var result = query.executeQuery("SELECT raw_sql,label FROM DataTable WHERE split_group='train' ORDER BY raw_sql")) {
            while (result.next()) rows.add(new WarmupRow(result.getString(1), result.getInt(2)));
        }
        Collections.shuffle(rows, new java.util.Random(trainingSettings.trainingOrderSeed()));
        for (var model : supplementalModels.values()) model.initialize(toFeature(rows.getFirst().rawSql).length);
        for (WarmupRow row : rows) {
            warmupLabelCounts.merge(row.label, 1, Integer::sum);
            float[] features = toFeature(row.rawSql, row.label == 0);
            for (var model : supplementalModels.values()) model.update(features, row.label);
        }
    }

    private void requireMutable() {
        if (readOnly) throw new IllegalStateException("The deployed frozen model cannot be updated.");
    }

    public String getModelMode() { return modelMode; }
    public String getCheckpointHash() { return checkpointHash; }

    public double incrementalThreshold(String modelId) {
        return IncrementalAlgorithm.fromId(modelId) == IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB
                ? getArchitectureThresholds().get("FULL_FLOW") : DECISION_THRESHOLD;
    }

    //Use a reproducible shuffled sample instead of always taking the first batch of training rows.
    private void warmup(Connection conn, int warmupSize) throws Exception{
        String sql = "SELECT raw_sql, label FROM DataTable "
            + "WHERE split_group='train' ORDER BY batch_seq, id";
        List<WarmupRow> rows = new ArrayList<>();
        java.util.Set<LabeledPayload> seenPayloads = new java.util.HashSet<>();
        try(PreparedStatement pstmt = conn.prepareStatement(sql);
            ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                String raw = rs.getString("raw_sql");
                int label = rs.getInt("label");
                if (seenPayloads.add(new LabeledPayload(raw, label))) {
                    rows.add(new WarmupRow(raw, label));
                }
            }
        }
        Collections.shuffle(rows, new java.util.Random(trainingSettings.trainingOrderSeed()));
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
            int featureCount = toFeature(rows.get(0).rawSql).length;
            fullFlowBagging.initialize(createHeader(featureCount));
            ratioBagging.initialize(createHeader(FeatureExtract.extract(rows.get(0).rawSql).length));
            extendedBagging.initialize(createHeader(toExtendedFeature(rows.get(0).rawSql).length));
            for (IncrementalClassifier model : supplementalModels.values()) {
                model.initialize(featureCount);
            }
            for (WarmupRow row : rows) {
                float[] features = toFeature(row.rawSql, row.label == 0);
                fullFlowBagging.update(features, row.label);
                ratioBagging.update(toBaseFeature(row.rawSql), row.label);
                extendedBagging.update(toExtendedFeature(row.rawSql), row.label);
                for (IncrementalClassifier model : supplementalModels.values()) {
                    model.update(features, row.label);
                }
                totalTrained++;
                currentModelTrained++;
                if (totalTrained % 1000 == 0) {
                    System.out.println("[training] Fitted " + totalTrained + "/" + rows.size());
                }
            }
        }
        warmedUp = totalTrained > 0;
        System.out.println("[ModelStateService] warmup complete, trained = " + totalTrained
            + ", FP-Growth rules = " + featureEngine.ruleCount()
            + ", extended rules = " + extendedRules.size());
    }

    //A method to convert raw SQL to feature vector
    public float[] toFeature(String rawSql) {
        return toFeature(rawSql, false);
    }

    // For warmup rows that are already inside the normal profile, use leave-one-out profile features.
    private float[] toFeature(String rawSql, boolean leaveOneOut) {
        double[] base = FeatureExtract.extract(rawSql);
        float[] arm = leaveOneOut
            ? normalProfile.deriveFeaturesLeaveOneOut(base)
            : normalProfile.deriveFeatures(base);
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
        return fullFlowBagging.predictProb(x)[1] >= getArchitectureThresholds().get("FULL_FLOW") ? 1 : 0;
    }
    //Predict the probability distribution for the given feature vector using the bagging model
    public synchronized double[] predictProb(float[] x) throws Exception{
        return fullFlowBagging.predictProb(x);
    }

    public synchronized double[] predictIncrementalProb(
            String rawSql,
            String modelId
    ) throws Exception {
        IncrementalAlgorithm algorithm = IncrementalAlgorithm.fromId(modelId);
        float[] features = toFeature(rawSql);
        if (algorithm == IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB) {
            return fullFlowBagging.predictProb(features);
        }
        IncrementalClassifier model = supplementalModels.get(algorithm);
        if (model == null || model.getTotalTrained() == 0) {
            throw new IllegalStateException("Incremental model is not initialized: " + algorithm.getId());
        }
        return model.predictProb(features);
    }

    public synchronized int predictIncremental(String rawSql, String modelId) throws Exception {
        double[] probabilities = predictIncrementalProb(rawSql, modelId);
        return probabilities[1] >= incrementalThreshold(modelId) ? 1 : 0;
    }

    public synchronized void applyIncrementalFeedback(
            String rawSql,
            int verifiedLabel,
            boolean verifiedBenign,
            String modelId
    ) throws Exception {
        requireMutable();
        IncrementalAlgorithm algorithm = IncrementalAlgorithm.fromId(modelId);
        if (algorithm == IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB) {
            applyVerifiedFeedback(rawSql, verifiedLabel, verifiedBenign);
            return;
        }
        if (rawSql == null || rawSql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be empty.");
        }
        if (verifiedLabel != 0 && verifiedLabel != 1) {
            throw new IllegalArgumentException("Verified label must be 0 or 1.");
        }
        IncrementalClassifier model = supplementalModels.get(algorithm);
        if (model == null || model.getTotalTrained() == 0) {
            throw new IllegalStateException("Incremental model is not initialized: " + algorithm.getId());
        }
        model.update(toFeature(rawSql), verifiedLabel);
    }

    public synchronized List<Map<String, Object>> getIncrementalModels() {
        List<Map<String, Object>> models = new ArrayList<>();
        for (IncrementalAlgorithm algorithm : IncrementalAlgorithm.available()) {
            int trained = algorithm == IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB
                ? currentModelTrained
                : supplementalModels.containsKey(algorithm) ? supplementalModels.get(algorithm).getTotalTrained() : 0;
            models.add(Map.of(
                "id", algorithm.getId(),
                "displayName", algorithm.getDisplayName(),
                "totalTrained", trained,
                "isWarmUp", trained > 0));
        }
        return List.copyOf(models);
    }

    // Select the model path used by the architecture performance benchmark.
    public synchronized int predictArchitecture(String rawSql, String architecture) throws Exception {
        String selected = architecture == null ? "FULL_FLOW" : architecture;
        Double threshold = getArchitectureThresholds().get(selected);
        if (threshold == null) throw new IllegalArgumentException("Unknown architecture: " + selected);
        return predictArchitectureProb(rawSql, selected)[1] >= threshold ? 1 : 0;
    }

    // Return the class probability used as confidence for one architecture.
    public synchronized double[] predictArchitectureProb(String rawSql, String architecture)
            throws Exception {
        if (architecture != null && !FrozenArchitectureComparisonService.ARCHITECTURES.contains(architecture)) {
            throw new IllegalArgumentException("Unknown architecture: " + architecture);
        }
        double[] base = FeatureExtract.extract(rawSql);
        if ("ARM_PROFILE".equals(architecture)) {
            float anomaly = normalProfile.deriveFeatures(base)[2];
            return new double[] {1.0 - anomaly, anomaly};
        }
        if ("RATIO_INCREMENTAL_ML".equals(architecture)) {
            return ratioBagging.predictProb(toBaseFeature(rawSql));
        }
        if ("EXTENDED_ARM".equals(architecture)) {
            return extendedBagging.predictProb(toExtendedFeature(rawSql));
        }
        if ("PURE_EXTENDED_ARM".equals(architecture)) {
            double confidence = pureExtendedArmConfidence(rawSql);
            return new double[] {1.0 - confidence, confidence};
        }
        return fullFlowBagging.predictProb(toFeature(rawSql));
    }
    //Update the bagging model with the given feature vector and label
    public synchronized void update(float[] x, int label) throws Exception{
        requireMutable();
        fullFlowBagging.update(x, label);
        totalTrained++;
        currentModelTrained++;
    }

    public synchronized void updateArchitecture(String rawSql, int label, String architecture)
            throws Exception {
        requireMutable();
        if (label != 0 && label != 1) throw new IllegalArgumentException("Label must be 0 or 1.");
        if (architecture != null && !FrozenArchitectureComparisonService.ARCHITECTURES.contains(architecture)) {
            throw new IllegalArgumentException("Unknown architecture: " + architecture);
        }
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
        if ("EXTENDED_ARM".equals(architecture)) {
            extendedBagging.update(toExtendedFeature(rawSql), label);
            totalTrained++;
            return;
        }
        if ("PURE_EXTENDED_ARM".equals(architecture)) {
            throw new IllegalArgumentException("PURE_EXTENDED_ARM does not support model updates.");
        }
        fullFlowBagging.update(toFeature(rawSql), label);
        totalTrained++;
        currentModelTrained++;
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
        extendedBagging.update(toExtendedFeature(rawSql), verifiedLabel);
        if (verifiedBenign && verifiedLabel == 0) {
            normalProfile.update(FeatureExtract.extract(rawSql));
        }
    }

    // Base features followed by one confidence feature per extended rule (rules are fixed per session).
    private float[] toExtendedFeature(String rawSql) {
        double[] base = FeatureExtract.extract(rawSql);
        List<Integer> tokens = ExtendedRuleMiner.tokenize(rawSql);
        float[] ruleFeatures = ExtendedRuleMiner.ruleFeatures(extendedRules,
            ExtendedRuleMiner.items(rawSql, tokens, ItemMode.BASE_TOKEN_NEG), tokens);
        float[] fused = new float[base.length + ruleFeatures.length];
        for (int i = 0; i < base.length; i++) fused[i] = (float) base[i];
        System.arraycopy(ruleFeatures, 0, fused, base.length, ruleFeatures.length);
        return fused;
    }

    synchronized float[] benchmarkFeatures(String rawSql, String architecture) {
        return switch (architecture) {
            case "RATIO_INCREMENTAL_ML" -> toBaseFeature(rawSql);
            case "FULL_FLOW" -> toFeature(rawSql);
            case "EXTENDED_ARM" -> toExtendedFeature(rawSql);
            default -> throw new IllegalArgumentException(
                "Architecture does not use an incremental classifier: " + architecture);
        };
    }

    synchronized List<float[]> benchmarkFeatures(
            List<String> rawSqlRows,
            String architecture
    ) {
        List<float[]> features = new ArrayList<>(rawSqlRows.size());
        for (String rawSql : rawSqlRows) {
            features.add(benchmarkFeatures(rawSql, architecture));
        }
        return List.copyOf(features);
    }

    Connection benchmarkConnection() {
        return connection;
    }

    Instances benchmarkHeader(int featureCount) {
        return createHeader(featureCount);
    }

    // Extended rules (token, negative-item and sequential) mined from split_group='train' only.
    private List<ExtendedRule> mineExtendedRules(Connection conn) throws Exception {
        List<String> queries = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();
        java.util.Set<LabeledPayload> seenPayloads = new java.util.HashSet<>();
        String sql = "SELECT raw_sql, label FROM DataTable WHERE split_group='train' ORDER BY id";
        try (PreparedStatement statement = conn.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String raw = rows.getString("raw_sql");
                int label = rows.getInt("label");
                if (!seenPayloads.add(new LabeledPayload(raw, label))) continue;
                queries.add(raw);
                labels.add(label);
            }
        }
        int[] y = labels.stream().mapToInt(Integer::intValue).toArray();
        List<List<Integer>> tokens = new ArrayList<>();
        List<java.util.Set<Integer>> items = new ArrayList<>();
        for (String query : queries) {
            List<Integer> t = ExtendedRuleMiner.tokenize(query);
            tokens.add(t);
            items.add(ExtendedRuleMiner.items(query, t, ItemMode.BASE_TOKEN_NEG));
        }
        List<ExtendedRule> rules = new ArrayList<>(ExtendedRuleMiner.mineItemRules(items, y,
            trainingSettings.extendedMinSupport(), trainingSettings.extendedMinConfidence(),
            trainingSettings.extendedMinLift(), trainingSettings.extendedMaxLength(),
            trainingSettings.extendedMaxRulesPerFamily(), trainingSettings.miningWorkDirectory()));
        rules.addAll(ExtendedRuleMiner.mineSequenceRules(tokens, y,
            trainingSettings.extendedMinSupport(), trainingSettings.extendedMinConfidence(),
            trainingSettings.extendedMinLift(), trainingSettings.extendedMaxLength(),
            trainingSettings.extendedMaxRulesPerFamily(), trainingSettings.miningWorkDirectory()));
        return List.copyOf(rules);
    }

    public List<ExtendedRule> getExtendedRules() {
        return extendedRules;
    }

    // Extended rules that fire for one query, strongest first.
    public List<ExtendedRule> matchedExtendedRules(String rawSql) {
        List<Integer> tokens = ExtendedRuleMiner.tokenize(rawSql);
        java.util.Set<Integer> items = ExtendedRuleMiner.items(rawSql, tokens, ItemMode.BASE_TOKEN_NEG);
        return extendedRules.stream()
            .filter(rule -> rule.matches(items, tokens))
            .sorted(java.util.Comparator.comparingDouble((ExtendedRule r) -> r.confidence).reversed())
            .toList();
    }

    private double pureExtendedArmConfidence(String rawSql) {
        List<Integer> tokens = ExtendedRuleMiner.tokenize(rawSql);
        java.util.Set<Integer> items = ExtendedRuleMiner.items(rawSql, tokens, ItemMode.BASE_TOKEN_NEG);
        return ExtendedRuleMiner.maxMatchedConfidence(extendedRules, items, tokens);
    }

    // Compare all five architecture paths against the same model state.
    public synchronized List<Map<String, Object>> predictAllSnapshot(String rawSql) throws Exception {
        return predictSnapshot(rawSql, List.of(
            "ARM_PROFILE", "RATIO_INCREMENTAL_ML", "FULL_FLOW",
            "EXTENDED_ARM", "PURE_EXTENDED_ARM"));
    }

    // Compare a selected set of methods without releasing the model-state lock.
    public synchronized List<Map<String, Object>> predictSnapshot(
            String rawSql, List<String> architectures) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String architecture : architectures) {
            double[] probability = predictArchitectureProb(rawSql, architecture);
            double threshold = getArchitectureThresholds().get(architecture);
            int label = probability[1] >= threshold ? 1 : 0;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("architecture", architecture);
            entry.put("label", label);
            entry.put("confidence", probability[label]);
            entry.put("benignProbability", probability[0]);
            entry.put("maliciousProbability", probability[1]);
            entry.put("threshold", threshold);
            entry.put("modelMode", modelMode);
            entry.put("checkpointSha256", checkpointHash);
            if ("EXTENDED_ARM".equals(architecture)) {
                entry.put("action", extendedAction(rawSql));
                entry.put("matchedRules", matchedExtendedRules(rawSql).stream()
                    .map(ExtendedRuleMiner::toMap).toList());
            } else if ("PURE_EXTENDED_ARM".equals(architecture)) {
                entry.put("action", label == 1 ? "block" : "review");
                entry.put("matchedRules", matchedExtendedRules(rawSql).stream()
                    .map(ExtendedRuleMiner::toMap).toList());
            }
            results.add(entry);
        }
        return results;
    }

    public synchronized String extendedAction(String rawSql) throws Exception {
        if (predictArchitecture(rawSql, "EXTENDED_ARM") == 1) return "block";
        boolean strongRule = matchedExtendedRules(rawSql).stream()
            .anyMatch(rule -> rule.confidence >= REVIEW_RULE_CONFIDENCE);
        return strongRule ? "review" : "allow";
    }

    private List<FeatureForML.Rule> mineTrainingRules(Connection conn) throws Exception {
        Files.createDirectories(trainingSettings.miningWorkDirectory());
        File transactions = File.createTempFile("sqlia_train_transactions_", ".txt",
                trainingSettings.miningWorkDirectory().toFile());
        try {
            String sql = "SELECT raw_sql, label FROM DataTable WHERE split_group='train' ORDER BY id";
            try (PreparedStatement statement = conn.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery();
                 BufferedWriter writer = new BufferedWriter(new FileWriter(transactions))) {
                java.util.Set<LabeledPayload> seenPayloads = new java.util.HashSet<>();
                while (rows.next()) {
                    String raw = rows.getString("raw_sql");
                    int label = rows.getInt("label");
                    if (!seenPayloads.add(new LabeledPayload(raw, label))) continue;
                    java.util.Set<Integer> items = FeatureForML.toTransactionSet(
                        FeatureExtract.extract(raw));
                    items.add(label == 1
                        ? FeatureForML.SQLI_LABEL_ITEM
                        : FeatureForML.BENIGN_LABEL_ITEM);
                    writer.write(items.stream().sorted().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(" ")));
                    writer.newLine();
                }
            }
            return new FPGrowth().getAssociationRules(
                    transactions.getAbsolutePath(), trainingSettings.standardMinSupport(),
                    trainingSettings.standardMinConfidence(), trainingSettings.miningWorkDirectory())
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

    private record LabeledPayload(String rawSql, int label) {}
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

    public synchronized void recordEvaluation(
            int predictedLabel,
            int trueLabel,
            String modelId
    ) {
        IncrementalAlgorithm algorithm = IncrementalAlgorithm.fromId(modelId);
        if (algorithm == IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB) {
            recordEvaluation(predictedLabel, trueLabel);
            return;
        }
        modelEvaluations.computeIfAbsent(algorithm, ignored -> new ModelEvaluation())
            .record(predictedLabel, trueLabel);
    }

    public synchronized List<AccuracyPoint> getAccuracyHistory(String modelId) {
        IncrementalAlgorithm algorithm = IncrementalAlgorithm.fromId(modelId);
        if (algorithm == IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB) {
            return getAccuracyHistory();
        }
        ModelEvaluation evaluation = modelEvaluations.get(algorithm);
        return evaluation == null ? List.of() : List.copyOf(evaluation.points);
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
        if (decisionThresholds != null) return decisionThresholds;
        return Map.of(
                "ARM_PROFILE", DECISION_THRESHOLD,
                "RATIO_INCREMENTAL_ML", DECISION_THRESHOLD,
                "FULL_FLOW", DECISION_THRESHOLD,
                "EXTENDED_ARM", DECISION_THRESHOLD,
                "PURE_EXTENDED_ARM", PURE_EXTENDED_ARM_THRESHOLD);
    }

    private static final class ModelEvaluation {
        private int evaluated;
        private int correct;
        private final List<AccuracyPoint> points = new ArrayList<>();

        void record(int predictedLabel, int trueLabel) {
            evaluated++;
            if (predictedLabel == trueLabel) {
                correct++;
            }
            if (evaluated % METRICS_INTERVAL != 0) {
                return;
            }
            if (points.size() >= MAX_ACCURACY_POINTS) {
                points.remove(0);
            }
            points.add(new AccuracyPoint(
                points.size() + 1,
                (double) correct / evaluated,
                evaluated,
                Instant.now().toString()));
        }
    }

}