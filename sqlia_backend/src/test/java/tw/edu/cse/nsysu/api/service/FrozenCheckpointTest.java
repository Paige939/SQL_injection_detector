package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;

import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tw.edu.cse.nsysu.ArmNormalProfile;
import tw.edu.cse.nsysu.ExtendedRuleMiner;
import tw.edu.cse.nsysu.FeatureExtract;
import tw.edu.cse.nsysu.FeatureForML;
import tw.edu.cse.nsysu.OnlineBagging;
import weka.classifiers.bayes.NaiveBayesUpdateable;
import weka.core.Attribute;
import weka.core.Instances;

public class FrozenCheckpointTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void serializesEveryFrozenComponentAndReloadsIdenticalScores() throws Exception {
        FeatureForML features = new FeatureForML();
        features.setRules(List.of(new FeatureForML.Rule(new int[] {3}, 101, 0.8, true)));
        ArmNormalProfile profile = new ArmNormalProfile();
        profile.update(FeatureExtract.extract("ordinary input"));
        var rules = List.of(new ExtendedRuleMiner.ExtendedRule(new int[] {3}, false, 0.2, 0.8, 1.5));
        var bundle = new ModelStateService.FrozenModel(
                classifier(10), classifier(6), classifier(7), features, profile, rules, 2);
        var first = temporary.newFile("initial.ser").toPath();
        try (var output = new ObjectOutputStream(Files.newOutputStream(first))) {
            output.writeObject(bundle);
        }
        var state = ModelStateService.loadFrozen(first);
        var second = temporary.getRoot().toPath().resolve("saved.ser");
        state.saveFrozen(second);
        var restored = ModelStateService.loadFrozen(second);
        for (String architecture : FrozenArchitectureComparisonService.ARCHITECTURES) {
            assertArrayEquals(state.predictArchitectureProb("ordinary input", architecture),
                    restored.predictArchitectureProb("ordinary input", architecture), 0);
        }
        assertEquals(2, restored.getTotalTrained());
        assertEquals(1, restored.getExtendedRules().size());
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> state.saveFrozen(second));
    }

    static OnlineBagging classifier(int count) throws Exception {
        ArrayList<Attribute> attributes = new ArrayList<>();
        for (int i = 0; i < count; i++) attributes.add(new Attribute("x" + i));
        attributes.add(new Attribute("label", new ArrayList<>(List.of("0", "1"))));
        Instances header = new Instances("fixture", attributes, 0);
        header.setClassIndex(count);
        OnlineBagging bagging = new OnlineBagging(new NaiveBayesUpdateable(), 2, 42L);
        bagging.initialize(header);
        bagging.update(new float[count], 0);
        float[] malicious = new float[count];
        java.util.Arrays.fill(malicious, 1);
        bagging.update(malicious, 1);
        return bagging;
    }
}
