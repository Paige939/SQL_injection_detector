package tw.edu.cse.nsysu;

import ca.pfv.spmf.algorithms.associationrules.agrawal94_association_rules.AlgoAgrawalFaster94;
import ca.pfv.spmf.algorithms.frequentpatterns.fpgrowth.AlgoFPGrowth;
import ca.pfv.spmf.algorithms.sequentialpatterns.prefixspan.AlgoPrefixSpan;
import ca.pfv.spmf.patterns.itemset_array_integers_with_count.Itemsets;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extended association rule mining for SQL injection.
 *
 * Compared with the standard "feature items => class" rules, three extensions are added:
 * 1. Token items: SQL attack tokens (UNION, comments, tautologies, ...) become transaction items.
 * 2. Negative items: the absence of an item (item + NEG_OFFSET) can appear in a rule antecedent.
 * 3. Sequential rules: ordered token subsequences such as {' , OR, --} => SQLi, mined with PrefixSpan.
 * Every rule also carries support, confidence and lift, and can be filtered by lift.
 */
public final class ExtendedRuleMiner {
    public static final int TOKEN_ITEM_BASE = 200;
    public static final int NEG_OFFSET = 1000;
    private static final int[] BASE_ITEMS = {1, 2, 3, 30, 33, 40, 41, 42, 43, 5, 50, 55, 6};
    private static final int MAX_TOKENS = 40;

    public enum ItemMode { BASE, BASE_TOKEN, BASE_TOKEN_NEG }

    // Each regex is one token type; its token id is its 1-based position (SPMF items must be > 0).
    private static final String[] TOKEN_REGEX = {
        "'", "\"",
        "\\bor\\b", "\\band\\b", "\\bunion\\b", "\\bselect\\b", "\\bfrom\\b", "\\bwhere\\b",
        "\\bdrop\\b", "\\binsert\\b", "\\bupdate\\b", "\\bdelete\\b",
        "--|#|/\\*", ";",
        "\\d+\\s*=\\s*\\d+|'[^']*'\\s*=\\s*'[^']*'",
        "\\bsleep\\b|\\bbenchmark\\b|\\bwaitfor\\b",
        "information_schema|sysobjects|\\bxp_\\w+|\\bpg_\\w+",
        "\\b0x[0-9a-f]+\\b", "\\bnull\\b", "\\blike\\b",
        "\\border\\s+by\\b|\\bgroup\\s+by\\b",
        "\\bconcat\\s*\\(|\\bchar\\s*\\(",
        "\\(", "\\)", "=", "\\blimit\\b", "\\bhaving\\b", "\\bexec(?:ute)?\\b"
    };
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
        java.util.Arrays.stream(TOKEN_REGEX).map(r -> "(" + r + ")").collect(Collectors.joining("|")));
    public static final int TOKEN_COUNT = TOKEN_REGEX.length;

    private ExtendedRuleMiner() {}

    public static final class ExtendedRule implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final int[] antecedent;
        public final boolean sequential;
        public final double support;
        public final double confidence;
        public final double lift;

        public ExtendedRule(int[] antecedent, boolean sequential,
                            double support, double confidence, double lift) {
            this.antecedent = antecedent.clone();
            this.sequential = sequential;
            this.support = support;
            this.confidence = confidence;
            this.lift = lift;
        }

        public boolean hasNegativeItem() {
            if (sequential) return false;
            for (int item : antecedent) if (item >= NEG_OFFSET) return true;
            return false;
        }

        // Match against precomputed items (itemset rule) or the token sequence (sequential rule).
        public boolean matches(Set<Integer> items, List<Integer> tokens) {
            if (!sequential) {
                for (int item : antecedent) if (!items.contains(item)) return false;
                return true;
            }
            int next = 0;
            for (int token : tokens) {
                if (next < antecedent.length && token == antecedent[next]) next++;
            }
            return next == antecedent.length;
        }
    }

    // Decode like FeatureExtract so both pipelines see the same payload.
    private static String decode(String rawSql) {
        String sql = rawSql == null ? "" : rawSql.trim();
        try {
            return URLDecoder.decode(sql, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return sql;
        }
    }

    // Ordered token sequence with consecutive duplicates collapsed.
    public static List<Integer> tokenize(String rawSql) {
        List<Integer> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(decode(rawSql).toLowerCase(Locale.ROOT));
        while (matcher.find() && tokens.size() < MAX_TOKENS) {
            for (int g = 1; g <= TOKEN_COUNT; g++) {
                if (matcher.group(g) != null) {
                    if (tokens.isEmpty() || tokens.get(tokens.size() - 1) != g) tokens.add(g);
                    break;
                }
            }
        }
        return tokens;
    }

    // Transaction items for one query; class items are never included.
    public static Set<Integer> items(String rawSql, List<Integer> tokens, ItemMode mode) {
        Set<Integer> items = new LinkedHashSet<>(FeatureForML.toTransactionSet(FeatureExtract.extract(rawSql)));
        if (mode == ItemMode.BASE) return items;
        for (int token : tokens) items.add(TOKEN_ITEM_BASE + token);
        if (mode == ItemMode.BASE_TOKEN_NEG) {
            Set<Integer> present = new LinkedHashSet<>(items);
            for (int item : BASE_ITEMS) if (!present.contains(item)) items.add(NEG_OFFSET + item);
            for (int t = 1; t <= TOKEN_COUNT; t++) {
                if (!present.contains(TOKEN_ITEM_BASE + t)) items.add(NEG_OFFSET + TOKEN_ITEM_BASE + t);
            }
        }
        return items;
    }

    private static final int CANDIDATE_POOL = 2000;

    /**
     * Keeps at most maxRules rules. Candidates are pre-ranked by lift then support, and rules are
     * picked greedily by how many not-yet-covered SQLi training rows they match, so redundant
     * sub/super-rules of an already selected rule are dropped.
     */
    private static List<ExtendedRule> selectRules(List<ExtendedRule> rules, List<Set<Integer>> items,
            List<List<Integer>> tokens, int[] labels, int maxRules) {
        List<ExtendedRule> pool = rules.stream()
            .sorted(Comparator.comparingDouble((ExtendedRule r) -> r.lift).reversed()
                .thenComparing(Comparator.comparingDouble((ExtendedRule r) -> r.support).reversed()))
            .limit(CANDIDATE_POOL).toList();
        int n = labels.length;
        List<java.util.BitSet> covers = new ArrayList<>();
        for (ExtendedRule rule : pool) {
            java.util.BitSet cover = new java.util.BitSet(n);
            for (int i = 0; i < n; i++) {
                if (labels[i] == 1 && rule.matches(items == null ? Set.of() : items.get(i),
                        tokens == null ? List.of() : tokens.get(i))) cover.set(i);
            }
            covers.add(cover);
        }
        java.util.BitSet covered = new java.util.BitSet(n);
        boolean[] used = new boolean[pool.size()];
        List<ExtendedRule> selected = new ArrayList<>();
        while (selected.size() < maxRules) {
            int best = -1;
            int bestGain = 0;
            for (int k = 0; k < pool.size(); k++) {
                if (used[k]) continue;
                java.util.BitSet gain = (java.util.BitSet) covers.get(k).clone();
                gain.andNot(covered);
                if (gain.cardinality() > bestGain) {
                    bestGain = gain.cardinality();
                    best = k;
                }
            }
            if (best < 0) break;
            used[best] = true;
            covered.or(covers.get(best));
            selected.add(pool.get(best));
        }
        return selected;
    }

    private static double prior(int[] labels) {
        int sqli = 0;
        for (int l : labels) sqli += l == 1 ? 1 : 0;
        return (double) sqli / Math.max(1, labels.length);
    }

    /**
     * Mines "antecedent => SQLi" itemset rules from the given (training) transactions.
     * Pass minLift = 0 to disable the lift filter (standard confidence-only behaviour).
     */
    public static List<ExtendedRule> mineItemRules(List<Set<Integer>> transactions, int[] labels,
            double minSup, double minConf, double minLift, int maxLen, int maxRules) throws IOException {
        return mineItemRules(transactions, labels, minSup, minConf, minLift, maxLen, maxRules,
                Path.of("data/experiments/.work"));
    }

    public static List<ExtendedRule> mineItemRules(List<Set<Integer>> transactions, int[] labels,
            double minSup, double minConf, double minLift, int maxLen, int maxRules,
            Path workDirectory) throws IOException {
        Files.createDirectories(workDirectory);
        File input = File.createTempFile("ext_items_", ".txt", workDirectory.toFile());
        File output = File.createTempFile("ext_rules_", ".txt", workDirectory.toFile());
        try {
            List<String> lines = new ArrayList<>(transactions.size());
            for (int i = 0; i < transactions.size(); i++) {
                Set<Integer> t = new java.util.TreeSet<>(transactions.get(i));
                t.add(labels[i] == 1 ? FeatureForML.SQLI_LABEL_ITEM : FeatureForML.BENIGN_LABEL_ITEM);
                lines.add(t.stream().map(String::valueOf).collect(Collectors.joining(" ")));
            }
            Files.write(input.toPath(), lines);

            AlgoFPGrowth fp = new AlgoFPGrowth();
            fp.setMaximumPatternLength(maxLen + 1);
            Itemsets patterns = fp.runAlgorithm(input.getAbsolutePath(), null, minSup);
            if (patterns == null) return List.of();
            new AlgoAgrawalFaster94().runAlgorithm(patterns, output.getAbsolutePath(),
                fp.getDatabaseSize(), minConf);

            double prior = prior(labels);
            int n = fp.getDatabaseSize();
            List<ExtendedRule> rules = new ArrayList<>();
            try (BufferedReader br = Files.newBufferedReader(output.toPath())) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split("==>|#SUP:|#CONF:");
                    if (parts.length < 4) continue;
                    if (!parts[1].trim().equals(String.valueOf(FeatureForML.SQLI_LABEL_ITEM))) continue;
                    int[] antecedent = java.util.Arrays.stream(parts[0].trim().split("\\s+"))
                        .mapToInt(Integer::parseInt).toArray();
                    if (java.util.Arrays.stream(antecedent).anyMatch(i -> i == 100 || i == 101)) continue;
                    double confidence = Double.parseDouble(parts[3].trim());
                    double lift = confidence / prior;
                    if (lift < minLift) continue;
                    rules.add(new ExtendedRule(antecedent, false,
                        Integer.parseInt(parts[2].trim()) / (double) n, confidence, lift));
                }
            }
            return selectRules(rules, transactions, null, labels, maxRules);
        } finally {
            input.delete();
            output.delete();
        }
    }

    /** Mines ordered token rules "<t1, t2, ...> => SQLi" with PrefixSpan. */
    public static List<ExtendedRule> mineSequenceRules(List<List<Integer>> sequences, int[] labels,
            double minSup, double minConf, double minLift, int maxLen, int maxRules) throws IOException {
        return mineSequenceRules(sequences, labels, minSup, minConf, minLift, maxLen, maxRules,
                Path.of("data/experiments/.work"));
    }

    public static List<ExtendedRule> mineSequenceRules(List<List<Integer>> sequences, int[] labels,
            double minSup, double minConf, double minLift, int maxLen, int maxRules,
            Path workDirectory) throws IOException {
        Files.createDirectories(workDirectory);
        File input = File.createTempFile("ext_seq_", ".txt", workDirectory.toFile());
        File output = File.createTempFile("ext_seq_out_", ".txt", workDirectory.toFile());
        try {
            List<String> lines = new ArrayList<>(sequences.size());
            for (int i = 0; i < sequences.size(); i++) {
                StringBuilder sb = new StringBuilder();
                for (int token : sequences.get(i)) sb.append(token).append(" -1 ");
                sb.append(labels[i] == 1 ? FeatureForML.SQLI_LABEL_ITEM : FeatureForML.BENIGN_LABEL_ITEM)
                  .append(" -1 -2");
                lines.add(sb.toString());
            }
            Files.write(input.toPath(), lines);

            AlgoPrefixSpan algo = new AlgoPrefixSpan();
            algo.setMaximumPatternLength(maxLen + 1);
            algo.runAlgorithm(input.getAbsolutePath(), minSup, output.getAbsolutePath());

            Map<String, Integer> support = new HashMap<>();
            List<String[]> sqliPatterns = new ArrayList<>();
            try (BufferedReader br = Files.newBufferedReader(output.toPath())) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split("#SUP:");
                    if (parts.length < 2) continue;
                    String pattern = parts[0].trim().replace(" -1", "").trim();
                    support.put(pattern, Integer.parseInt(parts[1].trim()));
                    String[] items = pattern.split("\\s+");
                    if (items.length >= 2 && items[items.length - 1].equals(String.valueOf(FeatureForML.SQLI_LABEL_ITEM))) {
                        sqliPatterns.add(items);
                    }
                }
            }
            double prior = prior(labels);
            int n = sequences.size();
            List<ExtendedRule> rules = new ArrayList<>();
            for (String[] items : sqliPatterns) {
                String full = String.join(" ", items);
                String prefix = String.join(" ", java.util.Arrays.copyOf(items, items.length - 1));
                Integer prefixSupport = support.get(prefix);
                if (prefixSupport == null || prefixSupport == 0) continue;
                double confidence = support.get(full) / (double) prefixSupport;
                double lift = confidence / prior;
                if (confidence < minConf || lift < minLift) continue;
                int[] antecedent = java.util.Arrays.stream(items, 0, items.length - 1)
                    .mapToInt(Integer::parseInt).toArray();
                rules.add(new ExtendedRule(antecedent, true, support.get(full) / (double) n, confidence, lift));
            }
            return selectRules(rules, null, sequences, labels, maxRules);
        } finally {
            input.delete();
            output.delete();
        }
    }

    // One feature per rule: the rule confidence when it matches the query, otherwise 0.
    public static float[] ruleFeatures(List<ExtendedRule> rules, Set<Integer> items, List<Integer> tokens) {
        float[] features = new float[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            ExtendedRule rule = rules.get(i);
            features[i] = rule.matches(items, tokens) ? (float) rule.confidence : 0.0f;
        }
        return features;
    }

    public static double maxMatchedConfidence(
            List<ExtendedRule> rules, Set<Integer> items, List<Integer> tokens) {
        double confidence = 0.0;
        for (ExtendedRule rule : rules) {
            if (rule.matches(items, tokens)) {
                confidence = Math.max(confidence, rule.confidence);
            }
        }
        return confidence;
    }

    // JSON-friendly view of a rule for the API.
    public static Map<String, Object> toMap(ExtendedRule rule) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("type", rule.sequential ? "sequential" : rule.hasNegativeItem() ? "negative" : "itemset");
        map.put("antecedent", java.util.Arrays.stream(rule.antecedent).boxed().toList());
        map.put("description", describe(rule));
        map.put("support", rule.support);
        map.put("confidence", rule.confidence);
        map.put("lift", rule.lift);
        return map;
    }

    // Same order as TOKEN_REGEX; token id n is TOKEN_NAMES[n - 1].
    private static final String[] TOKEN_NAMES = {
        "single quote", "double quote", "OR", "AND", "UNION", "SELECT", "FROM", "WHERE",
        "DROP", "INSERT", "UPDATE", "DELETE", "comment (-- # /*)", "semicolon",
        "tautology (1=1)", "time delay (SLEEP/BENCHMARK)", "system schema", "hex literal", "NULL", "LIKE",
        "ORDER/GROUP BY", "CONCAT/CHAR call", "open paren", "close paren", "equals", "LIMIT", "HAVING", "EXEC"
    };

    public static String itemName(int item) {
        if (item >= NEG_OFFSET) return "NOT " + itemName(item - NEG_OFFSET);
        if (item > TOKEN_ITEM_BASE && item <= TOKEN_ITEM_BASE + TOKEN_COUNT) {
            return "token:" + TOKEN_NAMES[item - TOKEN_ITEM_BASE - 1];
        }
        return switch (item) {
            case 1 -> "long payload";
            case 2 -> "high symbol density";
            case 3 -> "no comparison";
            case 30 -> "low comparison density";
            case 33 -> "high comparison density";
            case 40, 41, 42, 43 -> "complexity " + (item - 40);
            case 5 -> "no function call";
            case 50 -> "low function density";
            case 55 -> "high function density";
            case 6 -> "high discontinuity";
            default -> String.valueOf(item);
        };
    }

    public static String describe(ExtendedRule rule) {
        String body = java.util.Arrays.stream(rule.antecedent)
            .mapToObj(i -> rule.sequential ? (i >= 1 && i <= TOKEN_COUNT ? TOKEN_NAMES[i - 1] : "tok" + i) : itemName(i))
            .collect(Collectors.joining(rule.sequential ? " -> " : ", "));
        return String.format(Locale.ROOT, "{%s} => SQLi (sup=%.3f conf=%.3f lift=%.2f)",
            body, rule.support, rule.confidence, rule.lift);
    }
}
