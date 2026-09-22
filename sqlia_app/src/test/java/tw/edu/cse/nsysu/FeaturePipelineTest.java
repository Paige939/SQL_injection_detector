package tw.edu.cse.nsysu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;

public class FeaturePipelineTest {
    // Feature extraction and ARM conversion must share the same six-dimensional contract.
    @Test
    public void convertsSixBaseFeaturesToArmItems() {
        double[] features = FeatureExtract.extract("SELECT * FROM users WHERE id = 1");

        assertEquals(6, features.length);
        Set<Integer> items = FeatureForML.toTransactionSet(features);

        assertTrue(items.contains(40 + (int) features[3]));
    }

    // A verified benign transaction becomes normal only after an explicit profile update.
    @Test
    public void normalProfileAcceptsVerifiedBenignFeaturePattern() {
        double[] features = FeatureExtract.extract("SELECT id FROM users");
        ArmNormalProfile profile = new ArmNormalProfile();

        float[] beforeUpdate = profile.deriveFeatures(features);
        profile.update(features);
        float[] afterUpdate = profile.deriveFeatures(features);

        assertEquals(1.0f, beforeUpdate[1], 0.0001f);
        assertEquals(1.0f, afterUpdate[0], 0.0001f);
        assertEquals(0.0f, afterUpdate[1], 0.0001f);
    }
}
