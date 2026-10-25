package tw.edu.cse.nsysu.preprocess;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

// Similarity keys are for partitioning only, never proof of semantic equivalence.
public final class PayloadNormalizer {
    private PayloadNormalizer() {}

    // trim -> URL decode -> lowercase -> collapse whitespace
    public static String normalize(String text) {
        if (text == null) return "";
        String t = text.trim();
        try {
            t = URLDecoder.decode(t, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Not valid URL encoding, keep the original text.
        }
        return t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public static String groupKey(String text) {
        return normalize(text).replaceAll("\\s*([=<>!(),;])\\s*", "$1")
                .replaceFirst(";+$", "").trim();
    }
}
