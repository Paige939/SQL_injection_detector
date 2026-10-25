package tw.edu.cse.nsysu.preprocess;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class RawRecord {
    public final String rawText;
    public String rawLabel; //raw label (not validate with either 0 or 1)
    public String source; //source dataset name
    public boolean benignValid = true; // Optional syntax diagnostic; never a dataset exclusion rule.
    public final String recordId; // SHA-256 digest of the raw text
    public final String normalizedText; // Normalized version of the raw text for grouping purposes
    public final String groupId; // SHA-256 digest of the normalized text for grouping purposes
    public final String inputType; // Classified input type of the raw text
    public final List<Origin> origins = new ArrayList<>();
    public int occurrences = 1;

    public record Origin(String source, String file, long row) {}

    public RawRecord(String rawText, String rawLabel, String source) {
        this.rawText = rawText;
        this.rawLabel = rawLabel;
        this.source = source;
        this.recordId = digest(rawText == null ? "" : rawText);
        this.normalizedText = PayloadNormalizer.groupKey(rawText);
        this.groupId = digest(normalizedText);
        this.inputType = InputType.classify(rawText);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for dataset identifiers.", e);
        }
    }
}