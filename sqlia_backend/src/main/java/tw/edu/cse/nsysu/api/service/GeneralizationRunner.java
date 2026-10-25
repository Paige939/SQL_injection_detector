package tw.edu.cse.nsysu.api.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.apache.commons.csv.*;
import tw.edu.cse.nsysu.ExtendedRuleMiner;
import tw.edu.cse.nsysu.preprocess.PayloadNormalizer;

/** Three explicit stages; development never opens or scores the external export. */
public final class GeneralizationRunner {
    static final List<String> FOCUS = List.of("PURE_EXTENDED_ARM", "EXTENDED_ARM");
    static final List<String> ARCHITECTURES = FrozenArchitectureComparisonService.ARCHITECTURES;
    static final List<Long> SEEDS = List.of(20261025L, 20261026L);
    static final List<String> POLICIES = List.of("baseline_same_training", "disjoint_A_rules_B_classifier",
            "sample_size_control_B_rules_B_classifier");
    static final int DEFAULT_REPLICATES = 200;
    record Sample(String text, int label, String source, String group) {}
    record Trial(String id, String policy, long seed, Path checkpoint, Map<String, Object> results,
            List<Sample> ruleRows, List<Sample> classifierRows) {}

    private GeneralizationRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException(                        "Usage: GeneralizationRunner audit|develop|evaluate <run-directory> [bootstrap-replicates:200..2000]; "
                    + "or learning-curve <new-output-directory> [completed-old-development-directory]");
        Path root = Path.of("").toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (args[0].equals("learning-curve")) {
            learningCurve(root, output, args.length == 3 ? Path.of(args[2]).toAbsolutePath().normalize() : null);
            return;
        }
        int replicates = args.length == 3 ? Integer.parseInt(args[2]) : DEFAULT_REPLICATES;
        if (replicates < 200 || replicates > 2000) throw new IllegalArgumentException("Bootstrap replicates must be 200..2000.");
        switch (args[0]) {
            case "audit" -> GeneralizationAudit.run(root, output);
            case "develop" -> develop(root, output, replicates);
            case "evaluate" -> evaluate(root, output);
            default -> throw new IllegalArgumentException("Unknown stage; expected audit, develop, or evaluate.");
        }
    }

    static List<Sample> readSamples(Path file, String expectedSplit) throws Exception {
        List<Sample> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                        .setIgnoreEmptyLines(false).get().parse(reader)) {
            for (CSVRecord record : parser) {
                if (!record.isConsistent()) throw new IllegalArgumentException("CSV width mismatch: " + file);
                String text = EvaluationDatasetSupport.requireSql(record);
                int label = EvaluationDatasetSupport.requireBinaryLabel(record);
                if (expectedSplit != null && (!record.isMapped("split")
                        || !expectedSplit.equals(record.get("split"))))
                    throw new IllegalArgumentException("Unexpected partition in " + file);
                if (!seen.add(text)) throw new IllegalArgumentException("Duplicate exact payload in " + file);
                result.add(new Sample(text, label, record.get("source"),
                        GeneralizationAudit.templateKey(text)));
            }
        }
        requireBoth(result, file.toString());
        return result;
    }

    static void develop(Path root, Path output, int replicates) throws Exception {
        Map<String, Object> audit = verifiedAudit(root, output);
        Path reportPath = output.resolve("development-report.json");
        Map<String, Object> report = GeneralizationAudit.reserve(reportPath, "sqlia-generalization-development-v1");
        try {
            Map<String, Object> protocol = new LinkedHashMap<>();
            protocol.put("seeds", SEEDS);
            protocol.put("architectures", ARCHITECTURES);
            protocol.put("policiesInFrozenTieOrder", POLICIES);
            protocol.put("selection", "Old merged Validation maximum F1, then minimum FPR, then baseline/disjoint seed order; "
                    + "sample-size control never a final candidate. Thresholds selected on old Validation only. "
                    + "Pure/Hybrid compare policies; other three prespecified first-seed same-training baseline, no policy search.");
            protocol.put("disjoint", "Deterministic SHA-256(template,seed) parity, A mines BOTH standard and extended rules; "
                    + "B fits classifier and benign profile against fixed A rule schema; no per-batch remine.");
            protocol.put("sameTrainingInterpretation", "Supervised rule selection and classifier fitting on the same old Training "
                    + "is a baseline training strategy, not automatically leakage.");
            protocol.put("sampleSizeConfound", "Disjoint strategy has fewer rule-mining and classifier examples. "
                    + "B-rules/B-classifier control uses exactly the same B rows and order as disjoint classifier training. "
                    + "A and B group composition may still differ; these diagnostics do not identify causal benefits.");
            protocol.put("bootstrapReplicates", replicates);
            protocol.put("bootstrapSeedPolicy", "Per-trial training seed for development CIs; external manifest fixes 20261025.");
            protocol.put("externalWhollyWithheld", true);
            protocol.put("oldFinalTestRead", false);
            protocol.put("sourceHoldouts", "Three old-source holdouts; eligible evaluation provenance exclusively held source; "
                    + "exclude all template overlap with non-held source; thresholds use internal old-source validation only.");
            protocol.put("learningCurve", "Not run: bounded two-seed policy comparison and three source holdouts prioritized.");
            report.put("protocol", protocol);
            Files.write(output.resolve("development-protocol.json"),
                    GeneralizationAudit.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(protocol),
                    StandardOpenOption.CREATE_NEW);
            List<Sample> train = readSamples(root.resolve("data/processed/train.csv"), "train");
            List<Sample> validation = readSamples(root.resolve("data/processed/validation.csv"), "validation");
            assertNormalizedDisjoint(train, validation);
            report.put("mergedTrainSha256", GeneralizationAudit.sha(root.resolve("data/processed/train.csv")));
            report.put("mergedValidationSha256", GeneralizationAudit.sha(root.resolve("data/processed/validation.csv")));
            report.put("templateGroupsSharedTrainValidation", sharedGroups(train, validation));
            report.put("mergedInterpretation", "Merged diagnostics retain existing partitions. "
                    + "Any shared heuristic templates with Validation are explicitly disclosed; not final blind evidence.");
            List<Trial> trials = new ArrayList<>();
            List<Map<String, Object>> trialReports = new ArrayList<>();
            Files.createDirectory(output.resolve("models"));
            for (long seed : SEEDS) {
                List<Sample> a = partition(train, seed, 2, 0), b = partition(train, seed, 2, 1);
                requireBoth(a, "A"); requireBoth(b, "B");
                for (String policy : POLICIES) {
                    List<Sample> rules = policy.equals(POLICIES.get(0)) ? train
                            : policy.equals(POLICIES.get(1)) ? a : b;
                    List<Sample> classifiers = policy.equals(POLICIES.get(0)) ? train : b;
                    Trial trial = trainTrial(policy + "-" + seed, policy, seed, rules, classifiers,
                            validation, output, replicates, true);
                    trials.add(trial);
                    trialReports.add(trialReport(trial, output));
                    report.put("trials", trialReports);
                    Files.write(reportPath, GeneralizationAudit.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
            }
            List<Map<String, Object>> selected = new ArrayList<>();
            for (String architecture : ARCHITECTURES) {
                Trial best = null;
                List<Trial> ordered = trials.stream().sorted(Comparator
                        .comparingInt((Trial trial) -> POLICIES.indexOf(trial.policy()))
                        .thenComparingLong(Trial::seed)).toList();
                for (Trial trial : ordered) {
                    if (trial.policy().equals(POLICIES.get(2))) continue;
                    if (!FOCUS.contains(architecture) && (!trial.policy().equals(POLICIES.get(0))
                            || trial.seed() != SEEDS.getFirst())) continue;
                    if (best == null || better(architectureResult(trial, architecture), architectureResult(best, architecture)))
                        best = trial;
                }
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("architecture", architecture);
                choice.put("trainingPolicy", best.policy()); choice.put("seed", best.seed());
                choice.put("checkpoint", output.relativize(best.checkpoint()).toString());
                choice.put("checkpointSha256", GeneralizationAudit.sha(best.checkpoint()));
                choice.put("threshold", architectureResult(best, architecture).get("threshold"));
                choice.put("validation", architectureResult(best, architecture));
                choice.put("trialId", best.id());
                selected.add(choice);
            }
            report.put("selectedModels", selected);
            List<Map<String, Object>> holdouts = sourceHoldouts(output, replicates);
            report.put("sourceHoldouts", holdouts);
            report.put("templateGroupHoldout", templateHoldout(train, validation, output, replicates));
            report.put("status", "complete");
            report.put("externalScored", false);
            report.put("oldFinalTestRead", false);
            GeneralizationAudit.finish(reportPath, report);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", "sqlia-frozen-external-manifest-v1");
            manifest.put("status", "frozen");
            manifest.put("runId", output.getFileName().toString());
            manifest.put("frozenAt", Instant.now().toString());
            manifest.put("bootstrapReplicates", replicates);
            manifest.put("bootstrapSeed", 20261025L);
            manifest.put("protocolSha256", GeneralizationAudit.sha(output.resolve("development-protocol.json")));
            manifest.put("developmentReportSha256", GeneralizationAudit.sha(reportPath));
            manifest.put("auditReportSha256", GeneralizationAudit.sha(output.resolve("audit-report.json")));
            manifest.put("externalFileSha256", ((Map<?, ?>) audit.get("external")).get("fileSha256"));
            manifest.put("externalExportSha256", ((Map<?, ?>) audit.get("exportSha256")).get("external-valid.csv"));
            manifest.put("blindTestEligible", false);
            manifest.put("classification", GeneralizationAudit.INDEPENDENCE);
            manifest.put("limitations", audit.get("limitations"));
            manifest.put("models", selected);
            manifest.put("policy", Map.of("retrained", false, "updated", false, "thresholdsRetuned", false));
            Files.write(output.resolve("frozen-manifest.json"),
                    GeneralizationAudit.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest),
                    StandardOpenOption.CREATE_NEW);
            System.out.println("[generalization] Development complete; manifest frozen. External NOT scored.");
        } catch (Exception failure) {
            GeneralizationAudit.fail(reportPath, report, failure); throw failure;
        }
    }

    private static Trial trainTrial(String id, String policy, long seed, List<Sample> ruleRows,
            List<Sample> classifierRows, List<Sample> validation, Path output, int replicates,
            boolean intervals) throws Exception {
        return trainTrial(id, policy, seed, ruleRows, classifierRows, validation, output,
                replicates, intervals, ExtendedRulePolicy.BASELINE, false);
    }

    static Trial trainTrial(String id, String policy, long seed, List<Sample> ruleRows,
            List<Sample> classifierRows, List<Sample> validation, Path output, int replicates,
            boolean intervals, ExtendedRulePolicy rulePolicy, boolean focusOnly) throws Exception {
        Path directory = output.resolve("models").resolve(id);
        Files.createDirectory(directory);
        ModelStateService.TrainingSettings defaults = ModelStateService.TrainingSettings.defaults();
        var settings = new ModelStateService.TrainingSettings(seed, defaults.ensembleSize(),
                seed + 42, seed + 84, seed + 126, defaults.standardMinSupport(), defaults.standardMinConfidence(),
                defaults.extendedMinSupport(), defaults.extendedMinConfidence(), defaults.extendedMinLift(),
                defaults.extendedMaxLength(), defaults.extendedMaxRulesPerFamily(), directory.resolve("mining"));
        try (Connection classifiers = trainingConnection(classifierRows);
                Connection rules = trainingConnection(ruleRows)) {
            System.out.println("[generalization] " + id + " ruleRows=" + ruleRows.size() + " classifierRows=" + classifierRows.size());
            ModelStateService state = new ModelStateService(classifiers, rules, settings, rulePolicy);
            if (state.getTotalTrained() != classifierRows.size())
                throw new IllegalStateException("Training row count differs from manifest.");
            Path checkpoint = directory.resolve("model.ser");
            state.saveFrozen(checkpoint);
            ModelStateService restored = ModelStateService.loadFrozen(checkpoint);
            Map<String, Object> architectures = new LinkedHashMap<>();
            List<String> scoredArchitectures = focusOnly || policy.equals("old_only_learning_curve") ? FOCUS
                    : policy.equals(POLICIES.get(0)) && seed == SEEDS.getFirst()
                    || !POLICIES.contains(policy) ? ARCHITECTURES : FOCUS;
            for (String architecture : scoredArchitectures) {
                List<GeneralizationMetrics.Scored> scores = new ArrayList<>();
                List<ValidationThreshold.Scored> thresholdScores = new ArrayList<>();
                for (Sample row : validation) {
                    double original = state.predictArchitectureProb(row.text(), architecture)[1];
                    double reload = restored.predictArchitectureProb(row.text(), architecture)[1];
                    if (Double.compare(original, reload) != 0)
                        throw new IllegalStateException("Checkpoint/schema reload mismatch " + id);
                    scores.add(scored(row, original));
                    thresholdScores.add(new ValidationThreshold.Scored(original, row.label()));
                }
                var threshold = ValidationThreshold.select(thresholdScores);
                Map<String, Object> result = intervals
                        ? GeneralizationMetrics.report(scores, threshold.threshold(), replicates, seed)
                        : new LinkedHashMap<>(Map.of("metrics", GeneralizationMetrics.metrics(scores, threshold.threshold())));
                result.put("threshold", threshold.threshold());
                result.put("validationF1", threshold.f1()); result.put("validationFpr", threshold.fpr());
                result.put("reloadVerifiedOnAllValidationRows", true);
                result.put("byLength", byLength(validation, scores, threshold.threshold()));
                result.put("bootstrapSeed", seed);
                architectures.put(architecture, result);
            }
            Map<String, Object> results = new LinkedHashMap<>();
            results.put("architectures", architectures);
            results.put("trainingSettings", settings.asMap());
            results.put("extendedRulePolicy", rulePolicy.name());
            results.put("standardRuleCount", state.getRuleFeatureCount());
            results.put("extendedRuleCount", state.getExtendedRules().size());
            results.put("extendedRules", state.getExtendedRules().stream().map(ExtendedRuleMiner::toMap).toList());
            results.put("ruleSchema", "Both standard FeatureForML and extended ordered rules embedded in model.ser; "
                    + "A schema fixed for B classifier; reload equality verified on every Validation score.");
            List<Sample> actualOrder = new ArrayList<>(classifierRows);
            Collections.shuffle(actualOrder, new Random(seed));
            results.put("actualTrainingOrderSha256", fingerprint(actualOrder));
            Files.write(directory.resolve("training-details.json"),
                    GeneralizationAudit.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(results),
                    StandardOpenOption.CREATE_NEW);
            return new Trial(id, policy, seed, checkpoint, results, ruleRows, classifierRows);
        }
    }

    private static Map<String, Object> trialReport(Trial trial, Path output) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>(trial.results());
        result.put("id", trial.id()); result.put("policy", trial.policy()); result.put("seed", trial.seed());
        result.put("checkpoint", output.relativize(trial.checkpoint()).toString());
        result.put("checkpointSha256", GeneralizationAudit.sha(trial.checkpoint()));
        result.put("ruleMiningRows", trial.ruleRows().size());
        result.put("classifierRows", trial.classifierRows().size());
        result.put("ruleMiningFingerprint", fingerprint(trial.ruleRows()));
        result.put("classifierFingerprint", fingerprint(trial.classifierRows()));
        result.put("ruleClassifierSharedTemplateGroups", sharedGroups(trial.ruleRows(), trial.classifierRows()));
        return result;
    }

    static void learningCurve(Path root, Path output, Path oldDevelopment) throws Exception {
        Files.createDirectories(output);
        Path reportPath = output.resolve("learning-curve.json");
        Map<String, Object> report = GeneralizationAudit.reserve(reportPath, "sqlia-old-only-learning-curve-v1");
        try {
            report.put("protocol", Map.of(
                    "seeds", SEEDS, "fractions", List.of(.25, .5, 1.0),
                    "architecture", FOCUS,
                    "selection", "NO model selection; post-freeze old-data-only diagnosis, never changes frozen model",
                    "subsets", "Nested deterministic SHA-256(template,seed) quartiles; whole heuristic template groups",
                    "thresholds", "Each size selects old Validation F1 then FPR; both train and Validation reported at that same threshold",
                    "training", "Re-mine both standard and extended schema at each size; fit all training rows; no website state",
                    "uncertainty", "No CIs for learning curves; six bounded real fits",
                    "calibration", "Brier score and 10 equal-width-bin ECE use raw malicious scores; not recalibrated"));
            report.put("externalRead", false);
            report.put("externalScored", false);
            report.put("oldFinalTestRead", false);
            report.put("frozenSelectionChanged", false);
            report.put("interpretation", "Post-external descriptive diagnostic, not evidence for post-external model selection. "
                    + "Training F1 is resubstitution and optimistic; Validation used for threshold selection. "
                    + "Heuristic groups are not semantic equivalence. No speculative classifier/feature changes.");
            List<Sample> train = readSamples(root.resolve("data/processed/train.csv"), "train");
            List<Sample> validation = readSamples(root.resolve("data/processed/validation.csv"), "validation");
            report.put("mergedTrainSha256", GeneralizationAudit.sha(root.resolve("data/processed/train.csv")));
            report.put("mergedValidationSha256", GeneralizationAudit.sha(root.resolve("data/processed/validation.csv")));
            report.put("fullTrainingRows", train.size());
            report.put("validationRows", validation.size());
            report.put("fullTrainValidationSharedTemplateGroups", sharedGroups(train, validation));
            if (oldDevelopment != null)
                report.put("frozenOldDevelopmentTrainValidationDiagnostics",
                        oldDevelopmentDiagnostics(oldDevelopment, train, validation));
            Files.createDirectory(output.resolve("models"));
            List<Map<String, Object>> curves = new ArrayList<>();
            for (long seed : SEEDS) for (int quarters : List.of(1, 2, 4)) {
                List<Sample> subset = new ArrayList<>();
                for (int bucket = 0; bucket < quarters; bucket++)
                    subset.addAll(partition(train, seed, 4, bucket));
                // Preserve original row order independently of group-bucket enumeration.
                Set<String> selected = new HashSet<>(); subset.forEach(row -> selected.add(row.text()));
                subset = train.stream().filter(row -> selected.contains(row.text())).toList();
                requireBoth(subset, "learning subset");
                Trial trial = trainTrial("learning-" + quarters + "quarters-" + seed, "old_only_learning_curve",
                        seed, subset, subset, validation, output, DEFAULT_REPLICATES, false);
                Map<String, Object> entry = trialReport(trial, output);
                entry.put("requestedFraction", quarters / 4.0);
                entry.put("actualFraction", (double) subset.size() / train.size());
                entry.put("trainValidationSharedTemplateGroups", sharedGroups(subset, validation));
                ModelStateService state = ModelStateService.loadFrozen(trial.checkpoint());
                Map<String, Object> evaluations = new LinkedHashMap<>();
                for (String architecture : FOCUS) {
                    double threshold = ((Number) architectureResult(trial, architecture).get("threshold")).doubleValue();
                    Map<String, Object> split = new LinkedHashMap<>();
                    for (var data : Map.of("train", subset, "validation", validation).entrySet()) {
                        List<GeneralizationMetrics.Scored> scored = score(state, data.getValue(), architecture);
                        split.put(data.getKey(), Map.of("metrics", GeneralizationMetrics.metrics(scored, threshold),
                                "scoreCalibration", calibration(scored)));
                    }
                    evaluations.put(architecture, split);
                }
                entry.put("trainAndValidation", evaluations);
                curves.add(entry);
                report.put("curves", curves);
                Files.write(reportPath, GeneralizationAudit.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
            report.put("status", "complete");
            GeneralizationAudit.finish(reportPath, report);
            System.out.println("[generalization] Separate old-only learning curves complete: " + reportPath);
        } catch (Exception failure) {
            GeneralizationAudit.fail(reportPath, report, failure); throw failure;
        }
    }

    static Map<String, Object> calibration(List<GeneralizationMetrics.Scored> rows) {
        double brier = 0, mean = 0;
        long positives = 0;
        long[] count = new long[10], positive = new long[10];
        double[] scores = new double[10];
        for (var row : rows) {
            brier += Math.pow(row.score() - row.label(), 2);
            mean += row.score(); positives += row.label();
            int bin = Math.min(9, (int) (row.score() * 10));
            count[bin]++; positive[bin] += row.label(); scores[bin] += row.score();
        }
        double ece = 0;
        List<Map<String, Object>> bins = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> bin = new LinkedHashMap<>();
            bin.put("lowerInclusive", i / 10.0); bin.put("upper", (i + 1) / 10.0);
            bin.put("count", count[i]); bin.put("positives", positive[i]);
            bin.put("meanScore", count[i] == 0 ? null : scores[i] / count[i]);
            bin.put("positiveFraction", count[i] == 0 ? null : (double) positive[i] / count[i]);
            if (count[i] > 0) ece += (double) count[i] / rows.size()
                    * Math.abs(scores[i] / count[i] - (double) positive[i] / count[i]);
            bins.add(bin);
        }
        return Map.of("brierScore", brier / rows.size(), "expectedCalibrationError10Bins", ece,
                "meanMaliciousScore", mean / rows.size(), "positivePrevalence", (double) positives / rows.size(),
                "bins", bins);
    }

    private static List<Map<String, Object>> oldDevelopmentDiagnostics(Path directory,
            List<Sample> train, List<Sample> validation) throws Exception {
        Map<String, Object> development = GeneralizationAudit.object(directory.resolve("development-report.json"));
        if (!"complete".equals(development.get("status")))
            throw new IllegalArgumentException("Old development must already be complete.");
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object entry : (List<?>) development.get("trials")) {
            Map<?, ?> trial = (Map<?, ?>) entry;
            long seed = ((Number) trial.get("seed")).longValue();
            String policy = (String) trial.get("policy");
            List<Sample> classifiers = policy.equals(POLICIES.getFirst()) ? train : partition(train, seed, 2, 1);
            if (!fingerprint(classifiers).equals(trial.get("classifierFingerprint")))
                throw new IllegalStateException("Old classifier fingerprint mismatch.");
            Path checkpoint = directory.resolve((String) trial.get("checkpoint")).normalize();
            if (!checkpoint.startsWith(directory)) throw new IllegalArgumentException("Checkpoint outside development.");
            checkHash(checkpoint, trial.get("checkpointSha256"));
            ModelStateService model = ModelStateService.loadFrozen(checkpoint);
            Map<String, Object> architectures = new LinkedHashMap<>();
            for (String architecture : FOCUS) {
                Map<?, ?> original = (Map<?, ?>) ((Map<?, ?>) trial.get("architectures")).get(architecture);
                double threshold = ((Number) original.get("threshold")).doubleValue();
                Map<String, Object> splits = new LinkedHashMap<>();
                for (var data : Map.of("train", classifiers, "validation", validation).entrySet()) {
                    List<GeneralizationMetrics.Scored> scores = score(model, data.getValue(), architecture);
                    splits.put(data.getKey(), Map.of("metrics", GeneralizationMetrics.metrics(scores, threshold),
                            "scoreCalibration", calibration(scores)));
                }
                architectures.put(architecture, Map.of("threshold", threshold, "splits", splits));
            }
            results.add(Map.of("id", trial.get("id"), "policy", policy, "seed", seed,
                    "classifierRows", classifiers.size(), "checkpointSha256", trial.get("checkpointSha256"),
                    "architectures", architectures, "retrained", false, "thresholdsRetuned", false));
        }
        return results;
    }

    private static List<Map<String, Object>> sourceHoldouts(Path output, int replicates) throws Exception {
        List<Sample> old = readSamples(output.resolve("old-valid.csv"), null);
        List<Map<String, Object>> reports = new ArrayList<>();
        for (String source : List.of("Modified_SQL_Dataset", "sqli", "SQLiV3")) {
            List<Sample> nonHeld = old.stream().filter(row -> !List.of(row.source().split("\\|")).contains(source)).toList();
            Set<String> trainingGroups = new HashSet<>();
            nonHeld.forEach(row -> trainingGroups.add(row.group()));
            List<Sample> held = old.stream().filter(row -> row.source().equals(source)
                    && !trainingGroups.contains(row.group())).toList();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("heldOutSource", source); report.put("eligibleHeldOutRows", held.size());
            report.put("excludedSharedProvenanceOrTemplateRows", old.stream()
                    .filter(row -> List.of(row.source().split("\\|")).contains(source)).count() - held.size());
            List<Sample> train = partition(nonHeld, SEEDS.getFirst(), 5, 0, false);
            List<Sample> validation = partition(nonHeld, SEEDS.getFirst(), 5, 0);
            if (!both(train) || !both(validation) || !both(held)) {
                report.put("status", "blocked");
                report.put("reason", "Strict provenance/template separation leaves a partition without both classes; no fallback.");
            } else {
                Trial trial = trainTrial("source-holdout-" + source, "old_source_holdout_baseline",
                        SEEDS.getFirst(), train, train, validation, output, replicates, true);
                report.put("status", "complete");
                report.put("training", trialReport(trial, output));
                report.put("heldOutEvaluation", scoreCheckpoint(trial, held, replicates));
            }
            reports.add(report);
        }
        return reports;
    }

    private static Map<String, Object> templateHoldout(List<Sample> train, List<Sample> validation,
            Path output, int replicates) throws Exception {
        List<Sample> combined = new ArrayList<>(train); combined.addAll(validation);
        List<Sample> training = partition(combined, SEEDS.getFirst(), 5, 0, false);
        List<Sample> held = partition(combined, SEEDS.getFirst(), 5, 0);
        List<Sample> innerTrain = partition(training, SEEDS.get(1), 5, 0, false);
        List<Sample> innerValidation = partition(training, SEEDS.get(1), 5, 0);
        requireBoth(innerTrain, "template inner training"); requireBoth(innerValidation, "template inner validation");
        requireBoth(held, "template holdout");
        Trial trial = trainTrial("template-holdout", "template_group_holdout_baseline", SEEDS.getFirst(),
                innerTrain, innerTrain, innerValidation, output, replicates, true);
        return Map.of("status", "complete", "interpretation", "Old Train+Validation regrouped for diagnostic only; "
                        + "final old Test untouched; thresholds from nested inner validation",
                "training", trialReport(trial, output), "heldOutEvaluation", scoreCheckpoint(trial, held, replicates));
    }

    private static Map<String, Object> scoreCheckpoint(Trial trial, List<Sample> rows, int replicates) throws Exception {
        ModelStateService model = ModelStateService.loadFrozen(trial.checkpoint());
        Map<String, Object> results = new LinkedHashMap<>();
        for (String architecture : ARCHITECTURES) {
            double threshold = ((Number) architectureResult(trial, architecture).get("threshold")).doubleValue();
            List<GeneralizationMetrics.Scored> scores = score(model, rows, architecture);
            Map<String, Object> result = GeneralizationMetrics.report(scores, threshold, replicates, trial.seed());
            result.put("threshold", threshold);
            result.put("byLength", byLength(rows, scores, threshold));
            results.put(architecture, result);
        }
        return results;
    }

    static void evaluate(Path root, Path output) throws Exception {
        Path reportPath = output.resolve("external-report.json");
        Map<String, Object> report = GeneralizationAudit.reserve(reportPath, "sqlia-external-report-v1");
        report.put("reportType", "external_generalization");
        report.put("runId", output.getFileName().toString());
        report.put("evaluationMode", "frozen_external_checkpoint");
        report.put("blindTestEligible", false);
        report.put("limitations", GeneralizationAudit.LIMITATIONS);
        report.put("policy", Map.of("retrained", false, "updated", false, "thresholdsRetuned", false));
        report.put("provenance", Map.of("verifiedIndependentCollection", false));
        report.put("dataset", Map.of());
        report.put("models", List.of());
        Files.write(reportPath, GeneralizationAudit.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Map<String, Object> audit = verifiedAudit(root, output);
            report.put("limitations", audit.get("limitations"));
            Map<String, Object> manifest = GeneralizationAudit.object(output.resolve("frozen-manifest.json"));
            if (!"frozen".equals(manifest.get("status"))) throw new IllegalStateException("Manifest not frozen.");
            checkHash(output.resolve("audit-report.json"), manifest.get("auditReportSha256"));
            checkHash(output.resolve("development-report.json"), manifest.get("developmentReportSha256"));
            checkHash(output.resolve("development-protocol.json"), manifest.get("protocolSha256"));
            checkHash(output.resolve("external-valid.csv"), manifest.get("externalExportSha256"));
            checkHash(root.resolve("data/raw/clean_sql_dataset.csv"), manifest.get("externalFileSha256"));
            report.put("frozenManifestSha256", GeneralizationAudit.sha(output.resolve("frozen-manifest.json")));
            report.put("classification", GeneralizationAudit.INDEPENDENCE);
            report.put("provenance", audit.get("provenance"));
            Map<String, Object> dataset = new LinkedHashMap<>();
            dataset.put("source", "clean_sql_dataset");
            dataset.put("fileSha256", manifest.get("externalFileSha256"));
            for (String key : List.of("originalRows","evaluatedRows","benignCount","maliciousCount","excludedRows","exclusionCounts"))
                dataset.put(key, audit.get(key));
            dataset.put("auditReportSha256", manifest.get("auditReportSha256"));
            report.put("dataset", dataset);
            if (!"pass".equals(audit.get("qualityGate"))) {
                report.put("status", "blocked"); report.put("error", audit.get("qualityGate"));
                report.put("models", List.of()); GeneralizationAudit.finish(reportPath, report); return;
            }
            List<Sample> rows = readSamples(output.resolve("external-valid.csv"), null);
            int replicates = ((Number) manifest.get("bootstrapReplicates")).intValue();
            long seed = ((Number) manifest.get("bootstrapSeed")).longValue();
            List<Map<String, Object>> results = new ArrayList<>();
            for (Object entry : (List<?>) manifest.get("models")) {
                Map<?, ?> frozen = (Map<?, ?>) entry;
                Path checkpoint = output.resolve((String) frozen.get("checkpoint")).normalize();
                if (!checkpoint.startsWith(output)) throw new IllegalStateException("Checkpoint escapes run directory.");
                checkHash(checkpoint, frozen.get("checkpointSha256"));
                ModelStateService model = ModelStateService.loadFrozen(checkpoint);
                String architecture = (String) frozen.get("architecture");
                double threshold = ((Number) frozen.get("threshold")).doubleValue();
                List<GeneralizationMetrics.Scored> scores = score(model, rows, architecture);
                Map<String, Object> result = GeneralizationMetrics.report(scores, threshold, replicates, seed);
                result.put("architecture", architecture);
                result.put("checkpointSha256", frozen.get("checkpointSha256"));
                result.put("threshold", threshold);
                result.put("trainingPolicy", frozen.get("trainingPolicy"));
                result.put("byLength", byLength(rows, scores, threshold));
                results.add(result);
            }
            report.put("models", results); report.put("status", "complete");
            GeneralizationAudit.finish(reportPath, report);
            System.out.println("[generalization] External report complete: " + reportPath);
        } catch (Exception failure) {
            GeneralizationAudit.fail(reportPath, report, failure); throw failure;
        }
    }

    private static Map<String, Object> verifiedAudit(Path root, Path output) throws Exception {
        Map<String, Object> audit = GeneralizationAudit.object(output.resolve("audit-report.json"));
        if (!"complete".equals(audit.get("status"))) throw new IllegalStateException("Complete audit required.");
        for (Object source : (List<?>) audit.get("oldSources")) {
            Map<?, ?> file = (Map<?, ?>) source;
            checkHash(Path.of((String) file.get("path")), file.get("fileSha256"));
        }
        Map<?, ?> external = (Map<?, ?>) audit.get("external");
        checkHash(root.resolve("data/raw/clean_sql_dataset.csv"), external.get("fileSha256"));
        Map<?, ?> exports = (Map<?, ?>) audit.get("exportSha256");
        for (Object file : exports.keySet()) checkHash(output.resolve(file.toString()), exports.get(file));
        return audit;
    }

    static void checkHash(Path path, Object expected) throws Exception {
        if (!GeneralizationAudit.sha(path).equals(expected)) throw new IllegalStateException("Hash mismatch: " + path);
    }

    static List<GeneralizationMetrics.Scored> score(ModelStateService model, List<Sample> rows,
            String architecture) throws Exception {
        List<GeneralizationMetrics.Scored> scores = new ArrayList<>();
        for (Sample row : rows) scores.add(scored(row, model.predictArchitectureProb(row.text(), architecture)[1]));
        return scores;
    }
    private static GeneralizationMetrics.Scored scored(Sample row, double probability) {
        return new GeneralizationMetrics.Scored(probability, row.label(), row.source(), row.group());
    }

    static Map<String, Object> byLength(List<Sample> rows, List<GeneralizationMetrics.Scored> scores, double threshold) {
        Map<String, List<GeneralizationMetrics.Scored>> groups = new TreeMap<>();
        for (int i = 0; i < rows.size(); i++) {
            int length = rows.get(i).text().length();
            String group = length < 80 ? "characters_0_79" : length < 240 ? "characters_80_239" : "characters_240_plus";
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(scores.get(i));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        groups.forEach((group, data) -> result.put(group, GeneralizationMetrics.metrics(data, threshold)));
        return result;
    }

    private static boolean better(Map<String, Object> candidate, Map<String, Object> current) {
        double cf = ((Number) candidate.get("validationF1")).doubleValue();
        double bf = ((Number) current.get("validationF1")).doubleValue();
        return cf > bf || (cf == bf && ((Number) candidate.get("validationFpr")).doubleValue()
                < ((Number) current.get("validationFpr")).doubleValue());
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> architectureResult(Trial trial, String architecture) {
        return (Map<String, Object>) ((Map<?, ?>) trial.results().get("architectures")).get(architecture);
    }

    private static List<Sample> partition(List<Sample> rows, long seed, int modulus, int bucket) throws Exception {
        return partition(rows, seed, modulus, bucket, true);
    }
    private static List<Sample> partition(List<Sample> rows, long seed, int modulus, int bucket, boolean include) throws Exception {
        Map<String, Boolean> groups = new HashMap<>();
        List<Sample> selected = new ArrayList<>();
        for (Sample row : rows) {
            Boolean match = groups.get(row.group());
            if (match == null) {
                String hash = GeneralizationAudit.sha((seed + "\n" + row.group()).getBytes(StandardCharsets.UTF_8));
                match = Long.parseUnsignedLong(hash.substring(0, 8), 16) % modulus == bucket;
                groups.put(row.group(), match);
            }
            if (match == include) selected.add(row);
        }
        return selected;
    }
    private static long sharedGroups(List<Sample> a, List<Sample> b) {
        Set<String> groups = new HashSet<>(); a.forEach(row -> groups.add(row.group()));
        return b.stream().map(Sample::group).distinct().filter(groups::contains).count();
    }
    private static void assertNormalizedDisjoint(List<Sample> a, List<Sample> b) {
        Set<String> groups = new HashSet<>(); a.forEach(row -> groups.add(PayloadNormalizer.groupKey(row.text())));
        if (b.stream().anyMatch(row -> groups.contains(PayloadNormalizer.groupKey(row.text()))))
            throw new IllegalArgumentException("Old Training/Validation exact or normalized overlap.");
    }
    private static boolean both(List<Sample> rows) {
        return rows.stream().anyMatch(row -> row.label() == 0) && rows.stream().anyMatch(row -> row.label() == 1);
    }
    private static void requireBoth(List<Sample> rows, String partition) {
        if (!both(rows)) throw new IllegalArgumentException(partition + " requires both labels.");
    }
    private static String fingerprint(List<Sample> rows) throws Exception {
        return GeneralizationAudit.sha(GeneralizationAudit.JSON.writeValueAsBytes(rows));
    }
    private static Connection trainingConnection(List<Sample> rows) throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE DataTable(id INTEGER PRIMARY KEY,raw_sql TEXT,label INTEGER,"
                        + "source TEXT,split_group TEXT,batch_seq INTEGER)");
            }
            try (var insert = connection.prepareStatement("INSERT INTO DataTable VALUES(?,?,?,?, 'train',?)")) {
                int id = 0;
                for (Sample row : rows) {
                    insert.setInt(1, ++id); insert.setString(2, row.text()); insert.setInt(3, row.label());
                    insert.setString(4, row.source()); insert.setInt(5, id); insert.addBatch();
                }
                insert.executeBatch();
            }
            return connection;
        } catch (Exception failure) { connection.close(); throw failure; }
    }
}
