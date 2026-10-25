package tw.edu.cse.nsysu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    public void associationRuleConfidenceBecomesAnAdditionalFeature() {
        FeatureForML featureEngine = new FeatureForML();
        featureEngine.setRules(List.of(
                new FeatureForML.Rule(new int[] {2}, 33, 0.75)));
        double[] matchingBaseFeatures = {120.0, 0.4, 0.06, 2.0, 0.03, 0.4};
        double[] nonMatchingBaseFeatures = {120.0, 0.4, 0.0, 2.0, 0.03, 0.4};

        float[] matching = featureEngine.getFeatureFromBase(matchingBaseFeatures, 4);
        float[] nonMatching = featureEngine.getFeatureFromBase(nonMatchingBaseFeatures, 4);

        assertEquals(7, matching.length);
        assertEquals(0.75f, matching[6], 0.0001f);
        assertEquals(0.0f, nonMatching[6], 0.0001f);
    }

    @Test
    public void sqliClassRuleDoesNotRequireUnknownClassItemAtInference() {
        FeatureForML featureEngine = new FeatureForML();
        featureEngine.setRules(List.of(
                new FeatureForML.Rule(new int[] {2, 33},
                        FeatureForML.SQLI_LABEL_ITEM, 0.84, true)));
        double[] matchingBaseFeatures = {120.0, 0.4, 0.06, 2.0, 0.03, 0.4};
        double[] nonMatchingBaseFeatures = {120.0, 0.4, 0.0, 2.0, 0.03, 0.4};

        float[] matching = featureEngine.getFeatureFromBase(matchingBaseFeatures, 4);
        float[] nonMatching = featureEngine.getFeatureFromBase(nonMatchingBaseFeatures, 4);

        assertEquals(0.84f, matching[6], 0.0001f);
        assertEquals(0.0f, nonMatching[6], 0.0001f);
    }

    @Test
    public void fpGrowthMinesFeatureAntecedentToSqliLabelRule() throws Exception {
        Path transactions = Files.createTempFile("sqli-rule-test-", ".txt");
        try {
            Files.write(transactions, List.of(
                    "2 33 101", "2 33 101", "2 33 101", "3 100", "3 100"),
                    StandardCharsets.UTF_8);

            List<FeatureForML.Rule> rules = new FPGrowth().getAssociationRules(
                    transactions.toString(), 0.2, 0.6);

            assertTrue(rules.stream().anyMatch(rule ->
                    rule.getConsequent() == FeatureForML.SQLI_LABEL_ITEM
                    && java.util.Arrays.equals(rule.getAntecedent(), new int[] {2, 33})
                    && rule.getConfidence() >= 0.99));
        } finally {
            Files.deleteIfExists(transactions);
        }
    }
}
