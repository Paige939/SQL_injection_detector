package tw.edu.cse.nsysu.api.service;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class ExternalErrorAnalysisTest {
    @Test
    public void summarizesInterpolatedQuantilesWithoutLosingValues() {
        var result = ExternalErrorAnalysis.summarize(new double[] {4, 1, 3, 2});
        assertEquals(4, result.get("count"));
        assertEquals(2.5, (Double) result.get("mean"), 0);
        assertEquals(2.5, (Double) result.get("median"), 0);
        assertEquals(1.3, (Double) result.get("p10"), 1e-12);
        assertEquals(3.7, (Double) result.get("p90"), 1e-12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyDistribution() {
        ExternalErrorAnalysis.summarize(new double[0]);
    }

    private Map<String, Object> original() {
        return Map.of("models", List.of(Map.of("architecture", "PURE_EXTENDED_ARM",
                "metrics", Map.of("total", 10, "tp", 4, "fp", 2, "tn", 4, "fn", 0))));
    }

    @Test
    public void acceptsMatchingCountsIncludingAbsentZeroOutcome() {
        ExternalErrorAnalysis.verifyOutcomes("PURE_EXTENDED_ARM",
                Map.of("total", 10, "outcomes", Map.of("TP", 4, "FP", 2, "TN", 4)), original());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsChangedConfusionCountsEvenWithMatchingTotal() {
        ExternalErrorAnalysis.verifyOutcomes("PURE_EXTENDED_ARM",
                Map.of("total", 10, "outcomes", Map.of("TP", 4, "FP", 3, "TN", 3)), original());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsMissingArchitecture() {
        ExternalErrorAnalysis.verifyOutcomes("EXTENDED_ARM",
                Map.of("total", 10, "outcomes", Map.of("TP", 4, "FP", 2, "TN", 4)), original());
    }

    @Test
    public void ordinaryEnglishMatchesHighConfidenceNegativeRule() {
        var rule = new tw.edu.cse.nsysu.ExtendedRuleMiner.ExtendedRule(
                new int[] {204, 1208, 1214}, false, .10255382271579201, .9918573090345095, 2.894232201596604);
        String text = "The acting and dialogue were excellent.";
        var tokens = tw.edu.cse.nsysu.ExtendedRuleMiner.tokenize(text);
        var items = tw.edu.cse.nsysu.ExtendedRuleMiner.items(text, tokens,
                tw.edu.cse.nsysu.ExtendedRuleMiner.ItemMode.BASE_TOKEN_NEG);
        assertTrue(rule.matches(items, tokens));
        assertTrue(rule.confidence >= .9219152854511972);
    }
}
