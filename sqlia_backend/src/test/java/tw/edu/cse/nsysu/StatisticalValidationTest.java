package tw.edu.cse.nsysu;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StatisticalValidationTest {
    @Test
    public void exactMcNemarMatchesKnownValues() {
        assertEquals(1.0, StatisticalValidation.exactMcNemar(5, 5), 1e-9);
        assertEquals(1.0, StatisticalValidation.exactMcNemar(0, 0), 1e-9);
        // n=10, k=1: 2 * (1 + 10) / 1024
        assertEquals(22.0 / 1024, StatisticalValidation.exactMcNemar(1, 9), 1e-9);
    }

    @Test
    public void studentTCdfMatchesTables() {
        // t=2.262 at df=9 is the two-sided 5% critical value.
        assertEquals(0.975, StatisticalValidation.studentTCdf(2.262, 9), 1e-3);
        assertEquals(0.5, StatisticalValidation.studentTCdf(0, 5), 1e-9);
    }

    @Test
    public void groupedCrossValidationKeepsEquivalentPayloadsInOneFold() {
        java.util.List<String> groups = java.util.List.of(
            tw.edu.cse.nsysu.preprocess.PayloadNormalizer.groupKey("select * from users"),
            tw.edu.cse.nsysu.preprocess.PayloadNormalizer.groupKey("SELECT%20*%20FROM%20users;"),
            tw.edu.cse.nsysu.preprocess.PayloadNormalizer.groupKey("select 1"),
            tw.edu.cse.nsysu.preprocess.PayloadNormalizer.groupKey("insert into t values (1)"),
            tw.edu.cse.nsysu.preprocess.PayloadNormalizer.groupKey("delete from t"),
            tw.edu.cse.nsysu.preprocess.PayloadNormalizer.groupKey("update t set x=1"));
        int[] labels = {0, 0, 0, 1, 1, 1};
        int[] folds = StatisticalValidation.stratifiedGroupFolds(groups, labels, 42);
        assertEquals(folds[0], folds[1]);
        assertEquals(6, folds.length);
    }
}
