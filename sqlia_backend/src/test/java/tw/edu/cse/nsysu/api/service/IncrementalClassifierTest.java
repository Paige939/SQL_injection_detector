package tw.edu.cse.nsysu.api.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IncrementalClassifierTest {
    @Test
    public void defaultsToExistingOnlineBaggingModel() {
        assertEquals(
            IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB,
            IncrementalAlgorithm.fromId(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownModelIds() {
        IncrementalAlgorithm.fromId("HAT");
    }

    @Test
    public void sgdCanPredictAndLearnOnline() throws Exception {
        IncrementalClassifier classifier = new SgdIncrementalClassifier();
        classifier.initialize(2);
        classifier.update(new float[] {0.1f, 0.2f}, 0);
        classifier.update(new float[] {0.9f, 0.8f}, 1);

        double[] probabilities = classifier.predictProb(new float[] {0.8f, 0.9f});
        assertEquals(2, probabilities.length);
        assertEquals(1.0, probabilities[0] + probabilities[1], 1e-6);
        assertEquals(2, classifier.getTotalTrained());
    }

    private static double sgdAccuracy(boolean standardize) throws Exception {
        IncrementalClassifier c = new SgdIncrementalClassifier(standardize);
        c.initialize(2);
        java.util.Random r = new java.util.Random(7);
        int correct = 0, n = 3000;
        for (int i = 0; i < n; i++) {
            int y = r.nextInt(2);
            // f0: informative but tiny scale; f1: noise with huge scale
            float[] x = {(float) (y * 0.5 + r.nextGaussian() * 0.2), (float) (r.nextGaussian() * 500)};
            if (i >= n / 2) {
                double[] p = c.predictProb(x);
                if ((p[1] > p[0] ? 1 : 0) == y) correct++;
            }
            c.update(x, y);
        }
        return correct / (n / 2.0);
    }

    @Test
    public void standardizationHelpsSgdOnMixedScales() throws Exception {
        double raw = sgdAccuracy(false);
        double scaled = sgdAccuracy(true);
        System.out.println("SGD raw=" + raw + " standardized=" + scaled);
        assertTrue(scaled > raw);
        assertTrue(scaled > 0.8);
    }

    @Test
    public void hoeffdingTreeCanPredictAndLearnOnline() throws Exception {
        IncrementalClassifier classifier = new HoeffdingTreeIncrementalClassifier();
        classifier.initialize(2);
        for (int i = 0; i < 20; i++) {
            int label = i < 10 ? 0 : 1;
            float value = label == 0 ? 0.1f : 0.9f;
            classifier.update(new float[] {value, value}, label);
        }

        double[] probabilities = classifier.predictProb(new float[] {0.8f, 0.9f});
        assertEquals(2, probabilities.length);
        assertTrue(probabilities[0] >= 0.0 && probabilities[0] <= 1.0);
        assertTrue(probabilities[1] >= 0.0 && probabilities[1] <= 1.0);
        assertEquals(1.0, probabilities[0] + probabilities[1], 1e-6);
        assertEquals(20, classifier.getTotalTrained());
    }
}
