package tw.edu.cse.nsysu.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import tw.edu.cse.nsysu.preprocess.DatasetPreprocessor;
import tw.edu.cse.nsysu.preprocess.Deduplicator;
import tw.edu.cse.nsysu.preprocess.LabelValidator;
import tw.edu.cse.nsysu.preprocess.PayloadNormalizer;
import tw.edu.cse.nsysu.preprocess.RawRecord;
import tw.edu.cse.nsysu.preprocess.SourceHoldoutSplitter;
import tw.edu.cse.nsysu.preprocess.StratifiedSplitter;

/** Validation-only model/configuration selection with disjoint source holdouts. */
public final class ResearchExperimentService {
    public static final String REPORT_NAME = "research_results.json";
    public static final long SPLIT_SEED = 20261025L;
    public static final List<String> ARCHITECTURES = FrozenArchitectureComparisonService.ARCHITECTURES;
    private static final List<String> SOURCES = DatasetPreprocessor.defaultSources().stream()
            .map(spec -> spec.sourceName()).toList();

    public record Sample(String text, int label, String source, String split, String groupId) {}

    public record GridPoint(
            String id, int ensembleSize, double standardMinConfidence,
            double extendedMinConfidence, int extendedMaxLength) {}

    private static final List<GridPoint> GRID = List.of(
            new GridPoint("baseline", 15, 0.6, 0.6, 3),
            new GridPoint("small_ensemble", 5, 0.6, 0.6, 3),
            new GridPoint("high_confidence_rules", 15, 0.8, 0.8, 3),
            new GridPoint("short_extended_rules", 15, 0.6, 0.6, 2));

    interface ExperimentModel {
        double probability(String text, String architecture) throws Exception;
        void save(Path path) throws Exception;
    }

    @FunctionalInterface
    interface ModelTrainer {
        ExperimentModel train(Connection trainingOnly,
                ModelStateService.TrainingSettings settings) throws Exception;
    }

    private final ModelTrainer trainer;

    public ResearchExperimentService() {
        this((training, settings) -> {
            ModelStateService model = new ModelStateService(training, Integer.MAX_VALUE, settings);
            if (model.getTotalTrained() != countTrainingRows(training)) {
                throw new IllegalStateException("Model did not train on every unique Training row.");
            }
            return new ExperimentModel() {
                @Override
                public double probability(String text, String architecture) throws Exception {
                    return model.predictArchitectureProb(text, architecture)[1];
                }

                @Override
                public void save(Path path) throws Exception {
                    model.saveFrozen(path);
                }
            };
        });
    }

    ResearchExperimentService(ModelTrainer trainer) {
        this.trainer = trainer;
    }

