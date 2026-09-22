package tw.edu.cse.nsysu.api.service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RuleMiningService{
    //format: "3 ==> 4 #SUP: 5424 #CONF: 0.6467151544056278"
    private static final Pattern RULE_PATTERN =
        Pattern.compile("(.+?)\\s*==>\\s*(.+?)\\s*#SUP:\\s*(\\d+)\\s*#CONF:\\s*([\\d.]+)");
    //format: "3 4 #SUP: 5424" (Eclat frequent itemsets, no antecedent/consequent split)
    private static final Pattern ITEMSET_PATTERN =
        Pattern.compile("(.+?)\\s*#SUP:\\s*(\\d+)");
    //A DTO class to represent a rule with antecedent, consequent, support, and confidence
    public static class RuleDto{
        public List<Integer> antecedent = new ArrayList<>();
        public List<Integer> consequent = new ArrayList<>();
        public int support;
        public double confidence;
    }
    //Parse a rule file and return a list of RuleDto objects representing the rules
    public List<RuleDto> parseRuleFile(String filePath) throws Exception{
        List<RuleDto> rules = new ArrayList<>();
        //Read the rule file line by line and match each line against the RULE_PATTERN to extract antecedent, consequent, support, and confidence
        try(BufferedReader br = new BufferedReader(new FileReader(filePath))){
            String line;
            while((line = br.readLine()) != null){
                Matcher matcher = RULE_PATTERN.matcher(line.trim());
                if(matcher.matches()){
                    RuleDto rule = new RuleDto();
                    for(String s : matcher.group(1).trim().split("\\s+")){
                        rule.antecedent.add(Integer.parseInt(s));
                    }
                    for(String s : matcher.group(2).trim().split("\\s+")){
                        rule.consequent.add(Integer.parseInt(s));
                    }
                    rule.support = Integer.parseInt(matcher.group(3));
                    rule.confidence = Double.parseDouble(matcher.group(4));
                    rules.add(rule);
                }
            }
        }
        return rules;
    }
    //Parse an Eclat itemset file (frequent items, no rules) and return each itemset as a RuleDto with an empty consequent
    public List<RuleDto> parseItemsetFile(String filePath) throws Exception{
        List<RuleDto> rules = new ArrayList<>();
        try(BufferedReader br = new BufferedReader(new FileReader(filePath))){
            String line;
            while((line = br.readLine()) != null){
                Matcher matcher = ITEMSET_PATTERN.matcher(line.trim());
                if(matcher.matches()){
                    RuleDto rule = new RuleDto();
                    for(String s : matcher.group(1).trim().split("\\s+")){
                        rule.antecedent.add(Integer.parseInt(s));
                    }
                    rule.support = Integer.parseInt(matcher.group(2));
                    rule.confidence = 1.0;
                    rules.add(rule);
                }
            }
        }
        return rules;
    }
}