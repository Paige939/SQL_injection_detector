package tw.edu.cse.nsysu.preprocess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Deduplicator {
    public record Result(List<RawRecord> records, List<RawRecord> conflicts, int merged) {}

    public static Result partition(List<RawRecord> records) {
        Map<String, List<RawRecord>> exact = new LinkedHashMap<>();
        for (RawRecord record : records) {
            exact.computeIfAbsent(record.rawText, key -> new ArrayList<>()).add(record);
        }
        List<RawRecord> unique = new ArrayList<>();
        List<RawRecord> conflicts = new ArrayList<>();
        int merged = 0;
        for (List<RawRecord> group : exact.values()) {
            RawRecord first = group.getFirst();
            if (group.stream().anyMatch(r -> !first.rawLabel.trim().equals(r.rawLabel.trim()))) {
                conflicts.addAll(group);
                continue;
            }
            RawRecord combined = new RawRecord(first.rawText, first.rawLabel.trim(), first.source);
            Set<String> sources = new LinkedHashSet<>();
            combined.occurrences = 0;
            for (RawRecord row : group) {
                sources.addAll(Arrays.asList(row.source.split("\\|")));
                combined.origins.addAll(row.origins);
                combined.occurrences += row.occurrences;
            }
            combined.source = String.join("|", sources);
            unique.add(combined);
            merged += group.size() - 1;
        }
        System.out.println("Deduplicator: merged " + merged + " exact duplicates; quarantined "
                + conflicts.size() + " conflicting rows.");
        return new Result(unique, conflicts, merged);
    }

    public static List<RawRecord> dedup(List<RawRecord> records) {
        Result result = partition(records);
        if (!result.conflicts().isEmpty()) {
            throw new IllegalArgumentException("Conflicting labels for identical text; "
                    + "use partition() to inspect quarantined records.");
        }
        return result.records();
    }
}