    public synchronized Path run(
            List<RawRecord> rawRecords,
            List<Sample> mergedTraining,
            List<Sample> mergedValidation,
            Path outputDirectory) throws Exception {
        Path output = outputDirectory.toAbsolutePath().normalize();
        Path reportPath = output.resolve(REPORT_NAME);
        if (Files.exists(reportPath)) {
            throw new IllegalArgumentException("Research report already exists; refusing to overwrite: " + reportPath);
        }
        Files.createDirectories(output.resolve("artifacts"));
        if (Files.exists(output.resolve("artifacts/merged"))) {
            throw new IllegalArgumentException("Research artifacts already exist; refusing to overwrite.");
        }
        Files.createDirectory(output.resolve("artifacts/merged"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "sqlia-research-results-v1");
        report.put("status", "running");
        report.put("generatedAt", java.time.Instant.now().toString());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("wekaVersion", weka.core.Version.VERSION);
        report.put("protocol", Map.of(
                "validationSelectionMetric", "maximum F1; ties use minimum FPR; remaining ties preserve grid order",
                "thresholdSelection", "maximum validation F1; ties use minimum validation FPR",
                "gridCoverage", "Four prespecified configurations only; this is a bounded finite search, not an exhaustive search.",
                "architecturesPerTrainingRun", ARCHITECTURES,
                "mergedTestEvaluated", false,
                "mergedTestPartitionRead", false,
                "crossDatasetSelection", "Training/Validation only",
                "crossDatasetFinalEvaluation", "selected configuration per architecture, once on held-out source"));
        report.put("grid", gridDescription());

        try {
            List<RawRecord> valid = LabelValidator.validate(rawRecords).stream()
                    .filter(row -> row.rawText != null && !row.rawText.isBlank()).toList();
            Deduplicator.Result deduplicated = Deduplicator.partition(valid);
            Map<String, Object> overlapAudit = overlapAudit(deduplicated.records(), deduplicated.conflicts());
            report.put("sourceOverlapAudit", overlapAudit);

            validateSamples(mergedTraining, "train");
            validateSamples(mergedValidation, "validation");
            assertDisjointGroups(mergedTraining, mergedValidation);
            report.put("mergedValidationTuning", runMergedTuning(
                    mergedTraining, mergedValidation, output.resolve("artifacts/merged")));
            List<Map<String, Object>> crossDataset = new ArrayList<>();
            for (String heldOutSource : SOURCES) {
                Path artifactDirectory = output.resolve("artifacts/cross-dataset")
                        .resolve(sourceProtocolName(heldOutSource));
                Files.createDirectories(artifactDirectory.getParent());
                Files.createDirectory(artifactDirectory);
                crossDataset.add(runHeldOutSource(deduplicated.records(), deduplicated.conflicts(),
                        heldOutSource, artifactDirectory));
            }
            report.put("crossDataset", crossDataset);
            long incompleteProtocols = crossDataset.stream()
                    .filter(protocol -> !"complete".equals(protocol.get("status"))).count();
            report.put("status", incompleteProtocols == 0
                    ? "complete" : "complete_with_incomplete_holdouts");
            report.put("incompleteHoldoutCount", incompleteProtocols);
            report.put("completedAt", java.time.Instant.now().toString());
        } catch (Exception failure) {
            report.put("status", "failed");
            report.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            report.put("completedAt", java.time.Instant.now().toString());
            writeReport(reportPath, report);
            throw failure;
        }
        writeReport(reportPath, report);
        return reportPath;
    }

    private Map<String, Object> runMergedTuning(
            List<Sample> training, List<Sample> validation, Path artifactDirectory) throws Exception {
        requireBothLabels(training, "merged Training");
        requireBothLabels(validation, "merged Validation");
        String trainingFingerprint = fingerprint(training);
        String validationFingerprint = fingerprint(validation);
        Map<String, List<Trial>> trials = trainGrid(training, validation, artifactDirectory);
        Map<String, Object> architectures = new LinkedHashMap<>();
        for (String architecture : ARCHITECTURES) {
            Trial selected = select(trials.get(architecture));
            String artifactName = safeFileName(architecture) + ".ser";
            selected.model().save(artifactDirectory.resolve(artifactName));
            architectures.put(architecture, selectionReport(selected, trials.get(architecture),
                    "artifacts/merged/" + artifactName));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "complete");
        result.put("trainingSamples", training.size());
        result.put("validationSamples", validation.size());
        result.put("trainingFingerprint", trainingFingerprint);
        result.put("validationFingerprint", validationFingerprint);
        result.put("testPartitionRead", false);
        result.put("testPredictions", 0);
        result.put("architectures", architectures);
        return result;
    }

    private Map<String, Object> runHeldOutSource(
            List<RawRecord> records,
            List<RawRecord> conflicts,
            String heldOutSource,
            Path artifactDirectory) throws Exception {
        Map<String, List<RawRecord>> partitions =
                SourceHoldoutSplitter.holdOutBySource(records, heldOutSource);
        List<RawRecord> nonHeldOut = partitions.get("train_other_sources");
        List<RawRecord> heldOutExclusive = partitions.get("test_holdout_source");
        List<RawRecord> sharedProvenance = records.stream()
                .filter(row -> hasSource(row.source, heldOutSource) && hasOtherSource(row.source, heldOutSource))
                .toList();
        List<RawRecord> heldOutExclusiveBeforeOverlapFilter = records.stream()
                .filter(row -> hasSource(row.source, heldOutSource) && !hasOtherSource(row.source, heldOutSource))
                .toList();
        Set<String> trainingGroups = nonHeldOut.stream()
                .map(row -> PayloadNormalizer.groupKey(row.rawText)).collect(java.util.stream.Collectors.toSet());
        int nearOverlapExcluded = (int) heldOutExclusiveBeforeOverlapFilter.stream()
                .filter(row -> trainingGroups.contains(PayloadNormalizer.groupKey(row.rawText))).count();

        var sourceSplit = StratifiedSplitter.splitByLabelAndGroup(nonHeldOut, 0.85, 0.15, SPLIT_SEED);
        List<Sample> training = samples(sourceSplit.train(), "train");
        List<Sample> validation = samples(sourceSplit.val(), "validation");
        List<Sample> test = samples(heldOutExclusive, "heldout");
        assertDisjointGroups(training, validation);
        assertDisjointGroups(training, test);
        assertDisjointGroups(validation, test);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("heldOutSource", heldOutSource);
        result.put("protocol", String.join("+", SOURCES.stream()
                .filter(source -> !source.equals(heldOutSource)).toList()) + "->" + heldOutSource);
        result.put("trainingSources", SOURCES.stream().filter(source -> !source.equals(heldOutSource)).toList());
        result.put("trainingSamples", training.size());
        result.put("validationSamples", validation.size());
        result.put("heldOutRowsByProvenance", sharedProvenance.size() + heldOutExclusiveBeforeOverlapFilter.size());
        result.put("heldOutExclusiveCandidates", heldOutExclusiveBeforeOverlapFilter.size());
        Map<String, Integer> heldOutLabelCounts = labelCounts(test);
        result.put("heldOutLabelCounts", heldOutLabelCounts);
        int minorityClassCount = Math.min(heldOutLabelCounts.get("0"), heldOutLabelCounts.get("1"));
        int majorityClassCount = Math.max(heldOutLabelCounts.get("0"), heldOutLabelCounts.get("1"));
        result.put("identifiabilityStatus", minorityClassCount == 0
                ? "unidentifiable_missing_class"
                : minorityClassCount < 20 ? "identifiable_sparse_minority" : "identifiable");
        result.put("heldOutClassImbalanceRatio", minorityClassCount == 0
                ? "not_estimable" : (double) majorityClassCount / minorityClassCount);
        result.put("sharedExactProvenanceExcludedFromHeldOut", sharedProvenance.size());
        result.put("nearOverlapVariantsExcludedFromHeldOut", nearOverlapExcluded);
        result.put("conflictingExactRowsQuarantined", conflicts.size());
        result.put("groupOverlapCount", 0);
        result.put("heldOutRowsEvaluated", 0);
        result.put("evaluationPassesPerArchitecture", 0);
        result.put("overlapStatus", sharedProvenance.isEmpty() && nearOverlapExcluded == 0
                ? "no_overlap_detected" : "overlaps_explicitly_excluded");

        if (!hasBothLabels(test)) {
            result.put("status", "unidentifiable");
            result.put("incompleteReason", "Held-out source lacks one or both labels after overlap exclusion; no fallback or Test reuse is permitted.");
            result.put("architectures", Map.of());
            return result;
        }
        if (!hasBothLabels(training) || !hasBothLabels(validation)) {
            result.put("status", "incomplete");
            result.put("incompleteReason", "Non-held-out Training and Validation must each contain both labels; no fallback split is used.");
            result.put("architectures", Map.of());
            return result;
        }

        result.put("heldOutRowsEvaluated", test.size());
        result.put("evaluationPassesPerArchitecture", 1);
        Map<String, List<Trial>> trials = trainGrid(training, validation, artifactDirectory);
        Map<String, Object> architectures = new LinkedHashMap<>();
        for (String architecture : ARCHITECTURES) {
            Trial selected = select(trials.get(architecture));
            Map<String, Integer> confusion = new LinkedHashMap<>();
            int tp = 0, fp = 0, tn = 0, fn = 0;
            for (Sample row : test) {
                double score = selected.model().probability(row.text(), architecture);
                ValidationThreshold.requireScore(score);
                boolean predicted = score >= selected.threshold().threshold();
                if (predicted && row.label() == 1) tp++;
                else if (predicted) fp++;
                else if (row.label() == 0) tn++;
                else fn++;
            }
            confusion.put("tp", tp);
            confusion.put("fp", fp);
            confusion.put("tn", tn);
            confusion.put("fn", fn);
            String artifactName = safeFileName(architecture) + ".ser";
            selected.model().save(artifactDirectory.resolve(artifactName));
            Map<String, Object> architectureResult = new LinkedHashMap<>(
                    selectionReport(selected, trials.get(architecture),
                            "artifacts/cross-dataset/" + sourceProtocolName(heldOutSource) + "/" + artifactName));
            architectureResult.put("heldOut", evaluationReport(tp, fp, tn, fn));
            architectures.put(architecture, architectureResult);
        }
        result.put("status", "complete");
        result.put("trainingFingerprint", fingerprint(training));
        result.put("validationFingerprint", fingerprint(validation));
        result.put("heldOutFingerprint", fingerprint(test));
        result.put("architectures", architectures);
        return result;
    }

    private Map<String, List<Trial>> trainGrid(
            List<Sample> training, List<Sample> validation, Path workspaceRoot) throws Exception {
        Map<String, List<Trial>> trials = new LinkedHashMap<>();
        for (String architecture : ARCHITECTURES) trials.put(architecture, new ArrayList<>());
        for (GridPoint point : GRID) {
            Path miningWorkspace = workspaceRoot.resolve("mining").resolve(point.id());
            ModelStateService.TrainingSettings settings = settings(point, miningWorkspace);
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
                populateTraining(connection, training);
                ExperimentModel model = trainer.train(connection, settings);
                for (String architecture : ARCHITECTURES) {
                    List<ValidationThreshold.Scored> scored = new ArrayList<>(validation.size());
                    for (Sample row : validation) {
                        double score = model.probability(row.text(), architecture);
                        ValidationThreshold.requireScore(score);
                        scored.add(new ValidationThreshold.Scored(score, row.label()));
                    }
                    trials.get(architecture).add(new Trial(point, model, ValidationThreshold.select(scored)));
                }
            }
        }
        return trials;
    }

