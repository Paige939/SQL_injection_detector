package tw.edu.cse.nsysu.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tw.edu.cse.nsysu.api.service.FrozenArchitectureComparisonService.Row;

/** Verifies serialized research artifacts on fixed synthetic inputs only. */
public final class ResearchArtifactVerifier {
    public static final String REPORT_FILE = "artifact_verification.json";
    public static final List<SyntheticInput> SYNTHETIC_INPUTS = List.of(
            new SyntheticInput("ordinary_text", "reviewer supplied harmless text"),
            new SyntheticInput("identifier", "verify_token_20261025"),
            new SyntheticInput("numeric", "731946"),
            new SyntheticInput("sql_like", "SELECT verify_col FROM verify_table WHERE verify_id = 731946"),
            new SyntheticInput("tautology_like", "verify_id = 1 OR verify_guard = 1"),
            new SyntheticInput("comment_like", "verify_value' -- verifier-only"),
            new SyntheticInput("url_encoded", "verify%27%20OR%201%3D1--"));

    public record SyntheticInput(String id, String text) {}

    private final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException(
                    "Usage: ResearchArtifactVerifier [research-experiment-directory]");
        }
        Path experimentDirectory = args.length == 0
                ? Path.of("data/experiments/research-20261025")
                : Path.of(args[0]);
        Path report = verify(experimentDirectory);
        System.out.println("Research artifact verification complete: " + report);
    }

    static Path verify(Path experimentDirectory) throws Exception {
        Path root = experimentDirectory.toAbsolutePath().normalize();
        Path sourceReport = root.resolve(ResearchExperimentService.REPORT_NAME);
        Path verificationReport = root.resolve(REPORT_FILE);
        if (Files.exists(verificationReport)) {
            throw new IllegalArgumentException(
                    "Verification report already exists; refusing to overwrite: " + verificationReport);
        }
        byte[] reportBytes = Files.readAllBytes(sourceReport);
        JsonNode research = new ObjectMapper().readTree(reportBytes);
        if (!research.path("schemaVersion").asText().equals("sqlia-research-results-v1")
                || !research.path("status").asText().startsWith("complete")) {
            throw new IOException("Research report is missing or incomplete.");
        }

        List<JsonNode> declared = declaredArtifacts(research);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "sqlia-research-artifact-verification-v1");
        result.put("status", "running");
        result.put("generatedAt", java.time.Instant.now().toString());
        result.put("researchReport", ResearchExperimentService.REPORT_NAME);
        result.put("researchReportSha256", sha256(reportBytes));
        result.put("declaredArtifactCount", declared.size());
        result.put("verifiedArtifactCount", 0);
        result.put("architectures", ResearchExperimentService.ARCHITECTURES);
        result.put("syntheticInputs", SYNTHETIC_INPUTS.stream()
                .map(input -> Map.of("id", input.id())).toList());
        result.put("scope", Map.of(
                "syntheticRoundTripOnly", true,
                "originalValidationRescored", false,
                "heldOutOrTestDataRead", false,
                "heldOutOrTestPredictions", 0,
                "researchReportModified", false,
                "checkpointsModified", false));
        result.put("trainingOrderProvenance", Map.of(
                "researchTrainingRowsRead", "data/processed/train.csv in file row order; inserted into DataTable with increasing id and batch_seq",
                "classifierWarmup", "SELECT ORDER BY batch_seq,id; then unique rows shuffled with seed 20261003 before online updates",
                "standardAndExtendedRuleMining", "SELECT ORDER BY id; preserves train.csv row order",
                "earlierFrozenComparison", "SELECT ORDER BY raw_sql,source,split_group before insertion; consequently its seeded permutation is not equivalent",
                "comparisonImplication", "Same seed and settings do not guarantee identical models when source row order differs"));

        Path work = Files.createTempDirectory(root, ".artifact-verification-");
        List<Map<String, Object>> artifacts = new ArrayList<>();
        try {
            int index = 0;
            for (JsonNode entry : declared) {
                artifacts.add(verifyOne(root, work, entry, research, ++index));
                result.put("verifiedArtifactCount", index);
            }
            result.put("artifacts", List.copyOf(artifacts));
            result.put("status", "complete");
            result.put("completedAt", java.time.Instant.now().toString());
            writeNew(verificationReport, result);
            return verificationReport;
        } catch (Exception failure) {
            result.put("artifacts", List.copyOf(artifacts));
            result.put("status", "failed");
            result.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            result.put("completedAt", java.time.Instant.now().toString());
            if (!Files.exists(verificationReport)) writeNew(verificationReport, result);
            throw failure;
        } finally {
            deleteTree(work);
        }
    }

    private static Map<String, Object> verifyOne(
            Path root, Path work, JsonNode entry, JsonNode research, int index) throws Exception {
        String artifactName = requiredText(entry, "artifact");
        Path checkpoint = root.resolve(artifactName).normalize();
        if (!checkpoint.startsWith(root) || !Files.isRegularFile(checkpoint)) {
            throw new IOException("Declared checkpoint is missing or outside the experiment directory: "
                    + artifactName);
        }
        String architecture = requiredText(entry, "architecture");
        if (!ResearchExperimentService.ARCHITECTURES.contains(architecture)) {
            throw new IOException("Unknown architecture in research report: " + architecture);
        }
        String selectedConfiguration = requiredText(entry, "selectedConfiguration");
        JsonNode configuration = gridPoint(research.path("grid"), selectedConfiguration);
        Map<String, Object> settings = selectedSettings(configuration);
        byte[] originalBytes = Files.readAllBytes(checkpoint);
        String checkpointHash = sha256(originalBytes);
        ModelStateService original = ModelStateService.loadFrozen(originalBytes);

        Path roundTripPath = work.resolve("round-trip-" + index + ".ser");
        original.saveFrozen(roundTripPath);
        byte[] roundTripBytes = Files.readAllBytes(roundTripPath);
        ModelStateService restored = ModelStateService.loadFrozen(roundTripBytes);
        double maxAbsoluteDelta = 0;
        int scoreComparisons = 0;
        Map<String, Object> architectureResults = new LinkedHashMap<>();
        for (String scoredArchitecture : ResearchExperimentService.ARCHITECTURES) {
            List<Map<String, Object>> inputResults = new ArrayList<>();
            for (SyntheticInput input : SYNTHETIC_INPUTS) {
                double before = original.predictArchitectureProb(input.text(), scoredArchitecture)[1];
                double after = restored.predictArchitectureProb(input.text(), scoredArchitecture)[1];
                requireFiniteProbability(before, architecture, scoredArchitecture, input.id(), "original");
                requireFiniteProbability(after, architecture, scoredArchitecture, input.id(), "round-trip");
                double delta = Math.abs(before - after);
                maxAbsoluteDelta = Math.max(maxAbsoluteDelta, delta);
                if (Double.compare(before, after) != 0) {
                    throw new IllegalStateException("Score mismatch for " + artifactName + ", "
                            + scoredArchitecture + ", synthetic input " + input.id() + ".");
                }
                inputResults.add(Map.of("inputId", input.id(), "score", before, "absoluteDelta", delta));
                scoreComparisons++;
            }
            architectureResults.put(scoredArchitecture, inputResults);
        }

        Map<String, Object> verified = new LinkedHashMap<>();
        verified.put("artifact", artifactName);
        verified.put("architecture", architecture);
        verified.put("protocol", entry.path("protocol").asText());
        verified.put("selectedConfiguration", selectedConfiguration);
        verified.put("selectedSettings", settings);
        verified.put("trainingFingerprint", entry.path("trainingFingerprint").asText(""));
        verified.put("validationFingerprint", entry.path("validationFingerprint").asText(""));
        verified.put("checkpointBytes", originalBytes.length);
        verified.put("checkpointSha256", checkpointHash);
        verified.put("roundTripBytes", roundTripBytes.length);
        verified.put("roundTripSha256", sha256(roundTripBytes));
        verified.put("scoreComparisons", scoreComparisons);
        verified.put("maxAbsoluteScoreDelta", maxAbsoluteDelta);
        verified.put("roundTripScoresByArchitecture", architectureResults);
        verified.put("verified", true);
        return verified;
    }

    private static List<JsonNode> declaredArtifacts(JsonNode research) throws IOException {
        List<JsonNode> entries = new ArrayList<>();
        JsonNode merged = research.path("mergedValidationTuning").path("architectures");
        appendArchitectures(entries, merged, "merged-validation");
        for (JsonNode protocol : research.path("crossDataset")) {
            if (!protocol.path("status").asText().equals("complete")) continue;
            String protocolName = requiredText(protocol, "protocol");
            appendArchitectures(entries, protocol.path("architectures"), protocolName);
        }
        if (entries.size() != 20) {
            throw new IOException("Expected exactly 20 completed research checkpoints; report declares "
                    + entries.size() + ".");
        }
        return entries;
    }

    private static void appendArchitectures(
            List<JsonNode> entries, JsonNode architectures, String protocol) throws IOException {
        if (!architectures.isObject()) throw new IOException("Missing architecture results for " + protocol);
        for (String architecture : ResearchExperimentService.ARCHITECTURES) {
            JsonNode entry = architectures.path(architecture);
            if (entry.isMissingNode()) throw new IOException("Missing " + architecture + " in " + protocol);
            if (entry.has("heldOut") && !protocol.equals("merged-validation")) {
                JsonNode object = entry.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) object).put("protocol", protocol);
                ((com.fasterxml.jackson.databind.node.ObjectNode) object).put("architecture", architecture);
                entries.add(object);
            } else if (protocol.equals("merged-validation")) {
                JsonNode object = entry.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) object).put("protocol", protocol);
                ((com.fasterxml.jackson.databind.node.ObjectNode) object).put("architecture", architecture);
                entries.add(object);
            } else {
                throw new IOException("Cross-dataset result lacks a held-out evaluation: " + protocol);
            }
        }
    }

    private static JsonNode gridPoint(JsonNode grid, String id) throws IOException {
        for (JsonNode point : grid) {
            if (point.path("id").asText().equals(id)) return point;
        }
        throw new IOException("Selected configuration is absent from report grid: " + id);
    }

    private static Map<String, Object> selectedSettings(JsonNode point) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "ensembleSize", "standardMinSupport",
                "standardMinConfidence", "extendedMinSupport", "extendedMinConfidence",
                "extendedMinLift", "extendedMaxLength", "maxRulesPerFamily")) {
            JsonNode value = point.path(field);
            if (!value.isMissingNode()) result.put(field, value.isNumber() ? value.numberValue() : value.asText());
        }
        return result;
    }

    private static void requireFiniteProbability(
            double score, String artifact, String architecture, String input, String phase) {
        if (!Double.isFinite(score) || score < 0 || score > 1) {
            throw new IllegalStateException("Invalid " + phase + " probability for artifact " + artifact
                    + ", architecture " + architecture + ", synthetic input " + input + ": " + score);
        }
    }

    private static String requiredText(JsonNode node, String name) throws IOException {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IOException("Missing required report field: " + name);
        }
        return value.asText();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void writeNew(Path path, Map<String, Object> result) throws IOException {
        byte[] bytes = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
        Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
