package tw.edu.cse.nsysu;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.JSQLParserException;

public class FeatureExtract{
    //A function to implement feature extraction
    public static double[] extract(String rawSql){
        //Initialize a vector to store 8 features
        double[] vector=new double[8];
        //Do preprocessing before feature extraction
        String preprocess_sql=preprocess(rawSql);
        //--- Structural feature & Keyword feature ---
        //Length(Structural)
        vector[0]=preprocess_sql.length();
        try{
            Statement stmt=CCJSqlParserUtil.parse(preprocess_sql); //use jsqlparser
            //param count(Structural, Count for the number of "=")
            vector[1]=countChar(stmt.toString(), "=");
            //where clause exists(Structural)
            vector[2]=(stmt.toString().contains("WHERE"))?1:0;
            //SELECT count(Keyword)
            vector[3]=countRegex(stmt.toString(), "SELECT");
            //UNION exist(Keyword)
            vector[4]=(stmt.toString().contains("UNION"))?1:0;
        }catch(JSQLParserException e){
            //JSQLParser can only analyze for correct sql querys, for those with syntax error, use Regex
            vector[1]=countChar(preprocess_sql, "=");
            vector[2]=countRegex(preprocess_sql, "WHERE");
            vector[3]=countRegex(preprocess_sql, "SELECT");
            vector[4]=countRegex(preprocess_sql, "UNION");
        }
        //--- Risk Structure ---
        //single quote count
        vector[5]=countRegex(preprocess_sql, "'");
        //comment count
        vector[6]=countRegex(preprocess_sql, "--")+countRegex(preprocess_sql, "#");
        //--- Logic Structure ---
        //Or ratio
        int OrCount=countRegex(preprocess_sql, "OR");
        int wordCount=preprocess_sql.trim().split("\\s+").length;
        vector[7]=(double)OrCount/(wordCount>0? wordCount:1);
        return vector;
    } 
    //A function to do preprocessing
    private static String preprocess(String sql){
        if(sql==null||sql.isEmpty()) 
            return "";
        String decode_sql=sql;
        try{
            //Do URL decoding
            decode_sql=URLDecoder.decode(sql, StandardCharsets.UTF_8.toString());
        }catch(Exception e){
            decode_sql=sql;
        }
        try{
            //transfer to uppercase
            String new_sql=decode_sql.toUpperCase();
            //remove newline and tab(\r, \t, \n) and transfer to a space
            new_sql=new_sql.replaceAll("[\\r\\n\\t]+"," ");
            //combine multiple spaces into one
            new_sql=new_sql.replaceAll("\\s+", " ");
            //Remove head and tail spaces
            new_sql=new_sql.trim();
            return new_sql;
        }catch(Exception e){  
            return sql==null?"":sql;
        } 
    }   
    //A function to count the symbol number
    private static int countChar(String sql, String symbol){
        if(sql==null||symbol==null||symbol.isEmpty()) 
            return 0;
        else 
            //(Original sql length - length after remove the symbol) / the symbol length
            return (sql.length()-sql.replace(symbol, "").length())/symbol.length();
    }
    //A function to count how many time the pattern has appeared
    private static int countRegex(String sql, String target){
        if(sql==null||target==null||target.isEmpty())
            return 0;
        else{
            //Compile the regular expression 
            Pattern p=Pattern.compile("\\b"+target+"\\b");
            //Match to the target 
            Matcher m=p.matcher(sql);
            //Count the target number
            int count=0;
            while(m.find())
                count++;
            return count;
        }
    }
}
