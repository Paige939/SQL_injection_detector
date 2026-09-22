package tw.edu.cse.nsysu.preprocess;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Deduplicator {
    // Remove duplicates within one source while preserving the same payload in other sources.
    public static List<RawRecord> dedup(List<RawRecord> records) {
        Map<String, RawRecord> seen = new LinkedHashMap<>();
        int duplicates = 0;
        for (RawRecord r : records) {
            String key = r.source + "\u0000" + normalize(r.rawText);
            if(seen.containsKey(key)) {
                duplicates++;
                continue;
            }
            seen.put(key, r);
        }
        System.out.println("Duplicates removed: " + duplicates + "cross-dataset duplicates.");
        return new ArrayList<>(seen.values());
    }
    //Normalization: trim -> URL decode -> lowercase -> remove duplicate spaces
    private static String normalize(String text) {
        if (text == null) return "";
        String t = text.trim();
        try {
            t = URLDecoder.decode(t, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Not valid URL encoding, remain original one
        }
        return t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

}