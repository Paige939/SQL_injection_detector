package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ResearchArtifactVerifierTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void verifiesTwentyDeclaredSyntheticCheckpointsWithoutHeldoutInputs() throws Exception {
        Path root = temporary.newFolder("research").toPath();
        Files.createDirectories(root.resolve("artifacts/merged"));
        Path sourceCheckpoint = root.resolve("fixture.ser");
        var bundle = new ModelStateService.FrozenModel(
                FrozenCheckpointTest.classifier(10),
                FrozenCheckpointTest.classifier(6),
                FrozenCheckpointTest.classifier(7),
                new tw.edu.cse.nsysu.FeatureForML(),
                new tw.edu.cse.nsysu.ArmNormalProfile(),
                List.of(), 2);
        try (var output = new ObjectOutputStream(Files.newOutputStream(sourceCheckpoint))) {
            output.writeObject(bundle);
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> gridPoint = Map.of(
                "id", "baseline", "ensembleSize", 15, "standardMinSupport", 0.05,
                "standardMinConfidence", 0.6, "extendedMinSupport", 0.1,
                "extendedMinConfidence", 0.6, "extendedMinLift", 1.5,
                "extendedMaxLength", 3, "maxRulesPerFamily", 25);
        List<Map<String, Object>> grid = List.of(gridPoint);
        Map<String, Object> mergedArchitectures = architectureEntries(root, sourceCheckpoint,
                "merged-validation", mapper);
        List<Map<String, Object>> crossDataset = new ArrayList<>();
        for (String protocol : List.of("A+B->C", "A+C->B", "B+C->A")) {
            crossDataset.add(Map.of(
                    "protocol", protocol,
                    "status", "complete",
                    "architectures", architectureEntries(root, sourceCheckpoint, protocol, mapper)));
        }
        Map<String, Object> researchReport = Map.of(
                "schemaVersion", "sqlia-research-results-v1",
                "status", "complete",
                "grid", grid,
                "mergedValidationTuning", Map.of("architectures", mergedArchitectures),
                "crossDataset", crossDataset);
        Files.write(root.resolve(ResearchExperimentService.REPORT_NAME),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(researchReport));
        Files.delete(sourceCheckpoint);

        Path verificationPath = ResearchArtifactVerifier.verify(root);
        JsonNode verification = mapper.readTree(verificationPath.toFile());
        assertEquals("complete", verification.path("status").asText());
        assertEquals(20, verification.path("declaredArtifactCount").asInt());
        assertEquals(20, verification.path("verifiedArtifactCount").asInt());
        assertEquals(20, verification.path("artifacts").size());
        assertTrue(verification.path("scope").path("syntheticRoundTripOnly").asBoolean());
        assertFalse(verification.path("scope").path("heldOutOrTestDataRead").asBoolean());
        assertEquals(0, verification.path("scope").path("heldOutOrTestPredictions").asInt());
        assertTrue(verification.path("researchReportSha256").asText().matches("[0-9a-f]{64}"));
        for (JsonNode artifact : verification.path("artifacts")) {
            assertTrue(artifact.path("verified").asBoolean());
            assertEquals(ResearchExperimentService.ARCHITECTURES.size()
                            * ResearchArtifactVerifier.SYNTHETIC_INPUTS.size(),
                    artifact.path("scoreComparisons").asInt());
            assertEquals(0.0, artifact.path("maxAbsoluteScoreDelta").asDouble(), 0);
            assertTrue(artifact.path("checkpointSha256").asText().matches("[0-9a-f]{64}"));
            assertTrue(artifact.path("roundTripSha256").asText().matches("[0-9a-f]{64}"));
        }
        assertFalse(Files.exists(root.resolve("fixture.ser")));
        assertFalse(Files.list(root).anyMatch(path -> path.getFileName().toString()
                .startsWith(".artifact-verification-")));
    }

    private static Map<String, Object> architectureEntries(
            Path root, Path sourceCheckpoint, String protocol, ObjectMapper mapper) throws Exception {
        Map<String, Object> architectures = new LinkedHashMap<>();
        for (String architecture : ResearchExperimentService.ARCHITECTURES) {
            String relative = protocol.equals("merged-validation")
                    ? "artifacts/merged/" + architecture + ".ser"
                    : "artifacts/" + protocol.replace("->", "-to-").replace("+", "_")
                            + "/" + architecture + ".ser";
            Path destination = root.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(sourceCheckpoint, destination);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("artifact", relative);
            entry.put("selectedConfiguration", "baseline");
            entry.put("trainingFingerprint", "training-fixture");
            entry.put("validationFingerprint", "validation-fixture");
            if (!protocol.equals("merged-validation")) entry.put("heldOut", Map.of("f1", 0.5));
            architectures.put(architecture, entry);
        }
        return architectures;
    }
}
