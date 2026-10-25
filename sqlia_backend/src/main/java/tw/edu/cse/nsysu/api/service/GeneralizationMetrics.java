package tw.edu.cse.nsysu.api.service;

import java.util.*;

/** Ranking metrics use whole score ties; uncertainty resamples heuristic template clusters. */
final class GeneralizationMetrics {
    record Scored(double score, int label, String source, String group) {
        Scored {
            ValidationThreshold.requireScore(score);
            if (score == 0) score = 0.0;
            if (label != 0 && label != 1) throw new IllegalArgumentException("Nonbinary label.");
            Objects.requireNonNull(source);
            Objects.requireNonNull(group);
        }
    }
    private static final List<String> INTERVAL_METRICS =
            List.of("accuracy", "precision", "recall", "f1", "fpr", "averagePrecision", "rocAuc");

    static Map<String, Object> metrics(List<Scored> rows, double threshold) {
        return weighted(rows, threshold, null);
    }

    private static Map<String, Object> weighted(List<Scored> rows, double threshold,
            Map<String, Integer> weights) {
        long tp = 0, fp = 0, tn = 0, fn = 0;
        TreeMap<Double, long[]> ties = new TreeMap<>(Comparator.reverseOrder());
        for (Scored row : rows) {
            int w = weights == null ? 1 : weights.getOrDefault(row.group(), 0);
            if (w == 0) continue;
            boolean predicted = row.score() >= threshold;
            if (row.label() == 1) { if (predicted) tp += w; else fn += w; }
            else { if (predicted) fp += w; else tn += w; }
            ties.computeIfAbsent(row.score(), ignored -> new long[2])[row.label()] += w;
        }
        long positives = tp + fn, total = tp + fp + tn + fn;
        double ap = 0;
        double concordant = 0;
        long cumulativePositive = 0, cumulativeTotal = 0;
        for (long[] tie : ties.values()) {
            concordant += (cumulativePositive + .5 * tie[1]) * tie[0];
            cumulativePositive += tie[1];
            cumulativeTotal += tie[0] + tie[1];
            if (positives > 0) ap += ((double) tie[1] / positives)
                    * ((double) cumulativePositive / cumulativeTotal);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total); result.put("tp", tp); result.put("fp", fp);
        result.put("tn", tn); result.put("fn", fn);
        result.put("accuracy", divide(tp + tn, total));
        result.put("precision", divide(tp, tp + fp));
        result.put("recall", divide(tp, positives));
        result.put("f1", divide(2 * tp, 2 * tp + fp + fn));
        result.put("fpr", divide(fp, fp + tn));
        result.put("averagePrecision", positives == 0 ? null : ap);
        result.put("rocAuc", positives == 0 || fp + tn == 0 ? null
                : concordant / ((double) positives * (fp + tn)));
        return result;
    }

