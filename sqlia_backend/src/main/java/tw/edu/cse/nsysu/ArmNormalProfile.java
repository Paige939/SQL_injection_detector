package tw.edu.cse.nsysu;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ArmNormalProfile implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final Map<Integer, Integer> itemCounts = new HashMap<>();
    private int transactionCount;

    // Add one transaction only after the sample has been verified as benign.
    public synchronized void update(double[] baseFeatures) {
        Set<Integer> items = FeatureForML.toTransactionSet(baseFeatures);
        for (int item : items) {
            itemCounts.merge(item, 1, Integer::sum);
        }
        transactionCount++;
    }

    // Return rule-match, violation-ratio, and anomaly-score features for ML fusion.
    public synchronized float[] deriveFeatures(double[] baseFeatures) {
        return derive(baseFeatures, false);
    }

    // Same features, but as if this (already added) benign sample were not part of the profile.
    // Used for training rows so the model does not learn from features that saw its own row.
    public synchronized float[] deriveFeaturesLeaveOneOut(double[] baseFeatures) {
        return derive(baseFeatures, true);
    }

    private float[] derive(double[] baseFeatures, boolean leaveOneOut) {
        Set<Integer> items = FeatureForML.toTransactionSet(baseFeatures);
        int total = leaveOneOut ? transactionCount - 1 : transactionCount;
        if (total <= 0 || items.isEmpty()) {
            return new float[] {0.0f, 1.0f, 1.0f};
        }

        int unseen = 0;
        double normalSupport = 0.0;
        for (int item : items) {
            int count = itemCounts.getOrDefault(item, 0) - (leaveOneOut ? 1 : 0);
            if (count == 0) unseen++;
            normalSupport += (double) count / total;
        }
        double violationRatio = (double) unseen / items.size();
        double averageSupport = normalSupport / items.size();
        float ruleMatch = unseen == 0 ? 1.0f : 0.0f;
        float anomalyScore = (float) Math.max(0.0, 1.0 - averageSupport);
        return new float[] {ruleMatch, (float) violationRatio, anomalyScore};
    }

    public synchronized int size() {
        return transactionCount;
    }
}
