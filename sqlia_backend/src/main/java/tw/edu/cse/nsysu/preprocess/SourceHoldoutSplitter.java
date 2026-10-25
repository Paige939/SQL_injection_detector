package tw.edu.cse.nsysu.preprocess;

import java.util.*;
import java.util.stream.Collectors;

public class SourceHoldoutSplitter {
    // Shared payloads stay on the training side; only held-out-source-exclusive payloads are test data.
    public static Map<String, List<RawRecord>> holdOutBySource(List<RawRecord> records, String heldOutSource) {
        Map<String, List<RawRecord>> result = new HashMap<>();
        Set<String> trainingGroups = records.stream()
                .filter(r -> !hasSource(r.source, heldOutSource) || hasOtherSource(r.source, heldOutSource))
                .map(r -> PayloadNormalizer.groupKey(r.rawText)).collect(Collectors.toSet());
        result.put("train_other_sources", records.stream()
                .filter(r -> !hasSource(r.source, heldOutSource) || hasOtherSource(r.source, heldOutSource))
                .collect(Collectors.toList()));
        result.put("test_holdout_source", records.stream()
                .filter(r -> !trainingGroups.contains(PayloadNormalizer.groupKey(r.rawText)))
                .collect(Collectors.toList()));
        return result;
    }

    // A deduplicated row may contain several source names separated by '|'.
    private static boolean hasSource(String sources, String expected) {
        if (sources == null || expected == null) return false;
        return Arrays.asList(sources.split("\\|")).contains(expected);
    }

    private static boolean hasOtherSource(String sources, String heldOutSource) {
        if (sources == null || heldOutSource == null) return false;
        return Arrays.stream(sources.split("\\|"))
                .anyMatch(source -> !source.equals(heldOutSource));
    }
}