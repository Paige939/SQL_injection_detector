package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.Test;

public class RankingMetricsTest {
    private static GeneralizationMetrics.Scored row(double score, int label, String group) {
        return new GeneralizationMetrics.Scored(score, label, "fixture", group);
    }
    private static double auc(List<GeneralizationMetrics.Scored> rows) {
        return ((Number) GeneralizationMetrics.metrics(rows, .5).get("rocAuc")).doubleValue();
    }

    @Test public void aucHandlesPerfectReversedAndTiedRankings() {
        assertEquals(1, auc(List.of(row(.8, 1, "a"), row(.2, 0, "b"))), 0);
        assertEquals(0, auc(List.of(row(.2, 1, "a"), row(.8, 0, "b"))), 0);
        assertEquals(.5, auc(List.of(row(.8, 1, "a"), row(.8, 0, "b"))), 0);
        var rows = List.of(row(.9, 1, "a"), row(.9, 0, "b"), row(.1, 1, "c"));
        assertEquals(.25, auc(rows), 0);
        List<GeneralizationMetrics.Scored> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);
        assertEquals(auc(rows), auc(reversed), 0);
        assertNull(GeneralizationMetrics.metrics(List.of(row(.5, 1, "a")), .5).get("rocAuc"));
        assertNull(GeneralizationMetrics.metrics(List.of(row(.5, 0, "a")), .5).get("rocAuc"));
        assertNull(GeneralizationMetrics.metrics(List.of(), .5).get("rocAuc"));
    }

    @Test public void bootstrapMatchesExplicitClusterResamplingForAuc() {
        var rows = List.of(row(.9, 1, "a"), row(.9, 0, "a"),
                row(.2, 1, "b"), row(.1, 0, "c"));
        var random = new Random(42);
        List<Double> reference = new ArrayList<>();
        for (int r = 0; r < 200; r++) {
            List<GeneralizationMetrics.Scored> sample = new ArrayList<>();
            for (int g = 0; g < 3; g++) {
                String chosen = List.of("a", "b", "c").get(random.nextInt(3));
                rows.stream().filter(row -> row.group().equals(chosen)).forEach(sample::add);
            }
            Number value = (Number) GeneralizationMetrics.metrics(sample, .5).get("rocAuc");
            if (value != null) reference.add(value.doubleValue());
        }
        Collections.sort(reference);
        Map<?, ?> ci = (Map<?, ?>) GeneralizationMetrics.intervals(rows, .5, 200, 42).get("rocAuc");
        assertEquals(reference.size(), ((Number) ci.get("validReplicates")).intValue());
        for (var bound : Map.of("lower", .025, "upper", .975).entrySet()) {
            double index = bound.getValue() * (reference.size() - 1);
            int lo = (int) index, hi = (int) Math.ceil(index);
            double expected = reference.get(lo) + (reference.get(hi) - reference.get(lo)) * (index - lo);
            assertEquals(expected, ((Number) ci.get(bound.getKey())).doubleValue(), 1e-14);
        }
        var degenerate = GeneralizationMetrics.intervals(
                List.of(row(.9, 1, "a"), row(.1, 0, "b")), .5, 200, 42);
        assertTrue(((Number) ((Map<?, ?>) degenerate.get("rocAuc")).get("undefinedReplicates")).intValue() > 0);
    }

    @Test public void aucMatchesPairwiseReferenceAndFullRocArea() {
        Random random = new Random(17);
        List<GeneralizationMetrics.Scored> rows = new ArrayList<>();
        for (int i = 0; i < 80; i++) rows.add(row(random.nextInt(5) / 4.0, i % 2, "g" + i));
        double favorable = 0;
        int pairs = 0;
        for (var positive : rows) if (positive.label() == 1) {
            for (var negative : rows) if (negative.label() == 0) {
                pairs++;
                if (positive.score() > negative.score()) favorable++;
                else if (positive.score() == negative.score()) favorable += .5;
            }
        }
        assertEquals(favorable / pairs, auc(rows), 1e-14);
        var points = GeneralizationAudit.JSON.valueToTree(
                GeneralizationMetrics.curves(rows, .5)).path("points");
        double area = 0;
        for (int i = 1; i < points.size(); i++) {
            var previous = points.get(i - 1);
            var current = points.get(i);
            area += (current.path("fpr").doubleValue() - previous.path("fpr").doubleValue())
                    * (current.path("recall").doubleValue() + previous.path("recall").doubleValue()) / 2;
        }
        assertEquals(auc(rows), area, 1e-14);
        assertEquals(GeneralizationMetrics.metrics(rows, 0).get("rocAuc"),
                GeneralizationMetrics.metrics(rows, Math.nextUp(1.0)).get("rocAuc"));
    }

    @Test public void curvesPreserveEndpointsTiesAndExactOperatingPoint() {
        var rows = List.of(row(.9, 1, "a"), row(.9, 0, "b"), row(.1, 1, "c"));
        ObjectNode report = GeneralizationAudit.JSON.valueToTree(GeneralizationMetrics.report(rows, .9, 200, 42));
        report.put("threshold", .9);
        RankingReportValidation.validate(report, "fixture");
        var curves = report.path("rankingCurves");
        assertEquals(3, curves.path("points").size());
        assertTrue(curves.path("points").get(0).path("threshold").isNull());
        assertEquals(.5, curves.path("operatingPoint").path("recall").doubleValue(), 0);
        assertEquals(1, curves.path("operatingPoint").path("fpr").doubleValue(), 0);
        ((ObjectNode) curves.path("operatingPoint")).put("threshold", .5);
        assertThrows(ExternalReportService.InvalidReportException.class,
                () -> RankingReportValidation.validate(report, "fixture"));
    }

    @Test public void curveDisplayIsBoundedWithoutChangingAuc() {
        List<GeneralizationMetrics.Scored> rows = new ArrayList<>();
        for (int i = 0; i < 2000; i++) rows.add(row(i / 2000.0, i >= 1000 ? 1 : 0, "g" + i));
        var curves = GeneralizationAudit.JSON.valueToTree(GeneralizationMetrics.curves(rows, .7535));
        assertEquals(401, curves.path("points").size());
        assertEquals(2000, curves.path("totalScoreGroups").intValue());
        assertEquals(1, curves.path("points").get(400).path("fpr").doubleValue(), 0);
        assertEquals(.7535, curves.path("operatingPoint").path("threshold").doubleValue(), 0);
        assertEquals(1, auc(rows), 0);
    }

    @Test public void undefinedCurvesAndPredictNoneThresholdRemainExplicit() {
        ObjectNode singleClass = GeneralizationAudit.JSON.valueToTree(GeneralizationMetrics.report(
                List.of(row(.9, 1, "a")), Math.nextUp(1.0), 200, 42));
        singleClass.put("threshold", Math.nextUp(1.0));
        RankingReportValidation.validate(singleClass, "single-class");
        assertTrue(singleClass.path("metrics").path("rocAuc").isNull());
        assertTrue(singleClass.path("rankingCurves").path("points").get(0).path("fpr").isNull());
        assertEquals(1, singleClass.path("rankingCurves").path("operatingPoint").path("precision").intValue());
        assertEquals(0, singleClass.path("confidenceIntervals").path("rocAuc").path("validReplicates").intValue());
        ObjectNode legacy = GeneralizationAudit.JSON.createObjectNode();
        RankingReportValidation.validate(legacy, "legacy");
        legacy.putObject("metrics").put("rocAuc", .5);
        assertThrows(ExternalReportService.InvalidReportException.class,
                () -> RankingReportValidation.validate(legacy, "partial"));
    }
}
