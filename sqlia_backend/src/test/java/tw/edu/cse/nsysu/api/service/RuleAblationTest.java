package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tw.edu.cse.nsysu.ExtendedRuleMiner.ExtendedRule;

public class RuleAblationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private ExtendedRule rule(boolean sequential, int... antecedent) {
        return new ExtendedRule(antecedent, sequential, .2, .99, 2.0);
    }

    @Test public void baselinePreservesOrderAndRuleObjects() {
        var candidates = List.of(rule(false, 204, 1208, 1214), rule(true, 4), rule(true, 15));
        var filtered = ExtendedRulePolicy.BASELINE.filter(candidates);
        assertEquals(candidates, filtered);
        for (int i = 0; i < candidates.size(); i++) assertSame(candidates.get(i), filtered.get(i));
        assertThrows(UnsupportedOperationException.class, () -> filtered.clear());
    }

    @Test public void noNegativeRemovesAbsenceRulesButRetainsPositiveAndSequenceRules() {
        assertFalse(ExtendedRulePolicy.NO_NEGATIVE.accepts(rule(false, 204, 1208, 1214)));
        assertFalse(ExtendedRulePolicy.NO_NEGATIVE.accepts(rule(false, 1003, 1214, 1225)));
        assertTrue(ExtendedRulePolicy.NO_NEGATIVE.accepts(rule(false, 50)));
        assertTrue(ExtendedRulePolicy.NO_NEGATIVE.accepts(rule(true, 4)));
        assertTrue(ExtendedRulePolicy.NO_NEGATIVE.accepts(rule(false, 205, 206)));
    }

    @Test public void contextRejectsCommonWordsPunctuationBaseFeaturesAndRepeatedToken() {
        for (int token : List.of(1, 2, 3, 4, 13, 14, 16, 20, 23, 24, 25)) {
            assertFalse(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(true, token)));
            assertFalse(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(false, 200 + token)));
        }
        assertFalse(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(false, 50)));
        assertFalse(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(true, 6, 6)));
        assertFalse(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(true, 3, 4, 23)));
        assertFalse(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(false, 205, 206, 1208)));
    }

    @Test public void contextAcceptsComparisonShapeOrDistinctSqlTokensInEitherEncoding() {
        assertTrue(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(true, 15)));
        assertTrue(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(false, 215)));
        assertTrue(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(true, 5, 6)));
        assertTrue(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(false, 205, 206)));
        assertTrue(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(true, 6, 7)));
        assertTrue(ExtendedRulePolicy.SQL_CONTEXT.filter(List.of(rule(true, 4))).isEmpty());
    }

    @Test public void contextTokenIdsMatchRealTokenizerAndAreNotAnInjectionOracle() {
        assertEquals(List.of(5, 6), tw.edu.cse.nsysu.ExtendedRuleMiner.tokenize("UNION SELECT"));
        assertEquals(List.of(15), tw.edu.cse.nsysu.ExtendedRuleMiner.tokenize("1=2"));
        assertEquals(List.of(6, 7), tw.edu.cse.nsysu.ExtendedRuleMiner.tokenize("SELECT field FROM table"));
        assertEquals(List.of(4), tw.edu.cse.nsysu.ExtendedRuleMiner.tokenize("The acting and dialogue were excellent."));
        assertTrue(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(true, 15)));
        assertFalse(ExtendedRulePolicy.SQL_CONTEXT.accepts(rule(true, 4)));
    }

    private Map<String, Object> result(double threshold, int fp, int tn) {
        return Map.of("threshold", threshold,
                "metrics", Map.of("total", 10, "tp", 4, "fp", fp, "tn", tn, "fn", 0));
    }

    @Test public void baselineComparisonPreservesThresholdPrecisionAndConfusionCounts() {
        double threshold = .9999999999999896;
        RuleAblationRunner.compareCountsAndThreshold(result(threshold, 2, 4), result(threshold, 2, 4));
        assertThrows(IllegalStateException.class, () -> RuleAblationRunner.compareCountsAndThreshold(
                result(1, 2, 4), result(threshold, 2, 4)));
        assertThrows(IllegalStateException.class, () -> RuleAblationRunner.compareCountsAndThreshold(
                result(threshold, 3, 3), result(threshold, 2, 4)));
        assertThrows(IllegalStateException.class, () -> RuleAblationRunner.verifyBaseline(
                42, Map.of(), Map.of("trials", List.of())));
    }

    @Test public void filteredAndEmptySchemasReloadWithMatchingClassifierDimensions() throws Exception {
        for (var candidates : List.of(List.of(rule(true, 4), rule(true, 15)), List.of(rule(true, 4)))) {
            var retained = ExtendedRulePolicy.SQL_CONTEXT.filter(candidates);
            var features = new tw.edu.cse.nsysu.FeatureForML();
            features.setRules(List.of());
            var bundle = new ModelStateService.FrozenModel(
                    FrozenCheckpointTest.classifier(9), FrozenCheckpointTest.classifier(6),
                    FrozenCheckpointTest.classifier(6 + retained.size()), features,
                    new tw.edu.cse.nsysu.ArmNormalProfile(), retained, 2);
            Path path = temporary.newFile().toPath();
            try (var stream = new java.io.ObjectOutputStream(Files.newOutputStream(path))) {
                stream.writeObject(bundle);
            }
            var restored = ModelStateService.loadFrozen(path);
            assertEquals(retained.size(), restored.getExtendedRules().size());
            assertEquals(retained.isEmpty() ? 0 : .99,
                    restored.predictArchitectureProb("' OR 1=1 --", "PURE_EXTENDED_ARM")[1], 0);
            assertEquals(2, restored.predictArchitectureProb("normal and ordinary", "EXTENDED_ARM").length);
        }
    }

    @Test public void refusesOutputInsideHistoricalRun() throws Exception {
        Path completed = temporary.newFolder("completed").toPath();
        assertThrows(IllegalArgumentException.class, () -> RuleAblationRunner.run(
                temporary.getRoot().toPath(), completed, completed.resolve("overwrite")));
        assertFalse(Files.exists(completed.resolve("overwrite")));
    }

    @Test public void missingProvenanceHashPersistsFailureBeforeAnyTraining() throws Exception {
        Path completed = temporary.newFolder("invalid-completed").toPath();
        Files.writeString(completed.resolve("frozen-manifest.json"), "{}");
        Files.writeString(completed.resolve("development-report.json"), "{\"status\":\"complete\"}");
        Files.writeString(completed.resolve("external-report.json"), "{\"status\":\"complete\"}");
        Path output = temporary.getRoot().toPath().resolve("failed-run");
        assertThrows(IllegalStateException.class, () -> RuleAblationRunner.run(
                temporary.getRoot().toPath(), completed, output));
        assertEquals("failed", GeneralizationAudit.object(output.resolve("ablation-report.json")).get("status"));
        assertFalse(Files.exists(output.resolve("models")));
    }

    @Test public void refusesToOverwriteExistingOutput() throws Exception {
        Path completed = temporary.newFolder("historical").toPath();
        Path output = temporary.newFolder("existing").toPath();
        Path sentinel = output.resolve("ablation-report.json");
        Files.writeString(sentinel, "untouched");
        assertThrows(FileAlreadyExistsException.class, () -> RuleAblationRunner.run(
                temporary.getRoot().toPath(), completed, output));
        assertEquals("untouched", Files.readString(sentinel));
    }
}
