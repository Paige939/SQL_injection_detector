package tw.edu.cse.nsysu;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FeatureExtract {
    private static final Set<String> FUNCTION_NAMES = Set.of(
            "ASCII", "CHAR", "CHARACTER_LENGTH", "CHAR_LENGTH",
            "CONCAT", "CONCAT_WS", "CONVERT", "COALESCE",
            "CAST", "DATABASE", "EXTRACT", "IF", "IFNULL",
            "ISNULL", "LCASE", "LEFT", "LENGTH", "LOWER",
            "LPAD", "LTRIM", "MID", "NULLIF", "OCTET_LENGTH",
            "POSITION", "REPLACE", "REVERSE", "RIGHT", "RPAD",
            "RTRIM", "SUBSTR", "SUBSTRING", "TRIM", "UCASE",
            "UPPER", "USER", "VERSION", "UNHEX"
    );

    // Pattern to match SQL comparison operators
    private static final Pattern COMPARISON_PATTERN =
            Pattern.compile("(?<![<>!=])(?:!=|<>|>=|<=|=|>|<)(?![<>!=])");
    // Pattern to match SQL comments
    private static final Pattern COMMENT_PATTERN =
            Pattern.compile("--[^\\r\\n]*|#[^\\r\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    public static double[] extract(String rawSql) {
        if (rawSql == null || rawSql.isEmpty()) {
            return new double[6];
        }
        // Trim leading and trailing whitespace from the raw SQL string
        rawSql = rawSql.trim();
        //Decode the raw SQL string 
        try {
            String decodedSql = URLDecoder.decode(rawSql, StandardCharsets.UTF_8);
            return extractFromPayload(decodedSql);
        } catch (IllegalArgumentException e) {
            return extractFromPayload(rawSql);
        }
    }
    // Extract features from the decoded SQL payload
    private static double[] extractFromPayload(String sql) {
        double[] features = new double[6];

        if (sql == null || sql.isEmpty()) {
            return features;
        }
        //The payload length
        int payloadLength = sql.length();
        //Ensure that the dominator will be at least 1
        int denominator = Math.max(payloadLength, 1);
        //Transfer to uppercase
        String upperSql = sql.toUpperCase(Locale.ROOT);

        features[0] = payloadLength; // Feature 0: Length of the SQL payload
        features[1] = (double) countSymbol(sql) / denominator; // Feature 1: Symbol density
        features[2] = (double) countComparisons(sql) / denominator; // Feature 2: Comparison operator density
        features[3] = countComplex(sql); // Feature 3: Complexity (presence of letters, digits, symbols)
        features[4] = (double) functionCallCount(upperSql, FUNCTION_NAMES)
                / denominator; // Feature 4: Function call density
        features[5] = (double) countDiscontinuous(sql) / denominator; // Feature 5: Discontinuous character density

        return features;
    }
    // Count the number of symbols in the SQL payload
    private static int countSymbol(String sql) {
        int count = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (!Character.isLetterOrDigit(c) && !Character.isWhitespace(c)) {
                count++;
            }
        }

        return count;
    }

    // Count the number of comparison operators in the SQL payload
    private static int countComparisons(String sql) {
        Matcher matcher = COMPARISON_PATTERN.matcher(sql);
        int count = 0;

        while (matcher.find()) {
            count++;
        }

        return count;
    }

    // Count the complexity of the SQL payload (presence of letters, digits, symbols)
    private static int countComplex(String sql) {
        int hasAlpha = 0;
        int hasDigit = 0;
        int hasSymbol = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (Character.isLetter(c)) {
                hasAlpha = 1;
            } else if (Character.isDigit(c)) {
                hasDigit = 1;
            } else if (!Character.isWhitespace(c)
                    && !Character.isISOControl(c)) {
                hasSymbol = 1;
            }
        }

        return hasAlpha + hasDigit + hasSymbol;
    }

    // Count the number of function calls in the SQL payload
    private static int functionCallCount(
            String sql,
            Set<String> functionNames) {
        int count = 0;

        for (String functionName : functionNames) {
            Pattern pattern = Pattern.compile(
                    "\\b" + Pattern.quote(functionName) + "\\s*\\("
            );
            Matcher matcher = pattern.matcher(sql);

            while (matcher.find()) {
                count++;
            }
        }

        return count;
    }

    // Count the number of discontinuous characters (whitespace, control characters, comments) in the SQL payload
    private static int countDiscontinuous(String sql) {
        int count = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                count++;
            }
        }

        /*Matcher commentMatcher = COMMENT_PATTERN.matcher(sql);
        while (commentMatcher.find()) {
            count++;
        }*/

        return count;
    }
}
