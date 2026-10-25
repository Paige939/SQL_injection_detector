package tw.edu.cse.nsysu.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Re-scores saved checkpoints into new derived reports, never retraining or retuning. */
public final class RankingEvaluationRunner {
    private RankingEvaluationRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
                "Usage: RankingEvaluationRunner <original-generalization-run> <original-ablation-run> "
                        + "<new-generalization-run> <new-ablation-run>");
        run(Path.of(args[0]).toRealPath(), Path.of(args[1]).toRealPath(),
                Path.of(args[2]).toAbsolutePath().normalize(), Path.of(args[3]).toAbsolutePath().normalize());
    }

    static void run(Path external, Path ablation, Path externalOutput, Path ablationOutput) throws Exception {
        for (Path output : List.of(externalOutput, ablationOutput)) {
            if (!output.getParent().equals(external.getParent()) || output.startsWith(external)
                    || output.startsWith(ablation) || Files.exists(output))
                throw new IllegalArgumentException("Outputs must be new sibling experiment directories.");
        }
        if (externalOutput.equals(ablationOutput)
                || !externalOutput.getFileName().toString().startsWith("generalization-")
                || !ablationOutput.getFileName().toString().startsWith("rule-ablation-"))
            throw new IllegalArgumentException("Distinct generalization-/rule-ablation- output IDs required.");
        var externalService = new ExternalReportService(external.getParent());
        var ablationService = new RuleAblationReportService(ablation.getParent());
        ObjectNode originalExternal = (ObjectNode) externalService.getReport(external.getFileName().toString());
        ObjectNode originalAblation = (ObjectNode) ablationService.getReport(ablation.getFileName().toString());
        if (!"complete".equals(originalExternal.path("status").asText())
                || !"complete".equals(originalAblation.path("status").asText()))
            throw new IllegalArgumentException("Complete input reports required.");
        Map<Path, String> hashes = new LinkedHashMap<>();
        Path manifestPath = external.resolve("frozen-manifest.json");
        remember(hashes, manifestPath, originalExternal.path("frozenManifestSha256").asText());
        remember(hashes, external.resolve("external-report.json"), null);
        remember(hashes, ablation.resolve("ablation-report.json"), null);
        Path ablationManifest = ablation.resolve("ablation-frozen-manifest.json");
        remember(hashes, ablationManifest, originalAblation.path("frozenManifestSha256").asText());
        JsonNode manifest = GeneralizationAudit.JSON.readTree(manifestPath.toFile());
        JsonNode frozenTrials = GeneralizationAudit.JSON.readTree(ablationManifest.toFile()).path("trials");
        Path export = external.resolve("external-valid.csv");
        remember(hashes, export, manifest.path("externalExportSha256").asText());
        String previousExportHash = originalAblation.path("verifiedInputSha256").path(export.toString()).asText();
        GeneralizationRunner.checkHash(export, previousExportHash);
        List<GeneralizationRunner.Sample> rows = GeneralizationRunner.readSamples(export, null);
        ObjectNode derivedExternal = derived(originalExternal, externalOutput, external, hashes);
        ObjectNode derivedAblation = derived(originalAblation, ablationOutput, ablation, hashes);
        Files.createDirectory(externalOutput);
        Files.createDirectory(ablationOutput);
        Path externalFile = externalOutput.resolve("external-report.json");
        Path ablationFile = ablationOutput.resolve("ablation-report.json");
        persist(externalFile, derivedExternal);
        persist(ablationFile, derivedAblation);
        try {
            for (JsonNode entry : manifest.path("models")) {
                String architecture = entry.path("architecture").asText();
                JsonNode result = find(originalExternal.path("models"), "architecture", architecture);
                if (!result.path("checkpointSha256").equals(entry.path("checkpointSha256"))
                        || !result.path("threshold").equals(entry.path("threshold")))
                    throw new IllegalStateException("Manifest/report model mismatch: " + architecture);
                Path checkpoint = checkpoint(external, entry, hashes);
                ModelStateService state = ModelStateService.loadFrozen(checkpoint);
                ObjectNode target = (ObjectNode) find(derivedExternal.path("models"), "architecture", architecture);
                enrich(target, state, rows, architecture, manifest.path("bootstrapReplicates").intValue(),
                        manifest.path("bootstrapSeed").longValue());
                persist(externalFile, derivedExternal);
            }
            for (JsonNode trial : derivedAblation.path("trials")) {
                JsonNode frozen = find(frozenTrials, "id", trial.path("id").asText());
                if (!trial.path("checkpointSha256").equals(frozen.path("checkpointSha256"))
                        || !trial.path("checkpoint").equals(frozen.path("checkpoint")))
                    throw new IllegalStateException("Ablation frozen checkpoint mismatch.");
                Path checkpoint = checkpoint(ablation, trial, hashes);
                ModelStateService state = ModelStateService.loadFrozen(checkpoint);
                for (String architecture : GeneralizationRunner.FOCUS) {
                    JsonNode validation = trial.path("architectures").path(architecture);
                    JsonNode frozenValidation = frozen.path("architectures").path(architecture);
                    ObjectNode target = (ObjectNode) trial.path("externalDevelopmentDiagnostics").path(architecture);
                    if (!validation.path("threshold").equals(frozenValidation.path("threshold"))
                            || !target.path("threshold").equals(validation.path("threshold")))
                        throw new IllegalStateException("Ablation frozen threshold mismatch.");
                    enrich(target, state, rows, architecture, 200, trial.path("seed").longValue());
                }
                persist(ablationFile, derivedAblation);
            }
            for (var entry : hashes.entrySet()) GeneralizationRunner.checkHash(entry.getKey(), entry.getValue());
            for (ObjectNode report : List.of(derivedExternal, derivedAblation)) {
                report.put("status", "complete");
                report.put("completedAt", Instant.now().toString());
                ObjectNode verified = report.withObject("/rankingDerivation").putObject("verifiedInputSha256");
                hashes.forEach((path, hash) -> verified.put(path.toString(), hash));
            }
            RuleAblationReportService.validate(derivedAblation, ablationOutput.getFileName().toString());
            persist(externalFile, derivedExternal);
            persist(ablationFile, derivedAblation);
            externalService.getReport(externalOutput.getFileName().toString());
            ablationService.getReport(ablationOutput.getFileName().toString());
            System.out.println("[ranking] Complete: " + externalFile + " and " + ablationFile);
        } catch (Exception failure) {
            for (ObjectNode report : List.of(derivedExternal, derivedAblation)) {
                report.put("status", "failed");
                report.put("error", failure.toString());
            }
            persist(externalFile, derivedExternal);
            persist(ablationFile, derivedAblation);
            throw failure;
        }
    }

    private static ObjectNode derived(ObjectNode original, Path output, Path input, Map<Path, String> hashes) {
        ObjectNode result = original.deepCopy();
        result.put("runId", output.getFileName().toString());
        result.put("status", "running");
        result.put("createdAt", Instant.now().toString());
        result.remove("completedAt");
        ObjectNode derivation = result.putObject("rankingDerivation");
        derivation.put("sourceRunId", input.getFileName().toString());
        derivation.put("sourceCreatedAt", original.path("createdAt").asText());
        derivation.put("checkpointPathsRelativeTo", input.toString());
        derivation.put("policy", "Derived re-scoring only; no retraining, updates, threshold tuning, or model deployment. "
                + "Historical reports unchanged. External labels already disclosed; not a new independent blind test. "
                + "ROC-AUC awards half credit to score ties; AP is not trapezoidal PR-AUC. "
                + "95% marginal template-cluster bootstrap intervals, not paired difference tests.");
        ObjectNode inputs = derivation.putObject("verifiedInputSha256");
        hashes.forEach((path, hash) -> inputs.put(path.toString(), hash));
        return result;
    }

    private static void enrich(ObjectNode target, ModelStateService state, List<GeneralizationRunner.Sample> rows,
            String architecture, int replicates, long seed) throws Exception {
        double threshold = target.path("threshold").doubleValue();
        var scores = GeneralizationRunner.score(state, rows, architecture);
        var metrics = GeneralizationAudit.JSON.valueToTree(GeneralizationMetrics.metrics(scores, threshold));
        for (String key : List.of("total", "tp", "fp", "tn", "fn", "accuracy", "precision",
                "recall", "f1", "fpr", "averagePrecision")) {
            JsonNode previous = target.path("metrics").path(key), current = metrics.path(key);
            if (previous.isNumber() && current.isNumber()
                    ? Math.abs(previous.doubleValue() - current.doubleValue()) > 1e-12
                    : !previous.equals(current))
                throw new IllegalStateException("Re-scoring changed historical metric: " + architecture + "/" + key);
        }
        ((ObjectNode) target.path("metrics")).set("rocAuc", metrics.path("rocAuc"));
        var intervals = GeneralizationAudit.JSON.valueToTree(
                GeneralizationMetrics.intervals(scores, threshold, replicates, seed));
        ((ObjectNode) target.path("confidenceIntervals")).set("rocAuc", intervals.path("rocAuc"));
        target.set("rankingCurves", GeneralizationAudit.JSON.valueToTree(GeneralizationMetrics.curves(scores, threshold)));
        RankingReportValidation.validate(target, architecture);
        System.out.println("[ranking] " + architecture + " ROC-AUC=" + metrics.path("rocAuc"));
    }

    private static JsonNode find(JsonNode entries, String field, String value) {
        JsonNode found = null;
        for (JsonNode entry : entries) if (entry.path(field).asText().equals(value)) {
            if (found != null) throw new IllegalStateException("Duplicate " + field + ": " + value);
            found = entry;
        }
        if (found == null) throw new IllegalStateException("Missing " + field + ": " + value);
        return found;
    }

    private static Path checkpoint(Path input, JsonNode entry, Map<Path, String> hashes) throws Exception {
        Path checkpoint = input.resolve(entry.path("checkpoint").asText()).toRealPath();
        if (!checkpoint.startsWith(input)) throw new IllegalArgumentException("Checkpoint escapes input run.");
        remember(hashes, checkpoint, entry.path("checkpointSha256").asText());
        return checkpoint;
    }

    private static void remember(Map<Path, String> hashes, Path path, String expected) throws Exception {
        if (expected != null) GeneralizationRunner.checkHash(path, expected);
        hashes.put(path, GeneralizationAudit.sha(path));
    }

    private static void persist(Path path, ObjectNode report) throws Exception {
        Files.write(path, GeneralizationAudit.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
    }
}
