package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;
import tw.edu.cse.nsysu.FeatureExtract;

public class SingleInputDetectionTest {
    @Test
    public void acceptsMixedInputsWithoutRequiringSqlSyntax() {
        for (String input : List.of("SELECT 1", "' OR 1=1 --", "47209",
                "example@example.com", "ordinary words", "  user input  ")) {
            SingleInputValidation.requireValid(input);
            double[] features = FeatureExtract.extract(input);
            assertEquals(6, features.length);
            for (double feature : features) assertTrue(Double.isFinite(feature));
        }
        SingleInputValidation.requireValid("a".repeat(4000));
    }

    @Test
    public void rejectsBlankAndOversizeInputBeforeAccessingModels() {
        DetectionService service = new DetectionService(null);
        for (String input : new String[] {null, "", " \t\n", "a".repeat(4001)}) {
            assertThrows(IllegalArgumentException.class, () -> service.detect(input));
            assertThrows(IllegalArgumentException.class, () -> service.predictAllArchitectures(input));
            assertThrows(IllegalArgumentException.class, () -> service.predictExtendedArchitectures(input));
            assertThrows(IllegalArgumentException.class, () -> service.predictArchitecture(input, "FULL_FLOW"));
        }
    }

}