package tw.edu.cse.nsysu.preprocess;

import java.util.regex.Pattern;

/** Descriptive heuristics, not SQL validation or attack labels. */
public final class InputType {
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");
    private static final Pattern HTTP = Pattern.compile(
            "(?is)^(?:GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH)\\s+/.*|^https?://.*");
    private static final Pattern SQL = Pattern.compile(
            "(?is)^(?:select|insert|update|delete|create|alter|drop|with|exec|execute|"
                    + "show|set|begin|commit|rollback|grant|revoke|truncate|explain|replace)\\s+.*");
    private static final Pattern FUNCTION = Pattern.compile("(?s)^[\\w.]+\\s*\\(.*");
    private static final Pattern FRAGMENT = Pattern.compile(
            "(?is)^(?:and|or|union|where)\\b.*|.*(?:--|/\\*|['\"].*[=<>]).*");

    private InputType() {}

    public static String classify(String text) {
        if (text == null || text.isBlank()) return "empty";
        String value = text.trim();
        if (NUMBER.matcher(value).matches()) return "numeric";
        if (EMAIL.matcher(value).matches()) return "email";
        if (HTTP.matcher(value).matches()) return "url_or_http_like";
        if (value.startsWith("<")) return "markup_like";
        if (SQL.matcher(value).matches()) return "sql_like";
        if (FUNCTION.matcher(value).matches()) return "function_like";
        if (FRAGMENT.matcher(value).matches()) return "fragment_like";
        if (value.matches("[\\p{L}\\p{N}_-]+")) return "identifier_or_short_text";
        return "other_text";
    }
}