    private static ModelStateService.TrainingSettings settings(GridPoint point, Path workDirectory) {
        ModelStateService.TrainingSettings defaults = ModelStateService.TrainingSettings.defaults();
        return new ModelStateService.TrainingSettings(
                defaults.trainingOrderSeed(), point.ensembleSize(), defaults.fullFlowSeed(),
                defaults.ratioSeed(), defaults.extendedSeed(), defaults.standardMinSupport(),
                point.standardMinConfidence(), defaults.extendedMinSupport(),
                point.extendedMinConfidence(), defaults.extendedMinLift(), point.extendedMaxLength(),
                defaults.extendedMaxRulesPerFamily(), workDirectory);
    }

    private static Trial select(List<Trial> trials) {
        return trials.stream().max(Comparator
                .comparingDouble((Trial trial) -> trial.threshold().f1())
                .thenComparingDouble(trial -> -trial.threshold().fpr())
                .thenComparingInt(trial -> -GRID.indexOf(trial.point())))
                .orElseThrow(() -> new IllegalStateException("No validation trials were completed."));
    }

    private static Map<String, Object> selectionReport(
            Trial selected, List<Trial> trials, String artifactPath) {
        List<Map<String, Object>> trialReports = trials.stream().map(trial -> Map.<String, Object>of(
                "configuration", trial.point().id(),
                "validationThreshold", trial.threshold().threshold(),
                "validationF1", trial.threshold().f1(),
                "validationFpr", trial.threshold().fpr())).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selectedConfiguration", selected.point().id());
        result.put("validationThreshold", selected.threshold().threshold());
        result.put("validationF1", selected.threshold().f1());
        result.put("validationFpr", selected.threshold().fpr());
        result.put("trials", trialReports);
        result.put("artifact", artifactPath);
        return result;
    }

