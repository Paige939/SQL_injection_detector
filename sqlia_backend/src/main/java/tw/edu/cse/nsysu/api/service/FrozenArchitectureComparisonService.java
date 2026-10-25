package tw.edu.cse.nsysu.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import tw.edu.cse.nsysu.api.dto.SimulationRequest;
import tw.edu.cse.nsysu.api.dto.SimulationResult;
import tw.edu.cse.nsysu.preprocess.InputType;
import tw.edu.cse.nsysu.preprocess.PayloadNormalizer;

/** Fresh models per experiment; interactive model state is never used for scoring. */
public final class FrozenArchitectureComparisonService {
    public static final List<String> ARCHITECTURES = List.of(
            "ARM_PROFILE", "RATIO_INCREMENTAL_ML", "FULL_FLOW", "EXTENDED_ARM", "PURE_EXTENDED_ARM");
    private static final long SEED = 42;
    private final Connection source;
    private final Path allowedRoot;
    private final Trainer trainer;

    @FunctionalInterface
    interface Scorer { double score(String input, String architecture) throws Exception; }
    @FunctionalInterface
    interface Trainer { Scorer train(Connection trainingOnly) throws Exception; }
    record Row(String text, int label, String source, String split) {}

    public FrozenArchitectureComparisonService(Connection source) {
        this(source, Path.of("data"), training -> {
            ModelStateService state = new ModelStateService(training, Integer.MAX_VALUE, 15);
            return (input, architecture) -> state.predictArchitectureProb(input, architecture)[1];
        });
    }

    FrozenArchitectureComparisonService(Connection source, Path allowedRoot, Trainer trainer) {
        this.source = source;
        this.allowedRoot = allowedRoot.toAbsolutePath().normalize();
        this.trainer = trainer;
    }

