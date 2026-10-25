package tw.edu.cse.nsysu.api.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ValidationThreshold {
    record Scored(double score, int label) {}
    record Choice(double threshold, double f1, double fpr) {}

    private ValidationThreshold() {}

    static Choice select(List<Scored> observations) {
        if (observations.isEmpty()) throw new IllegalArgumentException("Validation data is empty.");
        List<Scored> sorted = new ArrayList<>(observations);
        int positives = 0;
        for (Scored row : sorted) {
            requireScore(row.score());
            if (row.label() != 0 && row.label() != 1) {
                throw new IllegalArgumentException("Validation labels must be binary.");
            }
            positives += row.label();
        }
        if (positives == 0 || positives == sorted.size()) {
            throw new IllegalArgumentException("Validation must contain both labels.");
        }
        sorted.sort(Comparator.comparingDouble(Scored::score));
        int tp = positives, fp = sorted.size() - positives, tn = 0, fn = 0;
        Choice best = metrics(0, tp, fp, tn, fn);
        for (int i = 0; i < sorted.size();) {
            double score = sorted.get(i).score();
            do {
                if (sorted.get(i).label() == 1) { tp--; fn++; }
                else { fp--; tn++; }
                i++;
            } while (i < sorted.size() && sorted.get(i).score() == score);
            Choice candidate = metrics(Math.nextUp(score), tp, fp, tn, fn);
            if (candidate.f1() > best.f1()
                    || (candidate.f1() == best.f1() && candidate.fpr() < best.fpr())) {
                best = candidate;
            }
        }
        return best;
    }

    static void requireScore(double score) {
        if (!Double.isFinite(score) || score < 0 || score > 1) {
            throw new IllegalStateException("Architecture score must be finite and within [0,1].");
        }
    }

    private static Choice metrics(double threshold, int tp, int fp, int tn, int fn) {
        double f1 = 2 * tp + fp + fn == 0 ? 0 : 2.0 * tp / (2 * tp + fp + fn);
        double fpr = fp + tn == 0 ? 0 : (double) fp / (fp + tn);
        return new Choice(threshold, f1, fpr);
    }
}
