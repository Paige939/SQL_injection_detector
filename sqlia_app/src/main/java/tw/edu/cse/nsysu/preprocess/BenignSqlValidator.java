package tw.edu.cse.nsysu.preprocess;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import java.util.List;
import java.util.regex.Pattern;

public class BenignSqlValidator {
    //Rough detection of whether a HTTP request (Contains method + path or query string structure)
    private static final Pattern HTTP_LIKE =
            Pattern.compile("^(GET|POST|PUT|DELETE|HEAD)\\s+/|[?&][a-zA-Z0-9_]+=");
    //data with label = 0 need verification since malicious samples may be a invalid SQL injection attempt
    public static void markBenignValidity(List<RawRecord> records) {
        for (RawRecord r : records) {
            if(!"0".equals(r.rawLabel.trim())) continue; //skip malicious or non-benign samples
            r.benignValid = isValidSqlOrHttp(r.rawText);
        }
    }
    private static boolean isValidSqlOrHttp(String text) {
        if (text == null || text.isBlank()) return false;
        if (HTTP_LIKE.matcher(text).find()) return true; //Looks like a valid HTTP request
        try {
            CCJSqlParserUtil.parse(text); //If can be analyzed by jsqlparder, considered to be valid SQL
            return true;
        }catch(Exception e){
            return false;  //Otherwise
        }
    }
}