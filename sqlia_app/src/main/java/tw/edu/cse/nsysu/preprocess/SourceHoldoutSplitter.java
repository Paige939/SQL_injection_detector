package tw.edu.cse.nsysu.preprocess;

import java.util.*;
import java.util.stream.Collectors;

public class SourceHoldoutSplitter {
    // Hold out all records from a specific source for testing, and use the rest for training
    // Verify the model's performance on unseen data sources
    public static Map<String, List<RawRecord>> holdOutBySource(List<RawRecord> records, String heldOutSource) {
        Map<String, List<RawRecord>> result = new HashMap<>();
        result.put("train_other_sources", records.stream()
                .filter(r -> !hasSource(r.source, heldOutSource))
                .collect(Collectors.toList()));
        result.put("test_holdout_source", records.stream()
                .filter(r -> hasSource(r.source, heldOutSource))
                .collect(Collectors.toList()));
        return result;
    }

    // A deduplicated row may contain several source names separated by '|'.
    private static boolean hasSource(String sources, String expected) {
        if (sources == null || expected == null) return false;
        return Arrays.asList(sources.split("\\|")).contains(expected);
    }
}