package tw.edu.cse.nsysu.preprocess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public class DeduplicatorTest {
    @Test
    public void mergesExactDuplicatesAndRetainsNormalizedVariants() {
        List<RawRecord> records = List.of(
                new RawRecord("SELECT%201", "0", "source-a"),
                new RawRecord("SELECT%201", "0", "source-c"),
                new RawRecord(" select 1 ", "0", "source-b"),
                new RawRecord("SELECT 2", "1", "source-a"));

        List<RawRecord> unique = Deduplicator.dedup(records);

        assertEquals(3, unique.size());
        assertEquals("source-a|source-c", unique.get(0).source);
        assertEquals(2, unique.get(0).occurrences);
        assertEquals(unique.get(0).groupId, unique.get(1).groupId);
        assertEquals("0", unique.get(0).rawLabel);
    }

    @Test
    public void rejectsConflictingLabelsForIdenticalText() {
        try {
            Deduplicator.dedup(List.of(
                    new RawRecord("SELECT 1", "0", "source-a"),
                    new RawRecord("SELECT 1", "1", "source-b")));
            fail("Expected conflicting labels to be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Conflicting labels"));
        }
    }

    @Test
    public void stratifiedSplitsAreUniqueAndPairwiseDisjoint() {
        List<RawRecord> unique = Deduplicator.dedup(List.of(
                new RawRecord("SELECT%201", "0", "source-a"),
                new RawRecord(" select 1 ", "0", "source-b"),
                new RawRecord("SELECT 2", "0", "source-a"),
                new RawRecord("SELECT 3", "1", "source-a"),
                new RawRecord("SELECT 4", "1", "source-b"),
                new RawRecord("SELECT 5", "1", "source-b")));

        StratifiedSplitter.Split split =
                StratifiedSplitter.splitByLabelAndGroup(unique, 0.5, 0.25, 42L);
        Set<String> train = normalizedPayloads(split.train());
        Set<String> validation = normalizedPayloads(split.val());
        Set<String> test = normalizedPayloads(split.test());

        assertEquals(unique.size(), split.train().size() + split.val().size() + split.test().size());
        assertFalse(intersects(train, validation));
        assertFalse(intersects(train, test));
        assertFalse(intersects(validation, test));
    }

    @Test
    public void sourceHoldoutExcludesPayloadsSharedWithOtherSourcesFromTest() {
        List<RawRecord> records = List.of(
                new RawRecord("SELECT 1", "0", "SQLiV3|Modified_SQL_Dataset"),
                new RawRecord("SELECT 2", "1", "SQLiV3"),
                new RawRecord("SELECT 3", "0", "Modified_SQL_Dataset"));

        Map<String, List<RawRecord>> split =
                SourceHoldoutSplitter.holdOutBySource(records, "SQLiV3");

        assertEquals(List.of("SELECT 1", "SELECT 3"),
                split.get("train_other_sources").stream()
                        .map(r -> r.rawText).collect(Collectors.toList()));
        assertEquals(List.of("SELECT 2"),
                split.get("test_holdout_source").stream()
                        .map(r -> r.rawText).collect(Collectors.toList()));
    }

    private static Set<String> normalizedPayloads(List<RawRecord> records) {
        return records.stream()
                .map(record -> PayloadNormalizer.groupKey(record.rawText))
                .collect(Collectors.toSet());
    }

    private static boolean intersects(Set<String> first, Set<String> second) {
        return first.stream().anyMatch(second::contains);
    }
}
