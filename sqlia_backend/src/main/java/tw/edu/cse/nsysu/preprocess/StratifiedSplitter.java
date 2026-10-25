package tw.edu.cse.nsysu.preprocess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class StratifiedSplitter {
    public record Split(List<RawRecord> train, List<RawRecord> val, List<RawRecord> test) {}

    public static Split splitByLabelAndGroup(
            List<RawRecord> records, double trainRatio, double valRatio, long seed) {
        if (!Double.isFinite(trainRatio) || !Double.isFinite(valRatio)
                || trainRatio < 0 || valRatio < 0 || trainRatio + valRatio > 1) {
            throw new IllegalArgumentException("Finite, nonnegative split ratios must sum to at most 1.");
        }
        Map<String, List<RawRecord>> groups = new LinkedHashMap<>();
        int[] totals = new int[2];
        for (RawRecord row : records) {
            totals[label(row)]++;
            groups.computeIfAbsent(PayloadNormalizer.groupKey(row.rawText),
                    key -> new ArrayList<>()).add(row);
        }
        List<List<RawRecord>> ordered = new ArrayList<>(groups.values());
        Collections.shuffle(ordered, new Random(seed));
        ordered.sort(Comparator.comparingInt((List<RawRecord> group) -> group.size()).reversed());
        double[] ratios = {trainRatio, valRatio, 1 - trainRatio - valRatio};
        List<List<RawRecord>> partitions = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        int[][] counts = new int[3][2];
        for (List<RawRecord> group : ordered) {
            int[] additions = new int[2];
            for (RawRecord row : group) additions[label(row)]++;
            int best = -1;
            double bestScore = Double.POSITIVE_INFINITY;
            for (int split = 0; split < 3; split++) {
                if (ratios[split] == 0) continue;
                double score = 0;
                for (int label = 0; label < 2; label++) {
                    double before = counts[split][label] - totals[label] * ratios[split];
                    double after = before + additions[label];
                    score += (after * after - before * before) / Math.max(1, totals[label]);
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = split;
                }
            }
            partitions.get(best).addAll(group);
            for (int label = 0; label < 2; label++) counts[best][label] += additions[label];
        }
        return new Split(partitions.get(0), partitions.get(1), partitions.get(2));
    }

    private static int label(RawRecord row) {
        String label = row.rawLabel == null ? "" : row.rawLabel.trim();
        if (!label.equals("0") && !label.equals("1")) {
            throw new IllegalArgumentException("Splitting requires validated binary labels.");
        }
        return label.equals("1") ? 1 : 0;
    }
}
