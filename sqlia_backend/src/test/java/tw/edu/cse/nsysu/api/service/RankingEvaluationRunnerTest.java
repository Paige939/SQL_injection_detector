package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.Comparator;
import org.junit.Test;

public class RankingEvaluationRunnerTest {
    @Test public void refusesExistingNestedAndAmbiguousOutputsWithoutWritingInputs() throws Exception {
        Path root = Files.createTempDirectory(Path.of("target"), "ranking-runner-test-");
        try {
            Path external = Files.createDirectory(root.resolve("generalization-source"));
            Path ablation = Files.createDirectory(root.resolve("rule-ablation-source"));
            Path existing = Files.createDirectory(root.resolve("generalization-existing"));
            Path other = root.resolve("rule-ablation-new");
            Path sentinel = external.resolve("external-report.json");
            Files.writeString(sentinel, "historical bytes");
            assertThrows(IllegalArgumentException.class,
                    () -> RankingEvaluationRunner.run(external, ablation, existing, other));
            assertThrows(IllegalArgumentException.class,
                    () -> RankingEvaluationRunner.run(external, ablation, external.resolve("nested"), other));
            Path newExternal = root.resolve("generalization-new");
            assertThrows(IllegalArgumentException.class,
                    () -> RankingEvaluationRunner.run(external, ablation, newExternal, newExternal));
            assertEquals("historical bytes", Files.readString(sentinel));
            assertFalse(Files.exists(other));
            assertFalse(Files.exists(newExternal));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
