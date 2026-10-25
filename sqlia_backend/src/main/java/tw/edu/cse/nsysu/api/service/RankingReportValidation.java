package tw.edu.cse.nsysu.api.service;

import com.fasterxml.jackson.databind.JsonNode;

/** Optional extension: historical reports remain readable without invented ranking results. */
final class RankingReportValidation {
    private RankingReportValidation() {}

    static void validate(JsonNode result, String id) {
        JsonNode metrics = result.path("metrics"), intervals = result.path("confidenceIntervals");
        if (!metrics.has("rocAuc") && !intervals.has("rocAuc") && !result.has("rankingCurves")) return;
        require(metrics.has("rocAuc") && nullableUnit(metrics.path("rocAuc")), id);
        long positives = metrics.path("tp").longValue() + metrics.path("fn").longValue();
        long negatives = metrics.path("fp").longValue() + metrics.path("tn").longValue();
        require(metrics.path("rocAuc").isNull() == (positives == 0 || negatives == 0), id);
        JsonNode ci = intervals.path("rocAuc");
        JsonNode lo = ci.path("lower"), hi = ci.path("upper");
        require((lo.isNull() && hi.isNull()) || (unit(lo) && unit(hi)
                && lo.doubleValue() <= hi.doubleValue()), id);
        for (String key : new String[]{"validReplicates", "undefinedReplicates"})
            require(ci.path(key).isIntegralNumber() && ci.path(key).longValue() >= 0, id);
        long valid = ci.path("validReplicates").longValue();
        long count = valid + ci.path("undefinedReplicates").longValue();
        require(count >= 200 && count <= 2000 && (valid == 0) == lo.isNull(), id);
        require(!metrics.path("rocAuc").isNull() || valid == 0, id);
        JsonNode curves = result.path("rankingCurves");
        require(curves.path("displayPolicy").isTextual() && !curves.path("displayPolicy").asText().isBlank(), id);
        require(curves.path("totalScoreGroups").isIntegralNumber()
                && curves.path("totalScoreGroups").longValue() > 0
                && curves.path("totalScoreGroups").longValue() <= positives + negatives, id);
        JsonNode points = curves.path("points");
        require(points.isArray() && points.size()
                == Math.min(400, curves.path("totalScoreGroups").longValue()) + 1, id);
        double lastFpr = -1, lastRecall = -1, lastThreshold = Double.POSITIVE_INFINITY;
        for (int i = 0; i < points.size(); i++) {
            JsonNode point = points.get(i);
            point(point, positives, negatives, id);
            if (i == 0) {
                require(point.path("threshold").isNull(), id);
                require(positives == 0 || point.path("recall").doubleValue() == 0, id);
                require(negatives == 0 || point.path("fpr").doubleValue() == 0, id);
            } else {
                require(unit(point.path("threshold")) && point.path("threshold").doubleValue() < lastThreshold, id);
                lastThreshold = point.path("threshold").doubleValue();
            }
            if (positives > 0) {
                require(point.path("recall").doubleValue() >= lastRecall, id);
                lastRecall = point.path("recall").doubleValue();
            }
            if (negatives > 0) {
                require(point.path("fpr").doubleValue() >= lastFpr, id);
                lastFpr = point.path("fpr").doubleValue();
            }
        }
        require((positives == 0 || lastRecall == 1) && (negatives == 0 || lastFpr == 1), id);
        JsonNode operating = curves.path("operatingPoint");
        point(operating, positives, negatives, id);
        require(operating.path("threshold").isNumber()
                && Double.isFinite(operating.path("threshold").doubleValue())
                && operating.path("threshold").doubleValue() >= 0
                && operating.path("threshold").doubleValue() <= Math.nextUp(1.0)
                && Double.compare(operating.path("threshold").doubleValue(),
                        result.path("threshold").doubleValue()) == 0, id);
        for (String key : new String[]{"fpr", "recall"}) {
            JsonNode expected = metrics.path(key);
            require(expected.isNull() ? operating.path(key).isNull()
                    : Double.compare(expected.doubleValue(), operating.path(key).doubleValue()) == 0, id);
        }
        double precision = metrics.path("tp").longValue() + metrics.path("fp").longValue() == 0
                ? 1 : metrics.path("precision").doubleValue();
        require(Double.compare(precision, operating.path("precision").doubleValue()) == 0, id);
    }

    private static void point(JsonNode point, long positives, long negatives, String id) {
        require(point.isObject() && point.has("threshold"), id);
        require(point.has("fpr") && nullableUnit(point.path("fpr"))
                && point.path("fpr").isNull() == (negatives == 0), id);
        require(point.has("recall") && nullableUnit(point.path("recall"))
                && point.path("recall").isNull() == (positives == 0), id);
        require(unit(point.path("precision")), id);
    }

    private static boolean nullableUnit(JsonNode node) { return node.isNull() || unit(node); }
    private static boolean unit(JsonNode node) {
        return node.isNumber() && Double.isFinite(node.doubleValue())
                && node.doubleValue() >= 0 && node.doubleValue() <= 1;
    }
    private static void require(boolean condition, String id) {
        if (!condition) throw new ExternalReportService.InvalidReportException(
                "Invalid ranking evaluation in report " + id + ".");
    }
}
