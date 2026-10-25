package tw.edu.cse.nsysu.api.service;

import java.nio.file.*;
import java.util.*;

/** Isolated development diagnostics; never promotes a model or reads old final Test. */
public final class RuleAblationRunner {
    private RuleAblationRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "Usage: RuleAblationRunner <completed-generalization-run> <new-output-directory>");
        run(Path.of("").toAbsolutePath().normalize(), Path.of(args[0]).toRealPath(),
                Path.of(args[1]).toAbsolutePath().normalize());
    }

    static void run(Path root, Path completed, Path output) throws Exception {
        if (output.startsWith(completed)) throw new IllegalArgumentException("Output must be outside the completed run.");
        Files.createDirectory(output);
        Path reportPath = output.resolve("ablation-report.json");
        Map<String, Object> report = GeneralizationAudit.reserve(reportPath, "sqlia-rule-ablation-v1");
        try {
            Map<String, Object> manifest = GeneralizationAudit.object(completed.resolve("frozen-manifest.json"));
            Map<String, Object> development = GeneralizationAudit.object(completed.resolve("development-report.json"));
            Map<String, Object> original = GeneralizationAudit.object(completed.resolve("external-report.json"));
            if (!"complete".equals(development.get("status")) || !"complete".equals(original.get("status"))) {
                throw new IllegalArgumentException("Completed development and external reports required.");
            }
            Path trainPath = root.resolve("data/processed/train.csv");
            Path validationPath = root.resolve("data/processed/validation.csv");
            Path externalPath = completed.resolve("external-valid.csv");
            Map<String, String> inputHashes = new LinkedHashMap<>();
            remember(inputHashes, completed.resolve("frozen-manifest.json"), original.get("frozenManifestSha256"));
            remember(inputHashes, completed.resolve("development-report.json"), manifest.get("developmentReportSha256"));
            remember(inputHashes, completed.resolve("external-report.json"));
            remember(inputHashes, trainPath, development.get("mergedTrainSha256"));
            remember(inputHashes, validationPath, development.get("mergedValidationSha256"));
            remember(inputHashes, externalPath, manifest.get("externalExportSha256"));
            for (Object value : (List<?>) manifest.get("models")) {
                Map<?, ?> model = (Map<?, ?>) value;
                Path checkpoint = completed.resolve((String) model.get("checkpoint")).normalize();
                if (!checkpoint.startsWith(completed)) throw new IllegalArgumentException("Checkpoint outside completed run.");
                remember(inputHashes, checkpoint, model.get("checkpointSha256"));
            }
            Map<String, Object> protocol = new LinkedHashMap<>();
            protocol.put("seeds", GeneralizationRunner.SEEDS);
            protocol.put("architectures", GeneralizationRunner.FOCUS);
            protocol.put("policies", Arrays.stream(ExtendedRulePolicy.values()).map(Enum::name).toList());
            protocol.put("candidateControl", "Filter the same ordered, capped mined schema before classifier fitting; "
                    + "do not refill removed rules or alter mining hyperparameters. Both stages use old Training.");
            protocol.put("sqlContext", "NO_NEGATIVE plus token 15 comparison-expression shape, or at least two distinct "
                    + "positive SQL tokens in {5,6,7,8,9,10,11,12,16,17,18,21,22,26,27,28}. "
                    + "This heuristic is not SQL parsing, proof of injection, or proof that a comparison is true.");
            protocol.put("thresholdSelection", "Maximum F1 then minimum FPR on old Validation only, using existing selector.");
            protocol.put("externalUsage", "Previously disclosed external labels; development diagnostics only, not blind evidence. "
                    + "All six models and thresholds saved before external scoring; no external-based winner selection.");
            protocol.put("limitations", List.of("Old Train/Validation share heuristic template groups.",
                    "Two seeds do not measure uncertainty over new data sources.",
                    "Post-selection filtering tests dependence on existing rules, not optimal constrained mining.",
                    "No new independent holdout or labeling audit; no claim of deployable improvement.",
                    "Zero retained rules are reported explicitly; Pure can become uninformative."));
            protocol.put("bootstrap", "200 heuristic-template cluster replicates, training seed; marginal 95% percentile intervals, not paired difference tests.");
            protocol.put("websiteModelPromoted", false);
            protocol.put("oldFinalTestRead", false);
            report.put("protocol", protocol);
            Files.write(output.resolve("protocol.json"),
                    GeneralizationAudit.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(protocol),
                    StandardOpenOption.CREATE_NEW);
            List<GeneralizationRunner.Sample> train = GeneralizationRunner.readSamples(trainPath, "train");
            List<GeneralizationRunner.Sample> validation = GeneralizationRunner.readSamples(validationPath, "validation");
            Set<String> trainingGroups = new HashSet<>();
            train.forEach(row -> trainingGroups.add(row.group()));
            report.put("sharedTrainValidationTemplateGroups",
                    validation.stream().map(GeneralizationRunner.Sample::group).distinct().filter(trainingGroups::contains).count());
            Files.createDirectory(output.resolve("models"));
            List<Map<String, Object>> trials = new ArrayList<>();
            report.put("trials", trials);
            for (long seed : GeneralizationRunner.SEEDS) {
                for (ExtendedRulePolicy policy : ExtendedRulePolicy.values()) {
                    String id = policy.name().toLowerCase(Locale.ROOT) + "-" + seed;
                    var trial = GeneralizationRunner.trainTrial(id, policy.name(), seed, train, train,
                            validation, output, 200, true, policy, true);
                    Map<String, Object> entry = new LinkedHashMap<>(trial.results());
                    entry.put("id", id);
                    entry.put("seed", seed);
                    entry.put("checkpoint", output.relativize(trial.checkpoint()).toString());
                    entry.put("checkpointSha256", GeneralizationAudit.sha(trial.checkpoint()));
                    entry.put("trainingRows", train.size());
                    entry.put("zeroExtendedRules", ((Number) entry.get("extendedRuleCount")).intValue() == 0);
                    if (policy == ExtendedRulePolicy.BASELINE) {
                        verifyBaseline(seed, entry, development);
                        entry.put("baselineValidationMatchesOriginalDevelopment", true);
                    }
                    trials.add(entry);
                    persist(reportPath, report);
                }
            }
            Map<String, Object> frozen = Map.of("schemaVersion", "sqlia-rule-ablation-frozen-v1",
                    "protocolSha256", GeneralizationAudit.sha(output.resolve("protocol.json")),
                    "verifiedInputSha256", inputHashes,
                    "trials", trials);
            Path frozenPath = output.resolve("ablation-frozen-manifest.json");
            Files.write(frozenPath, GeneralizationAudit.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(frozen),
                    StandardOpenOption.CREATE_NEW);
            String frozenHash = GeneralizationAudit.sha(frozenPath);
            report.put("frozenManifestSha256", frozenHash);
            report.put("phase", "external-development-diagnostics");
            persist(reportPath, report);
            List<GeneralizationRunner.Sample> external = GeneralizationRunner.readSamples(externalPath, null);
            for (Map<String, Object> trial : trials) {
                Path checkpoint = output.resolve((String) trial.get("checkpoint"));
                GeneralizationRunner.checkHash(checkpoint, trial.get("checkpointSha256"));
                ModelStateService state = ModelStateService.loadFrozen(checkpoint);
                Map<String, Object> diagnostics = new LinkedHashMap<>();
                for (String architecture : GeneralizationRunner.FOCUS) {
                    Map<?, ?> validationResult = (Map<?, ?>) ((Map<?, ?>) trial.get("architectures")).get(architecture);
                    double threshold = ((Number) validationResult.get("threshold")).doubleValue();
                    var scores = GeneralizationRunner.score(state, external, architecture);
                    Map<String, Object> result = GeneralizationMetrics.report(scores, threshold, 200,
                            ((Number) trial.get("seed")).longValue());
                    result.put("threshold", threshold);
                    result.put("byLength", GeneralizationRunner.byLength(external, scores, threshold));
                    if (ExtendedRulePolicy.BASELINE.name().equals(trial.get("extendedRulePolicy"))
                            && ((Number) trial.get("seed")).longValue() == GeneralizationRunner.SEEDS.getFirst()) {
                        verifyOriginalExternal(architecture, result, original);
                        result.put("matchesOriginalExternalCounts", true);
                    }
                    diagnostics.put(architecture, result);
                }
                trial.put("externalDevelopmentDiagnostics", diagnostics);
                persist(reportPath, report);
            }
            GeneralizationRunner.checkHash(frozenPath, frozenHash);
            for (var entry : inputHashes.entrySet()) GeneralizationRunner.checkHash(Path.of(entry.getKey()), entry.getValue());
            for (var trial : trials) GeneralizationRunner.checkHash(
                    output.resolve((String) trial.get("checkpoint")), trial.get("checkpointSha256"));
            report.put("verifiedInputSha256", inputHashes);
            report.put("phase", "complete");
            report.put("status", "complete");
            GeneralizationAudit.finish(reportPath, report);
            System.out.println("[rule-ablation] Complete; no model promoted: " + reportPath);
        } catch (Exception failure) {
            GeneralizationAudit.fail(reportPath, report, failure);
            throw failure;
        }
    }

    private static void remember(Map<String, String> hashes, Path path, Object expected) throws Exception {
        if (!(expected instanceof String hash) || hash.isBlank()) {
            throw new IllegalStateException("Missing required input hash: " + path);
        }
        GeneralizationRunner.checkHash(path, expected);
        remember(hashes, path);
    }

    private static void remember(Map<String, String> hashes, Path path) throws Exception {
        String hash = GeneralizationAudit.sha(path);
        hashes.put(path.toString(), hash);
    }

    private static void persist(Path path, Map<String, Object> report) throws Exception {
        Files.write(path, GeneralizationAudit.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    static void verifyBaseline(long seed, Map<String, Object> entry, Map<String, Object> development) {
        for (Object value : (List<?>) development.get("trials")) {
            Map<?, ?> original = (Map<?, ?>) value;
            if (((Number) original.get("seed")).longValue() != seed
                    || !"baseline_same_training".equals(original.get("policy"))) continue;
            for (String architecture : GeneralizationRunner.FOCUS) {
                Map<?, ?> actual = (Map<?, ?>) ((Map<?, ?>) entry.get("architectures")).get(architecture);
                Map<?, ?> expected = (Map<?, ?>) ((Map<?, ?>) original.get("architectures")).get(architecture);
                compareCountsAndThreshold(actual, expected);
            }
            return;
        }
        throw new IllegalStateException("Missing original baseline seed: " + seed);
    }

    private static void verifyOriginalExternal(String architecture, Map<String, Object> result,
            Map<String, Object> original) {
        for (Object value : (List<?>) original.get("models")) {
            Map<?, ?> model = (Map<?, ?>) value;
            if (architecture.equals(model.get("architecture"))) {
                compareCountsAndThreshold(result, model);
                return;
            }
        }
        throw new IllegalStateException("Missing original architecture: " + architecture);
    }

    static void compareCountsAndThreshold(Map<?, ?> actual, Map<?, ?> expected) {
        if (Double.compare(((Number) actual.get("threshold")).doubleValue(),
                ((Number) expected.get("threshold")).doubleValue()) != 0) {
            throw new IllegalStateException("Baseline threshold differs from original.");
        }
        Map<?, ?> a = (Map<?, ?>) actual.get("metrics"), b = (Map<?, ?>) expected.get("metrics");
        for (String count : List.of("total", "tp", "fp", "tn", "fn")) {
            if (((Number) a.get(count)).longValue() != ((Number) b.get(count)).longValue()) {
                throw new IllegalStateException("Baseline confusion count differs: " + count);
            }
        }
    }
}