    private static Double divide(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    static Map<String, Object> intervals(List<Scored> rows, double threshold, int replicates, long seed) {
        if (replicates < 200 || replicates > 2000)
            throw new IllegalArgumentException("Bootstrap replicates must be 200..2000.");
        List<String> groups = rows.stream().map(Scored::group).distinct().sorted().toList();
        Map<String, Integer> groupIndex = new HashMap<>();
        for (int i = 0; i < groups.size(); i++) groupIndex.put(groups.get(i), i);
        List<Scored> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingDouble(Scored::score).reversed());
        int[] rowGroups = new int[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) rowGroups[i] = groupIndex.get(sorted.get(i).group());
        Map<String, List<Double>> values = new LinkedHashMap<>();
        for (String metric : INTERVAL_METRICS) values.put(metric, new ArrayList<>());
        Random random = new Random(seed);
        for (int r = 0; r < replicates; r++) {
            int[] weights = new int[groups.size()];
            for (int g = 0; g < groups.size(); g++)
                weights[random.nextInt(groups.size())]++;
            Map<String, Object> metrics = sortedWeighted(sorted, rowGroups, threshold, weights);
            for (String metric : INTERVAL_METRICS) {
                Number value = (Number) metrics.get(metric);
                if (value != null) values.get(metric).add(value.doubleValue());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String metric : INTERVAL_METRICS) {
            List<Double> samples = values.get(metric);
            Collections.sort(samples);
            Map<String, Object> interval = new LinkedHashMap<>();
            interval.put("lower", samples.isEmpty() ? null : percentile(samples, .025));
            interval.put("upper", samples.isEmpty() ? null : percentile(samples, .975));
            interval.put("validReplicates", samples.size());
            interval.put("undefinedReplicates", replicates - samples.size());
            result.put(metric, interval);
        }
        return result;
    }

    private static Map<String, Object> sortedWeighted(List<Scored> rows, int[] rowGroups,
            double threshold, int[] weights) {
        long tp = 0, fp = 0, tn = 0, fn = 0;
        long positives = 0;
        for (int i = 0; i < rows.size(); i++) {
            Scored row = rows.get(i); int weight = weights[rowGroups[i]];
            if (row.label() == 1) {
                positives += weight;
                if (row.score() >= threshold) tp += weight; else fn += weight;
            } else {
                if (row.score() >= threshold) fp += weight; else tn += weight;
            }
        }
        long seenPositive = 0, seenTotal = 0;
        double ap = 0;
        double concordant = 0;
        for (int i = 0; i < rows.size();) {
            double score = rows.get(i).score();
            long tiePositive = 0, tieTotal = 0;
            do {
                int weight = weights[rowGroups[i]];
                tieTotal += weight;
                if (rows.get(i).label() == 1) tiePositive += weight;
                i++;
            } while (i < rows.size() && rows.get(i).score() == score);
            concordant += (seenPositive + .5 * tiePositive) * (tieTotal - tiePositive);
            seenPositive += tiePositive; seenTotal += tieTotal;
            if (positives > 0 && tieTotal > 0)
                ap += (double) tiePositive / positives * ((double) seenPositive / seenTotal);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accuracy", divide(tp + tn, tp + fp + tn + fn));
        result.put("precision", divide(tp, tp + fp)); result.put("recall", divide(tp, tp + fn));
        result.put("f1", divide(2 * tp, 2 * tp + fp + fn)); result.put("fpr", divide(fp, fp + tn));
        result.put("averagePrecision", positives == 0 ? null : ap);
        result.put("rocAuc", positives == 0 || fp + tn == 0 ? null
                : concordant / ((double) positives * (fp + tn)));
        return result;
    }

    static Map<String, Object> curves(List<Scored> rows, double threshold) {
        TreeMap<Double, long[]> ties = new TreeMap<>(Comparator.reverseOrder());
        long positives = 0, negatives = 0;
        for (Scored row : rows) {
            ties.computeIfAbsent(row.score(), ignored -> new long[2])[row.label()]++;
            if (row.label() == 1) positives++; else negatives++;
        }
        List<Map<String, Object>> points = new ArrayList<>();
        points.add(curvePoint(null, 0, 0, positives, negatives));
        long tp = 0, fp = 0;
        int index = 0, lastSelected = 0;
        int steps = Math.min(400, ties.size());
        int next = 1;
        for (var entry : ties.entrySet()) {
            tp += entry.getValue()[1]; fp += entry.getValue()[0]; index++;
            int target = steps == 0 ? 0 : (int) Math.ceil((double) next * ties.size() / steps);
            if (index >= target && index != lastSelected) {
                points.add(curvePoint(entry.getKey(), tp, fp, positives, negatives));
                lastSelected = index;
                next++;
            }
        }
        Map<String, Object> metrics = metrics(rows, threshold);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("points", points);
        result.put("totalScoreGroups", ties.size());
        result.put("displayPolicy", "At most 401 points sampled uniformly by whole score-group index, including endpoints. "
                + "ROC-AUC and AP use all score groups, not the displayed polyline. "
                + "Initial null threshold means predict none; initial precision 1 is a plotting convention. "
                + "The operating point uses the unchanged Validation-selected threshold; no external optimization.");
        result.put("operatingPoint", curvePoint(threshold, ((Number) metrics.get("tp")).longValue(),
                ((Number) metrics.get("fp")).longValue(), positives, negatives));
        return result;
    }

    private static Map<String, Object> curvePoint(Double threshold, long tp, long fp,
            long positives, long negatives) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("threshold", threshold);
        point.put("fpr", divide(fp, negatives));
        point.put("recall", divide(tp, positives));
        point.put("precision", tp + fp == 0 ? 1.0 : divide(tp, tp + fp));
        return point;
    }

    private static double percentile(List<Double> values, double p) {
        double index = p * (values.size() - 1);
        int lo = (int) index, hi = (int) Math.ceil(index);
        return values.get(lo) + (values.get(hi) - values.get(lo)) * (index - lo);
    }

    static Map<String, Object> report(List<Scored> rows, double threshold, int replicates, long seed) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("metrics", metrics(rows, threshold));
        report.put("confidenceIntervals", intervals(rows, threshold, replicates, seed));
        report.put("rankingCurves", curves(rows, threshold));
        Map<String, List<Scored>> sources = new TreeMap<>();
        Map<String, List<Scored>> groups = new TreeMap<>();
        for (Scored row : rows) {
            for (String source : row.source().split("\\|"))
                sources.computeIfAbsent(source, ignored -> new ArrayList<>()).add(row);
            String size = row.group();
            groups.computeIfAbsent(size, ignored -> new ArrayList<>()).add(row);
        }
        Map<String, Object> bySource = new LinkedHashMap<>();
        sources.forEach((source, data) -> bySource.put(source, metrics(data, threshold)));
        report.put("bySource", bySource);
        report.put("sourceAggregationPolicy", "Multi-source provenance contributes to each named source; source counts may overlap.");
        report.put("templateGroupCount", groups.size());
        report.put("bootstrapPolicy", "deterministic percentile 95%; resample template clusters with replacement; "
                + "undefined denominators excluded per metric and explicitly counted; no score jitter");
        return report;
    }
}
