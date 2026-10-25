package tw.edu.cse.nsysu.preprocess;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class CsvUnifier {
    //Declaration of the source file, including its path, the text column, the label column, and the source name.
    public record SourceSpec(String path, String textColumn, String labelColumn, String sourceName) {}
    public static List<RawRecord> unifyAll(List<SourceSpec> specs) throws IOException {
        List<RawRecord> all = new ArrayList<>();
        for (SourceSpec spec : specs) {
            all.addAll(readOneFile(spec));
        }
        return all;
    }
    //Read one CSV file according to the specification and return a list of RawRecord objects
    private static List<RawRecord> readOneFile(SourceSpec spec) throws IOException {
        List<RawRecord> output = new ArrayList<>();
        Charset detected = detectCharset(Path.of(spec.path())); //Automatically detect UTF-16LE or UTF-8
        try (Reader reader = new InputStreamReader(new FileInputStream(spec.path()),
                    detected.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT));
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader() //First row as header
                .setSkipHeaderRecord(true) //Skip the header row in the output
                .setIgnoreEmptyLines(true) //Ignore empty lines in the CSV
                .setAllowMissingColumnNames(false)
                .setTrim(false)
                .build() 
                .parse(reader)){
                    List<String> headers = parser.getHeaderNames().stream()
                            .map(CsvUnifier::stripBom).toList();
                    if (new HashSet<>(headers).size() != headers.size()) {
                        throw new IOException("Ambiguous duplicate headers in " + spec.path());
                    }
                    int textIndex = headers.indexOf(spec.textColumn());
                    int labelIndex = headers.indexOf(spec.labelColumn());
                    if (textIndex < 0 || labelIndex < 0 || textIndex == labelIndex) {
                        throw new IOException("Missing text/label column in " + spec.path());
                    }
                    for (CSVRecord r : parser) {
                        if (r.size() != headers.size()) {
                            throw new IOException("Unexpected column count in " + spec.path()
                                    + " at CSV record " + r.getRecordNumber()
                                    + "; fix quoting instead of guessing field boundaries.");
                        }
                        RawRecord record = new RawRecord(r.get(textIndex),
                                stripBom(r.get(labelIndex)), spec.sourceName());
                        record.origins.add(new RawRecord.Origin(spec.sourceName(),
                                spec.path(), r.getRecordNumber()));
                        output.add(record);
                    }
                }
        return output;
    }
    //Detect BOM (Byte Order Mark) and determine the charset of the file
    //sqlia.csv begins with BOM (FF FE -> UTF-16LE)
    private static Charset detectCharset(Path path) throws IOException {
        byte[] head = new byte[4];
        try (InputStream in = Files.newInputStream(path)) {
            int n = in.read(head); //Read the first 4 bytes to detect BOM
            //byte range: -127 - 128 (signed byte in Java), need to mask with 0xFF to get the unsigned value 0-255
            //Check if the file starts with BOM for UTF-16LE
            if (n >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
            //Check if the file starts with BOM for UTF-16BE
            if (n >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
            //Check if the file starts with BOM for UTF-8
            if(n >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
                return StandardCharsets.UTF_8;
            }
        }
        //If no BOM is found, default to UTF-8
        return StandardCharsets.UTF_8;
    }

    // Remove a BOM that may be decoded as a visible character at the start of a field.
    private static String stripBom(String value) {
        return value == null ? null : value.replaceFirst("^\\uFEFF", "");
    }
}