    private static Map<String, Object> evaluationReport(int tp, int fp, int tn, int fn) {
        double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
        return Map.of(
                "tp", tp, "fp", fp, "tn", tn, "fn", fn,
                "precision", precision,
                "recall", recall,
                "f1", precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall),
                "fpr", fp + tn == 0 ? 0 : (double) fp / (fp + tn));
    }

    private static Map<String, Object> overlapAudit(List<RawRecord> records, List<RawRecord> conflicts) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retainedExactDeduplicatedRows", records.size());
        result.put("conflictingExactRowsQuarantined", conflicts.size());
        result.put("rowsWithMultiSourceExactProvenance", records.stream()
                .filter(row -> row.source.contains("|")).count());
        result.put("normalizationPolicy", "PayloadNormalizer.groupKey; collisions are held out, never merged.");
        result.put("sharedProvenancePolicy", "Rows with held-out source plus another source remain on training side; excluded from held-out evaluation.");
        result.put("contaminationHandling", "Near-overlapping held-out variants matching non-held-out normalized groups are excluded.");
        return result;
    }

    private static List<Sample> samples(List<RawRecord> rows, String split) {
        return rows.stream().map(row -> new Sample(row.rawText, Integer.parseInt(row.rawLabel),
                row.source, split, PayloadNormalizer.groupKey(row.rawText))).toList();
    }

    private static void validateSamples(List<Sample> rows, String expectedSplit) {
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.text() == null || row.text().isBlank()
                || !expectedSplit.equals(row.split()) || (row.label() != 0 && row.label() != 1))) {
            throw new IllegalArgumentException("Merged " + expectedSplit + " rows are empty or invalid.");
        }
    }

    private static void requireBothLabels(List<Sample> rows, String partition) {
        if (!hasBothLabels(rows)) {
            throw new IllegalArgumentException(partition + " must contain both labels; no fallback split is used.");
        }
    }

    private static boolean hasBothLabels(List<Sample> rows) {
        return rows.stream().map(Sample::label).collect(java.util.stream.Collectors.toSet())
                .containsAll(Set.of(0, 1));
    }

    private static Map<String, Integer> labelCounts(List<Sample> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("0", 0);
        counts.put("1", 0);
        for (Sample row : rows) counts.merge(Integer.toString(row.label()), 1, Integer::sum);
        return counts;
    }

    private static void assertDisjointGroups(List<Sample> first, List<Sample> second) {
        Set<String> groups = first.stream().map(Sample::groupId).collect(java.util.stream.Collectors.toSet());
        if (second.stream().map(Sample::groupId).anyMatch(groups::contains)) {
            throw new IllegalArgumentException("Normalized payload groups overlap across experiment partitions.");
        }
    }

    private static void populateTraining(Connection connection, List<Sample> rows) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE DataTable(id INTEGER PRIMARY KEY,raw_sql TEXT,label INTEGER,"
                    + "source TEXT,split_group TEXT,batch_seq INTEGER)");
        }
        try (var insert = connection.prepareStatement("INSERT INTO DataTable VALUES(?,?,?,?, 'train',?)")) {
            int id = 0;
            for (Sample row : rows) {
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

    private static int countTrainingRows(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM DataTable WHERE split_group='train'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String fingerprint(List<Sample> rows) throws Exception {
        List<Sample> ordered = rows.stream().sorted(Comparator.comparing(Sample::text)
                .thenComparing(Sample::source).thenComparingInt(Sample::label)).toList();
        byte[] serialized = new ObjectMapper().writeValueAsBytes(ordered);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized));
    }

    private static void writeReport(Path path, Map<String, Object> report) throws Exception {
        Files.createDirectories(path.getParent());
        byte[] content = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
        Files.write(path, content, java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    public static List<Map<String, Object>> gridDescription() {
        return GRID.stream().map(point -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", point.id());
            row.put("ensembleSize", point.ensembleSize());
            row.put("standardMinSupport", ModelStateService.TrainingSettings.defaults().standardMinSupport());
            row.put("standardMinConfidence", point.standardMinConfidence());
            row.put("extendedMinSupport", ModelStateService.TrainingSettings.defaults().extendedMinSupport());
            row.put("extendedMinConfidence", point.extendedMinConfidence());
            row.put("extendedMinLift", ModelStateService.TrainingSettings.defaults().extendedMinLift());
            row.put("extendedMaxLength", point.extendedMaxLength());
            row.put("maxRulesPerFamily",
                    ModelStateService.TrainingSettings.defaults().extendedMaxRulesPerFamily());
            return row;
        }).toList();
    }

    private static boolean hasSource(String sources, String expected) {
        return sources != null && List.of(sources.split("\\|")).contains(expected);
    }

    private static boolean hasOtherSource(String sources, String heldOutSource) {
        return sources != null && java.util.Arrays.stream(sources.split("\\|"))
                .anyMatch(source -> !source.equals(heldOutSource));
    }

    private static String sourceProtocolName(String heldOutSource) {
        String otherSources = String.join("+", SOURCES.stream()
                .filter(source -> !source.equals(heldOutSource)).toList());
        return safeFileName(otherSources) + "-to-" + safeFileName(heldOutSource);
    }

    private static String safeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record Trial(
            GridPoint point, ExperimentModel model, ValidationThreshold.Choice threshold) {}
}
