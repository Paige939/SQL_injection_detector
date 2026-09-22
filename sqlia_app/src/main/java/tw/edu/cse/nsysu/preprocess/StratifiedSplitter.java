package tw.edu.cse.nsysu.preprocess;

import java.util.*;

public class StratifiedSplitter {
    public record Split(List<RawRecord> train, List<RawRecord> val, List<RawRecord> test) {}
    //Grouping according to the normalized text, and divide groups based on label distribution to avoid the same payload leaking to different sets
    public static Split splitByLabelAndGroup(List<RawRecord> records, double trainRatio, double valRatio, long seed) {
        if (trainRatio < 0.0 || valRatio < 0.0 || trainRatio + valRatio > 1.0) {
            throw new IllegalArgumentException("Train and validation ratios must sum to at most 1.");
        }
        //Use normalized text as group keys
        Map<String, List<RawRecord>> groups = new LinkedHashMap<>();
        for (RawRecord r : records) {
            groups.computeIfAbsent(normalizeKey(r.rawText), k -> new ArrayList<>()).add(r);
        }
        //Label in each group decided by majority vote as the splitting criterion
        Map<String, List<String>> byLabel = new HashMap<>();
        for (var entry : groups.entrySet()) {
            String majorityLabel = majorityLabel(entry.getValue());
            byLabel.computeIfAbsent(majorityLabel, k -> new ArrayList<>()).add(entry.getKey());
        }
        List<RawRecord> train = new ArrayList<>(), val = new ArrayList<>(), test = new ArrayList<>();
        Random rnd = new Random(seed);

        //Split each label group into train, val, and test sets, maintain the unified label ratio in train/test/val
        for (var entry : byLabel.entrySet()) {
            List<String> keys = new ArrayList<>(entry.getValue());
            Collections.shuffle(keys, rnd);
            int n = keys.size();
            int trainEnd = (int) Math.round(n * trainRatio);
            int valEnd = trainEnd + (int) Math.round(n * valRatio);
            for (int i = 0;i < n; i++) {
                List<RawRecord> groupRecords = groups.get(keys.get(i));
                if(i < trainEnd) train.addAll(groupRecords);
                else if(i < valEnd) val.addAll(groupRecords);
                else test.addAll(groupRecords);
            }
        }
        return new Split(train, val, test);
    }
    //Normalize the text to create a consistent key for grouping
    private static String normalizeKey(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
    //Determine the majority label in a group of records
    private static String majorityLabel(List<RawRecord> group){
        long ones = group.stream().filter(r -> "1".equals(r.rawLabel.trim())).count();
        return ones * 2 >= group.size() ? "1" : "0";
    }
    
}