    public synchronized void trainAndValidate(Path output) throws Exception {
        output = output.toAbsolutePath().normalize();
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Training output must be a new directory: " + output);
        }
        List<Row> snapshot = snapshot();
        validateSnapshot(snapshot);
        List<Row> train = snapshot.stream().filter(r -> r.split().equals("train")).toList();
        List<Row> validation = snapshot.stream().filter(r -> r.split().equals("validation")).toList();
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "running");
        report.put("datasetFingerprint", fingerprint(snapshot));
        report.put("trainingFingerprint", fingerprint(train));
        report.put("validationFingerprint", fingerprint(validation));
        report.put("trainingSamples", train.size());
        report.put("validationSamples", validation.size());
        report.put("testPredictions", 0);
        report.put("testEvaluated", false);
        report.put("createdAt", java.time.Instant.now().toString());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("wekaVersion", weka.core.Version.VERSION);
        report.put("trainingSettings", ModelStateService.frozenTrainingSettings());
        report.put("thresholdPolicy", "validation_max_f1_then_min_fpr");
        report.put("ruleParameterPolicy", "prespecified_defaults_not_grid_tuned");
        report.put("protocolVersion", "frozen-full-training-v1");
        ObjectMapper mapper = new ObjectMapper();
        Path reportPath = output.resolve("training_validation.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        try (Connection training = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            populateTraining(training, train);
            long started = System.nanoTime();
            ModelStateService state = new ModelStateService(training, Integer.MAX_VALUE, 15);
            if (state.getTotalTrained() != train.size()) {
                throw new IllegalStateException("Full training did not cover every Training row.");
            }
            report.put("trainingDurationMs", (System.nanoTime() - started) / 1_000_000);
            Path checkpoint = output.resolve("frozen-model.ser");
            state.saveFrozen(checkpoint);
            ModelStateService restored = ModelStateService.loadFrozen(checkpoint);
            Map<String, Object> choices = new LinkedHashMap<>();
            for (String architecture : ARCHITECTURES) {
                List<ValidationThreshold.Scored> scores = new ArrayList<>();
                for (Row row : validation) {
                    double original = state.predictArchitectureProb(row.text(), architecture)[1];
                    double reloaded = restored.predictArchitectureProb(row.text(), architecture)[1];
                    if (Double.compare(original, reloaded) != 0) {
                        throw new IllegalStateException("Reloaded model predictions differ: " + architecture);
                    }
                    scores.add(new ValidationThreshold.Scored(original, row.label()));
                }
                var choice = ValidationThreshold.select(scores);
                choices.put(architecture, choice);
                System.out.println("[validation] " + architecture + " " + choice);
            }
            report.put("architectures", choices);
            report.put("checkpointSha256", HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(checkpoint))));
            report.put("reloadVerifiedOnAllValidationRows", true);
            report.put("standardRuleCount", state.getRuleFeatureCount());
            report.put("extendedRuleCount", state.getExtendedRules().size());
            report.put("status", "complete");
            report.put("completedAt", java.time.Instant.now().toString());
            mapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        } catch (Exception failure) {
            report.put("status", "failed");
            report.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            mapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
            throw failure;
        }
    }

    public synchronized List<Map<String, Object>> compare(
            SimulationRequest request, List<String> architectures) throws Exception {
        if (request.datasetPath == null || request.sampleSize <= 0 || request.updateModel) {
            throw new IllegalArgumentException("Frozen comparison requires test data, a positive sample size, and updates disabled.");
        }
        if (architectures.isEmpty() || new HashSet<>(architectures).size() != architectures.size()
                || !ARCHITECTURES.containsAll(architectures)) {
            throw new IllegalArgumentException("Unknown or duplicate comparison architecture.");
        }
        Path dataset = Path.of(request.datasetPath).toRealPath();
        if (!dataset.startsWith(allowedRoot.toRealPath())) {
            throw new IllegalArgumentException("Comparison dataset must be below data/.");
        }
        // One SELECT captures the data independently of later imports or feedback.
        List<Row> snapshot = snapshot();
        validateSnapshot(snapshot);
        List<Row> train = snapshot.stream().filter(r -> r.split().equals("train")).toList();
        List<Row> validation = snapshot.stream().filter(r -> r.split().equals("validation")).toList();
        List<Row> test = readMatchingTest(dataset, snapshot);
        String fingerprint = fingerprint(snapshot);
        Collections.shuffle(test, new Random(SEED));
        test = new ArrayList<>(test.subList(0, Math.min(request.sampleSize, test.size())));
        if (request.checkpointDirectory != null) {
            return compareSaved(request.checkpointDirectory, architectures, snapshot, train, validation, test);
        }
        try (Connection training = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            populateTraining(training, train);
            long started = System.nanoTime();
            Scorer scorer = trainer.train(training);
            long trainingMs = (System.nanoTime() - started) / 1_000_000;
            Map<String, ValidationThreshold.Choice> thresholds = new LinkedHashMap<>();
            // Finish every validation decision before scoring any test row.
            for (String architecture : architectures) {
                List<ValidationThreshold.Scored> scores = new ArrayList<>();
                for (Row row : validation) {
                    scores.add(new ValidationThreshold.Scored(
                            scorer.score(row.text(), architecture), row.label()));
                }
                thresholds.put(architecture, ValidationThreshold.select(scores));
            }
            return evaluate(scorer, architectures, thresholds, test, train.size(), validation.size(),
                    fingerprint, trainingMs, "frozen_full_training");
        }
    }

    private List<Map<String, Object>> compareSaved(String directory, List<String> architectures,
            List<Row> snapshot, List<Row> train, List<Row> validation, List<Row> test) throws Exception {
        SavedState saved = loadSaved(directory, snapshot, train, validation);
        var results = evaluate((input, architecture) -> saved.state().predictArchitectureProb(input, architecture)[1],
                architectures, saved.thresholds(), test, train.size(), validation.size(), fingerprint(snapshot),
                saved.trainingDurationMs(), "frozen_saved_checkpoint");
        for (var result : results) {
            result.put("checkpointSha256", saved.checkpointHash());
            result.put("trainingReportSha256", saved.reportHash());
        }
        return results;
    }

    record SavedState(ModelStateService state, Map<String, ValidationThreshold.Choice> thresholds,
            long trainingDurationMs, String checkpointHash, String reportHash) {}

    synchronized SavedState loadSaved(String directory) throws Exception {
        List<Row> rows = snapshot();
        validateSnapshot(rows);
        return loadSaved(directory, rows, rows.stream().filter(r -> r.split().equals("train")).toList(),
                rows.stream().filter(r -> r.split().equals("validation")).toList());
    }

    private SavedState loadSaved(String directory, List<Row> snapshot, List<Row> train,
            List<Row> validation) throws Exception {
        Path root = allowedRoot.toRealPath().resolve("experiments").toRealPath();
        Path run = Path.of(directory).toRealPath();
        if (!run.startsWith(root)) {
            throw new IllegalArgumentException("Saved experiments must be below data/experiments/.");
        }
        Path reportPath = run.resolve("training_validation.json").toRealPath();
        Path checkpoint = run.resolve("frozen-model.ser").toRealPath();
        if (!reportPath.startsWith(run) || !checkpoint.startsWith(run)) {
            throw new IllegalArgumentException("Experiment files must remain inside their run directory.");
        }
        var report = new ObjectMapper().readTree(reportPath.toFile());
        if (!"complete".equals(report.path("status").asText())
                || !"frozen-full-training-v1".equals(report.path("protocolVersion").asText())
                || !report.path("reloadVerifiedOnAllValidationRows").asBoolean()
                || !report.path("testEvaluated").isBoolean() || report.path("testEvaluated").asBoolean()
                || !report.path("testPredictions").isIntegralNumber() || report.path("testPredictions").asInt() != 0
                || !fingerprint(snapshot).equals(report.path("datasetFingerprint").asText())
                || !fingerprint(train).equals(report.path("trainingFingerprint").asText())
                || !fingerprint(validation).equals(report.path("validationFingerprint").asText())
                || report.path("trainingSamples").asInt(-1) != train.size()
                || report.path("validationSamples").asInt(-1) != validation.size()
                || !new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(
                        ModelStateService.frozenTrainingSettings())).equals(report.path("trainingSettings"))) {
            throw new IllegalArgumentException("Saved experiment is incomplete, incompatible, or belongs to different data.");
        }
        byte[] checkpointBytes = Files.readAllBytes(checkpoint);
        String checkpointHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(checkpointBytes));
        if (!checkpointHash.equals(report.path("checkpointSha256").asText())) {
            throw new IllegalArgumentException("Saved model checksum mismatch.");
        }
        Map<String, ValidationThreshold.Choice> thresholds = new LinkedHashMap<>();
        for (String architecture : ARCHITECTURES) {
            var node = report.path("architectures").path(architecture);
            for (String field : List.of("threshold", "f1", "fpr")) {
                if (!node.path(field).isNumber() || !Double.isFinite(node.path(field).doubleValue())) {
                    throw new IllegalArgumentException("Invalid saved Validation choice: " + architecture);
                }
            }
            double threshold = node.get("threshold").doubleValue();
            if (threshold < 0 || threshold > Math.nextUp(1.0)) {
                throw new IllegalArgumentException("Invalid saved threshold.");
            }
            ValidationThreshold.requireScore(node.get("f1").doubleValue());
            ValidationThreshold.requireScore(node.get("fpr").doubleValue());
            thresholds.put(architecture, new ValidationThreshold.Choice(
                    threshold, node.get("f1").doubleValue(), node.get("fpr").doubleValue()));
        }
        ModelStateService state = ModelStateService.loadFrozen(checkpointBytes);
        if (state.getTotalTrained() != train.size()) {
            throw new IllegalArgumentException("Saved model training count mismatch.");
        }
        String reportHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(reportPath)));
        return new SavedState(state, Map.copyOf(thresholds),
                report.path("trainingDurationMs").asLong(), checkpointHash, reportHash);
    }

    private static List<Map<String, Object>> evaluate(Scorer scorer, List<String> architectures,
            Map<String, ValidationThreshold.Choice> thresholds, List<Row> test,
            int trainingSamples, int validationSamples, String fingerprint, long trainingMs,
            String evaluationMode) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String architecture : architectures) {
            long evaluationStarted = System.nanoTime();
            var choice = thresholds.get(architecture);
            SimulationResult.GroupMetrics overall = new SimulationResult.GroupMetrics();
            Map<String, SimulationResult.GroupMetrics> bySource = new LinkedHashMap<>();
            Map<String, SimulationResult.GroupMetrics> byType = new LinkedHashMap<>();
            for (Row row : test) {
                double score = scorer.score(row.text(), architecture);
                ValidationThreshold.requireScore(score);
                int prediction = score >= choice.threshold() ? 1 : 0;
                overall.add(prediction, row.label());
                bySource.computeIfAbsent(row.source(), k -> new SimulationResult.GroupMetrics())
                        .add(prediction, row.label());
                byType.computeIfAbsent(InputType.classify(row.text()), k -> new SimulationResult.GroupMetrics())
                        .add(prediction, row.label());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("architecture", architecture);
            result.put("total", overall.total);
            result.put("tp", overall.tp);
            result.put("fp", overall.fp);
            result.put("tn", overall.tn);
            result.put("fn", overall.fn);
            result.put("accuracy", overall.accuracy);
            result.put("precision", overall.precision);
            result.put("recall", overall.recall);
            result.put("f1", overall.f1);
            result.put("fpr", overall.fpr);
            result.put("durationMs", (System.nanoTime() - evaluationStarted) / 1_000_000);
            result.put("trainingDurationMs", trainingMs);
            result.put("evaluationMode", evaluationMode);
            result.put("trainingSamples", trainingSamples);
            result.put("validationSamples", validationSamples);
            result.put("threshold", choice.threshold());
            result.put("validationF1", choice.f1());
            result.put("validationFpr", choice.fpr());
            result.put("datasetFingerprint", fingerprint);
            result.put("testFingerprint", fingerprint(test));
            result.put("seed", SEED);
            result.put("thresholdPolicy", "validation_max_f1_then_min_fpr");
            result.put("ruleParameterPolicy", "prespecified_defaults_not_grid_tuned");
            result.put("trainingSettings", ModelStateService.frozenTrainingSettings());
            result.put("protocolVersion", "frozen-full-training-v1");
            result.put("bySource", bySource);
            result.put("byInputType", byType);
            results.add(result);
        }
        return results;
    }

    private List<Row> snapshot() throws Exception {
        List<Row> rows = new ArrayList<>();
        try (var statement = source.prepareStatement(
                "SELECT raw_sql,label,source,split_group FROM DataTable ORDER BY raw_sql,source,split_group");
                var result = statement.executeQuery()) {
            while (result.next()) {
                String value = result.getString(2);
                if (!"0".equals(value) && !"1".equals(value)) {
                    throw new IllegalArgumentException("Dataset labels must be canonical 0 or 1.");
                }
                int label = Integer.parseInt(value);
                rows.add(new Row(result.getString(1), label, result.getString(3), result.getString(4)));
            }
        }
        return rows;
    }

    static void validateSnapshot(List<Row> rows) {
        Map<String, String> groups = new HashMap<>();
        Set<String> texts = new HashSet<>();
        Map<String, Set<Integer>> labels = new HashMap<>();
        for (Row row : rows) {
            if (row.text() == null || row.text().isBlank() || row.source() == null
                    || row.source().isBlank() || row.split() == null
                    || !Set.of("train", "validation", "test").contains(row.split())
                    || (row.label() != 0 && row.label() != 1)) {
                throw new IllegalArgumentException("Dataset has invalid text, label, source, or split.");
            }
            if (!texts.add(row.text())) throw new IllegalArgumentException("Dataset contains exact duplicate text.");
            String previous = groups.putIfAbsent(PayloadNormalizer.groupKey(row.text()), row.split());
            if (previous != null && !previous.equals(row.split())) {
                throw new IllegalArgumentException("Dataset group crosses train/validation/test boundaries.");
            }
            labels.computeIfAbsent(row.split(), k -> new HashSet<>()).add(row.label());
        }
        for (String split : List.of("train", "validation", "test")) {
            if (!Set.of(0, 1).equals(labels.get(split))) {
                throw new IllegalArgumentException("Every split must contain both labels: " + split);
            }
        }
    }

    private static List<Row> readMatchingTest(Path path, List<Row> snapshot) throws Exception {
        Set<Row> expected = new HashSet<>(snapshot.stream().filter(r -> r.split().equals("test")).toList());
        List<Row> rows = new ArrayList<>();
        Set<Row> seen = new HashSet<>();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            if (!parser.getHeaderMap().containsKey("split") && !parser.getHeaderMap().containsKey("split_group")) {
                throw new IllegalArgumentException("Frozen comparison requires explicit test split metadata.");
            }
            for (var record : parser) {
                EvaluationDatasetSupport.requireTestSplit(record);
                Row row = new Row(EvaluationDatasetSupport.requireSql(record),
                        EvaluationDatasetSupport.requireBinaryLabel(record),
                        EvaluationDatasetSupport.firstMapped(record, "source"), "test");
                if (!expected.contains(row) || !seen.add(row)) {
                    throw new IllegalArgumentException("Test CSV differs from the database test partition; regenerate or select test.csv.");
                }
                rows.add(row);
            }
        }
        if (!seen.equals(expected)) {
            throw new IllegalArgumentException("Test CSV must contain the entire fixed database test partition.");
        }
        rows.sort(Comparator.comparing(Row::text).thenComparing(Row::source));
        return rows;
    }

    private static void populateTraining(Connection connection, List<Row> rows) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE DataTable(id INTEGER PRIMARY KEY,raw_sql TEXT,label INTEGER,"
                    + "source TEXT,split_group TEXT,batch_seq INTEGER)");
        }
        try (var insert = connection.prepareStatement(
                "INSERT INTO DataTable VALUES(?,?,?,?, 'train',?)")) {
            int id = 0;
            for (Row row : rows) {
                insert.setInt(1, ++id);
                insert.setString(2, row.text());
                insert.setInt(3, row.label());
                insert.setString(4, row.source());
                insert.setInt(5, id);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static String fingerprint(List<Row> rows) throws Exception {
        byte[] json = new ObjectMapper().writeValueAsBytes(rows);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
    }
}
