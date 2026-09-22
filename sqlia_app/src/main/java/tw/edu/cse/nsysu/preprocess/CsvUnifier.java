package tw.edu.cse.nsysu.preprocess;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import java.io.*;
import java.nio.charset.Charset;
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
    private static List<RawRecord> readOneFile(SourceSpec spec) throws IOException {
        List<RawRecord> output = new ArrayList<>();
        Charset detected = detectCharset(Path.of(spec.path())); //Automatically detect UTF-16LE or UTF-8
        try (Reader reader = new InputStreamReader(new FileInputStream(spec.path()), detected);
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader() //First row as header
                .setSkipHeaderRecord(true) //Skip the header row in the output
                .setIgnoreEmptyLines(true) //Ignore empty lines in the CSV
                .setAllowMissingColumnNames(true) //Allow columns without names
                .setTrim(true) //Trim leading and trailing whitespaces
                .build() 
                .parse(reader)){
                    for (CSVRecord r : parser) {
                        //Process the problems of extra column
                        //CSVRecord's column number exceeds the header cloumn number, needs to do combination of extra columns
                        String text = extractTextWithExtraColumns(r, parser.getHeaderNames(), spec.textColumn());
                        String label = safeGet(r, spec.labelColumn());
                        if (text == null) continue; //If the entire row is not valid, skip it
                        output.add(new RawRecord(stripBom(text), stripBom(label), spec.sourceName()));
                    }
                }
        return output;
    }
    //If the actual column number > header column number, combine all the columns after textColumn and before labelColumn with a comma
    private static String extractTextWithExtraColumns(CSVRecord r, List<String> headers, String textColumn) {
        int textIdx = -1;
        for (int i = 0; i < headers.size(); i++) {
            if (stripBom(headers.get(i)).equals(textColumn)) {
                textIdx = i;
                break;
            }
        }
        if (textIdx < 0 || textIdx >= r.size()) return null;
        if (r.size() == headers.size()) { //When actual column number = header column number
            return r.get(textIdx);
        }
        //When actual column number > header column number, combine the extra column with the textColumn
        StringBuilder combine = new StringBuilder(r.get(textIdx));
        for (int i = textIdx + 1; i < r.size()-1; i++) {
            combine.append(",").append(r.get(i));
        }
        return combine.toString();
    }
    //Safely get the value of a column, return the last column if not found, return null if any exception occurs
    private static String safeGet(CSVRecord r, String column) {
        try {
            return r.isSet(column) ? r.get(column) : r.get(r.size()-1); //If not find, return the last column
        } catch (Exception e) {
            return null; //If any exception occurs, return null
        }
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