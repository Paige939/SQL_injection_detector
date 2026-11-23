package tw.edu.cse.nsysu.api.service;

import java.sql.Connection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tw.edu.cse.nsysu.ExtendedRuleMiner;

/** Session-fixed copies; website feedback and replay cannot modify these classifiers. */
public final class DefenseModels implements DefenseService.Predictor {
    public record TrainingRow(String sql, int label) {}
    private final ModelStateService state;
    private final Map<IncrementalAlgorithm, IncrementalClassifier> classifiers =
            new EnumMap<>(IncrementalAlgorithm.class);
    private final String version = UUID.randomUUID().toString();
    private final int trainingSamples;

    public DefenseModels(ModelStateService website, Connection training) throws Exception {
        state = website.defenseSnapshot();
        List<TrainingRow> rows = website.defenseWarmupRows(training);
        trainingSamples = rows.size();
        if (rows.isEmpty() || rows.size() != website.getTotalTrained()
                || rows.stream().map(TrainingRow::label).distinct().count() != 2) {
            throw new IllegalStateException("Defense startup requires the original two-class website warmup.");
        }
        classifiers.put(IncrementalAlgorithm.SGD, new SgdIncrementalClassifier());
        classifiers.put(IncrementalAlgorithm.HOEFFDING_TREE, new HoeffdingTreeIncrementalClassifier());
        int dimension = state.benchmarkFeatures(rows.getFirst().sql(), "EXTENDED_ARM").length;
        for (var classifier : classifiers.values()) classifier.initialize(dimension);
        for (TrainingRow row : rows) {
            float[] features = state.benchmarkFeatures(row.sql(), "EXTENDED_ARM");
            for (var classifier : classifiers.values()) classifier.update(features, row.label());
        }
    }

    @Override
    public synchronized DefenseService.Prediction predict(String input, String architecture,
            IncrementalAlgorithm algorithm) throws Exception {
        double score = "EXTENDED_ARM".equals(architecture)
                && algorithm != IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB
                ? classifiers.get(algorithm).predictProb(
                        state.benchmarkFeatures(input, architecture))[1]
                : state.predictArchitectureProb(input, architecture)[1];
        double threshold = state.getArchitectureThresholds().get(architecture);
        var rules = architecture.contains("EXTENDED_ARM")
                ? state.matchedExtendedRules(input) : List.<ExtendedRuleMiner.ExtendedRule>of();
        String action = ModelStateService.defenseAction(architecture, score, threshold, rules);
        return new DefenseService.Prediction(score, threshold, action,
                rules.stream().map(ExtendedRuleMiner::toMap).toList());
    }

    @Override public String version() { return version; }
    public int trainingSamples() { return trainingSamples; }
}
