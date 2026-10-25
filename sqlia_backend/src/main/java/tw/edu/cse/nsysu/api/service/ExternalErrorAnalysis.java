package tw.edu.cse.nsysu.api.service;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import tw.edu.cse.nsysu.ExtendedRuleMiner;
import tw.edu.cse.nsysu.FeatureExtract;

/** Post-evaluation diagnostics only; never changes frozen models or thresholds. */
public final class ExternalErrorAnalysis {
    private static final String[] FEATURES = {
        "payloadLength", "symbolDensity", "comparisonDensity",
        "complexity", "functionDensity", "discontinuousDensity"
    };

    private ExternalErrorAnalysis() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "Usage: ExternalErrorAnalysis <completed-run> <new-diagnostic-directory>");
        Path run = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectory(output);
        Path reportPath = output.resolve("error-analysis.json");
        Map<String, Object> report = GeneralizationAudit.reserve(reportPath, "sqlia-external-error-analysis-v1");
        try {
            Map<String, Object> manifest = GeneralizationAudit.object(run.resolve("frozen-manifest.json"));
            Map<String, Object> original = GeneralizationAudit.object(run.resolve("external-report.json"));
            if (!"complete".equals(original.get("status"))) {
                throw new IllegalArgumentException("Completed external evaluation required.");
            }
            Map<String, String> inputHashes = new LinkedHashMap<>();
            for (String name : List.of("external-report.json", "frozen-manifest.json",
                    "development-report.json", "external-valid.csv")) {
                inputHashes.put(run.resolve(name).toString(), GeneralizationAudit.sha(run.resolve(name)));
            }
            if (!inputHashes.get(run.resolve("development-report.json").toString())
                    .equals(manifest.get("developmentReportSha256"))) {
                throw new IllegalStateException("Development report hash mismatch.");
            }
            Map<String, Object> development = GeneralizationAudit.object(run.resolve("development-report.json"));
            Path training = Path.of("data/processed/train.csv").toAbsolutePath().normalize();
            Path validation = Path.of("data/processed/validation.csv").toAbsolutePath().normalize();
            for (var entry : Map.of(training, "mergedTrainSha256",
                    validation, "mergedValidationSha256").entrySet()) {
                String hash = GeneralizationAudit.sha(entry.getKey());
                if (!hash.equals(development.get(entry.getValue()))) {
                    throw new IllegalStateException("Old-data hash mismatch: " + entry.getKey());
                }
                inputHashes.put(entry.getKey().toString(), hash);
            }
            report.put("createdAt", Instant.now().toString());
            report.put("externalReportSha256", GeneralizationAudit.sha(run.resolve("external-report.json")));
            report.put("usage", "External labels now used for development diagnosis, not untouched future blind evidence.");
            report.put("policy", Map.of("retrained", false, "thresholdsRetuned", false,
                    "originalReportOverwritten", false, "sampleSeed", 20261025,
                    "manualReview", "Stratified random examples for human review, not automatic relabeling.",
                    "ruleAttribution", "Overlapping observational coverage; not causal contributions or feature importance."));
            List<GeneralizationRunner.Sample> external =
                    GeneralizationRunner.readSamples(run.resolve("external-valid.csv"), null);
            if (!GeneralizationAudit.sha(run.resolve("external-valid.csv"))
                    .equals(manifest.get("externalExportSha256"))) {
                throw new IllegalStateException("External export hash mismatch.");
            }
            List<Map<String, Object>> models = new ArrayList<>();
            for (Object value : (List<?>) manifest.get("models")) {
                Map<?, ?> frozen = (Map<?, ?>) value;
                String architecture = (String) frozen.get("architecture");
                if (!GeneralizationRunner.FOCUS.contains(architecture)) continue;
                Path checkpoint = run.resolve((String) frozen.get("checkpoint")).normalize();
                if (!checkpoint.startsWith(run)
                        || !GeneralizationAudit.sha(checkpoint).equals(frozen.get("checkpointSha256"))) {
                    throw new IllegalStateException("Checkpoint path/hash mismatch.");
                }
                inputHashes.put(checkpoint.toString(), (String) frozen.get("checkpointSha256"));
                ModelStateService state = ModelStateService.loadFrozen(checkpoint);
                double threshold = ((Number) frozen.get("threshold")).doubleValue();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("architecture", architecture);
                result.put("threshold", threshold);
                result.put("checkpointSha256", frozen.get("checkpointSha256"));
                Map<String, Object> externalAnalysis = analyze(state, external, architecture, threshold, true);
                verifyOutcomes(architecture, externalAnalysis, original);
                result.put("external", externalAnalysis);
                result.put("oldTraining", analyze(state, GeneralizationRunner.readSamples(
                        training, "train"), architecture, threshold, false));
                result.put("oldValidation", analyze(state, GeneralizationRunner.readSamples(
                        validation, "validation"), architecture, threshold, false));
                models.add(result);
            }
            Set<String> architectures = new HashSet<>();
            for (var model : models) architectures.add((String) model.get("architecture"));
            if (models.size() != GeneralizationRunner.FOCUS.size()
                    || !architectures.equals(new HashSet<>(GeneralizationRunner.FOCUS))) {
                throw new IllegalStateException("Expected exactly the two focused architectures.");
            }
            for (var entry : inputHashes.entrySet()) {
                if (!GeneralizationAudit.sha(Path.of(entry.getKey())).equals(entry.getValue())) {
                    throw new IllegalStateException("Input changed during diagnosis: " + entry.getKey());
                }
            }
            report.put("models", models);
            report.put("verifiedInputSha256", inputHashes);
            report.put("externalConfusionCountsMatchOriginal", true);
            report.put("status", "complete");
            GeneralizationAudit.finish(reportPath, report);
            System.out.println("Saved post-evaluation diagnosis: " + reportPath);
        } catch (Exception failure) {
            GeneralizationAudit.fail(reportPath, report, failure);
            throw failure;
        }
    }

    static void verifyOutcomes(String architecture, Map<String, Object> analysis,
            Map<String, Object> original) {
        Map<?, ?> metrics = null;
        for (Object value : (List<?>) original.get("models")) {
            Map<?, ?> model = (Map<?, ?>) value;
            if (!architecture.equals(model.get("architecture"))) continue;
            if (metrics != null) throw new IllegalStateException("Duplicate original architecture: " + architecture);
            metrics = (Map<?, ?>) model.get("metrics");
        }
        if (metrics == null) throw new IllegalStateException("Missing original architecture: " + architecture);
        Map<?, ?> outcomes = (Map<?, ?>) analysis.get("outcomes");
        for (String outcome : List.of("TP", "FP", "TN", "FN")) {
            Number actual = (Number) outcomes.get(outcome);
            Number expected = (Number) metrics.get(outcome.toLowerCase(Locale.ROOT));
            if (expected == null || (actual == null ? 0 : actual.longValue()) != expected.longValue()) {
                throw new IllegalStateException("Frozen rescore mismatch: " + architecture + " " + outcome);
            }
        }
        if (((Number) analysis.get("total")).longValue() != ((Number) metrics.get("total")).longValue()) {
            throw new IllegalStateException("Frozen rescore total mismatch: " + architecture);
        }
    }

    static Map<String, Object> analyze(ModelStateService state,
            List<GeneralizationRunner.Sample> rows, String architecture,
            double threshold, boolean includeSamples) throws Exception {
        var rules = state.getExtendedRules();
        long[][] counts = new long[rules.size()][6];
        Map<String, List<double[]>> features = new TreeMap<>();
        Map<String, List<Map<String, Object>>> examples = new TreeMap<>();
        Map<String, Integer> outcomes = new TreeMap<>();
        int[] negativeMatch = new int[2];
        int[] positiveMatch = new int[2];
        for (var row : rows) {
            double score = state.predictArchitectureProb(row.text(), architecture)[1];
            boolean predicted = score >= threshold;
            String outcome = row.label() == 1 ? (predicted ? "TP" : "FN") : (predicted ? "FP" : "TN");
            outcomes.merge(outcome, 1, Integer::sum);
            double[] base = FeatureExtract.extract(row.text());
            features.computeIfAbsent(outcome, ignored -> new ArrayList<>()).add(base);
            features.computeIfAbsent(row.label() == 0 ? "allBenign" : "allMalicious",
                    ignored -> new ArrayList<>()).add(base);
            List<Integer> tokens = ExtendedRuleMiner.tokenize(row.text());
            Set<Integer> items = ExtendedRuleMiner.items(row.text(), tokens,
                    ExtendedRuleMiner.ItemMode.BASE_TOKEN_NEG);
            List<Integer> matchedIds = new ArrayList<>();
            boolean negative = false, positive = false;
            for (int i = 0; i < rules.size(); i++) {
                var rule = rules.get(i);
                if (!rule.matches(items, tokens)) continue;
                matchedIds.add(i);
                int column = row.label() == 0 ? 0 : 1;
                counts[i][column]++;
                if (outcome.equals("FP")) counts[i][2]++;
                if (outcome.equals("TP")) counts[i][3]++;
                if (rule.confidence >= threshold && row.label() == 0) counts[i][4]++;
                if (rule.confidence >= threshold && row.label() == 1) counts[i][5]++;
                if (rule.hasNegativeItem()) negative = true;
                else positive = true;
            }
            if (negative) negativeMatch[row.label()]++;
            if (positive) positiveMatch[row.label()]++;
            if (includeSamples) {
                Map<String, Object> example = new LinkedHashMap<>();
                example.put("payload", row.text());
                example.put("label", row.label());
                example.put("score", score);
                example.put("matchedRuleIds", matchedIds);
                example.put("templateSha256", GeneralizationAudit.sha(
                        row.group().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                examples.computeIfAbsent(outcome, ignored -> new ArrayList<>()).add(example);
            }
        }
        List<Map<String, Object>> attribution = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> rule = new LinkedHashMap<>(ExtendedRuleMiner.toMap(rules.get(i)));
            rule.put("ruleId", i);
            rule.put("matchedBenign", counts[i][0]);
            rule.put("matchedMalicious", counts[i][1]);
            rule.put("matchedFalsePositives", counts[i][2]);
            rule.put("matchedTruePositives", counts[i][3]);
            rule.put("ruleConfidenceAboveDecisionThresholdBenign", counts[i][4]);
            rule.put("ruleConfidenceAboveDecisionThresholdMalicious", counts[i][5]);
            rule.put("matchedMaliciousFraction", counts[i][0] + counts[i][1] == 0 ? null :
                    (double) counts[i][1] / (counts[i][0] + counts[i][1]));
            attribution.add(rule);
        }
        attribution.sort(Comparator.comparingLong(
                (Map<String, Object> rule) -> ((Number) rule.get("matchedFalsePositives")).longValue()).reversed());
        Map<String, Object> distributions = new TreeMap<>();
        for (var entry : features.entrySet()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            for (int i = 0; i < FEATURES.length; i++) {
                final int index = i;
                summary.put(FEATURES[i], summarize(entry.getValue().stream().mapToDouble(x -> x[index]).toArray()));
            }
            distributions.put(entry.getKey(), summary);
        }
        Map<String, Object> samples = new TreeMap<>();
        for (var entry : examples.entrySet()) {
            Collections.shuffle(entry.getValue(), new Random(20261025L));
            samples.put(entry.getKey(), List.copyOf(entry.getValue().subList(0, Math.min(12, entry.getValue().size()))));
        }
        return Map.of("total", rows.size(), "outcomes", outcomes, "rules", attribution,
                "featureDistributions", distributions, "reviewSamples", samples,
                "anyNegativeRuleMatch", Map.of("benign", negativeMatch[0], "malicious", negativeMatch[1]),
                "anyNonNegativeRuleMatch", Map.of("benign", positiveMatch[0], "malicious", positiveMatch[1]));
    }

    static Map<String, Object> summarize(double[] values) {
        if (values.length == 0) throw new IllegalArgumentException("Nonempty feature values required.");
        Arrays.sort(values);
        return Map.of("count", values.length, "mean", Arrays.stream(values).average().orElseThrow(),
                "p10", quantile(values, .1), "median", quantile(values, .5),
                "p90", quantile(values, .9));
    }

    private static double quantile(double[] sorted, double q) {
        double position = (sorted.length - 1) * q;
        int lo = (int) position, hi = Math.min(lo + 1, sorted.length - 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (position - lo);
    }
}
