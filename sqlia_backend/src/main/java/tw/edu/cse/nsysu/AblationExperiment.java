package tw.edu.cse.nsysu;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tw.edu.cse.nsysu.ExtendedRuleMiner.ExtendedRule;
import tw.edu.cse.nsysu.ExtendedRuleMiner.ItemMode;
import weka.classifiers.Classifier;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;

/**
 * Ablation study of the extended association rules. Rules are mined from split_group='train'
 * only (exact text-and-label duplicates removed within each split). Rule parameters are tuned on split_group='validation'; the test split is used once,
 * with the chosen parameters, for the final numbers.
 */
public class AblationExperiment {
    private static final double MIN_CONF = 0.6;
    private static final int MAX_LEN = 3;
    private static final String OUTPUT = "data/processed/ablation_results.csv";
    private static final String TUNING_OUTPUT = "data/processed/tuning_results.csv";

    record Params(double minSup, double minLift, int maxRules) {}

    static final class Dataset {
        final List<String> sql = new ArrayList<>();
        final List<String> groupKeys = new ArrayList<>();
        final List<Integer> label = new ArrayList<>();
        final List<double[]> base = new ArrayList<>();
        final List<List<Integer>> tokens = new ArrayList<>();
        int[] labels() { return label.stream().mapToInt(Integer::intValue).toArray(); }
        Dataset subset(int[] indices) {
            Dataset out = new Dataset();
            for (int i : indices) {
                out.sql.add(sql.get(i));
                out.groupKeys.add(groupKeys.get(i));
                out.label.add(label.get(i));
                out.base.add(base.get(i));
                out.tokens.add(tokens.get(i));
            }
            return out;
        }
    }

    enum Config {
        BASE("Base features only"),
        STD_ARM("Standard ARM (confidence only)"),
        STD_ARM_LIFT("Standard ARM + lift filter"),
        TOKEN_ITEMS("+ token items"),
        TOKEN_NEG("+ token items + negative items"),
        SEQ("+ sequential rules"),
        EXTENDED("+ token + negative + sequential");
        final String description;
        Config(String description) { this.description = description; }
    }

    public static void main(String[] args) throws Exception {
        Dataset train;
        Dataset validation;
        Dataset test;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:db/SQLIA.db")) {
            train = load(conn, "train");
            validation = load(conn, "validation");
            test = load(conn, "test");
        }
        System.out.println("train=" + train.sql.size() + " validation=" + validation.sql.size()
            + " test=" + test.sql.size());

        Params best = tune(train, validation);
        System.out.println("\nSelected on validation: " + best);

        List<String> rows = new ArrayList<>();
        rows.add("config,split,classifier,precision,recall,f1,fpr,accuracy,rule_count,description");
        for (Config config : Config.values()) {
            List<ExtendedRule> rules = mine(config, train, best);
            System.out.println("\n== " + config + " (" + config.description + "): " + rules.size() + " rules");
            rules.stream().limit(5).forEach(r -> System.out.println("   " + ExtendedRuleMiner.describe(r)));

            float[][] trainX = features(config, train, rules);
            // The train rows show the fit on seen data, to compare with validation/test for over/underfitting.
            for (Dataset eval : List.of(train, validation, test)) {
                String split = eval == train ? "train" : eval == validation ? "validation" : "test";
                float[][] evalX = features(config, eval, rules);
                for (String name : List.of("NaiveBayes", "RandomForest")) {
                    int[] cm = evaluate(name, trainX, train.labels(), evalX, eval.labels());
                    String row = row(config, split, name, cm, rules.size());
                    System.out.println("   " + row);
                    rows.add(row);
                }
            }
        }
        Files.write(Path.of(OUTPUT), rows);
        System.out.println("\nResults written to " + OUTPUT);
    }

    // Grid search on the validation split for the EXTENDED configuration (RandomForest).
    private static Params tune(Dataset train, Dataset validation) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("min_sup,min_lift,max_rules,rule_count,precision,recall,f1,fpr");
        Params best = null;
        double bestF1 = -1;
        double bestFpr = 1;
        for (double minSup : new double[] {0.03, 0.05, 0.1}) {
            for (double minLift : new double[] {1.0, 1.2, 1.5}) {
                for (int maxRules : new int[] {25, 50, 100}) {
                    Params params = new Params(minSup, minLift, maxRules);
                    List<ExtendedRule> rules = mine(Config.EXTENDED, train, params);
                    int[] cm = evaluate("RandomForest", features(Config.EXTENDED, train, rules), train.labels(),
                        features(Config.EXTENDED, validation, rules), validation.labels());
                    double tp = cm[0], fp = cm[1], tn = cm[2], fn = cm[3];
                    double precision = tp / Math.max(1, tp + fp);
                    double recall = tp / Math.max(1, tp + fn);
                    double f1 = 2 * precision * recall / Math.max(1e-12, precision + recall);
                    double fpr = fp / Math.max(1, fp + tn);
                    rows.add(String.format(Locale.ROOT, "%.2f,%.1f,%d,%d,%.4f,%.4f,%.4f,%.4f",
                        minSup, minLift, maxRules, rules.size(), precision, recall, f1, fpr));
                    System.out.println("tune " + params + " rules=" + rules.size()
                        + String.format(Locale.ROOT, " F1=%.4f FPR=%.4f", f1, fpr));
                    if (f1 > bestF1 + 1e-9 || (Math.abs(f1 - bestF1) <= 1e-9 && fpr < bestFpr)) {
                        best = params;
                        bestF1 = f1;
                        bestFpr = fpr;
                    }
                }
            }
        }
        Files.write(Path.of(TUNING_OUTPUT), rows);
        return best;
    }

    static Dataset load(Connection conn, String split) throws Exception {
        Dataset data = new Dataset();
        java.util.Set<String> seenZero = new java.util.HashSet<>();
        java.util.Set<String> seenOne = new java.util.HashSet<>();
        String sql = "SELECT raw_sql, label FROM DataTable WHERE split_group=? ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, split);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String raw = rs.getString(1);
                    int label = rs.getInt(2);
                    java.util.Set<String> seen = label == 0 ? seenZero : seenOne;
                    if (!seen.add(raw)) continue;
                    data.sql.add(raw);
                    data.groupKeys.add(
                        tw.edu.cse.nsysu.preprocess.PayloadNormalizer.groupKey(raw));
                    data.label.add(label);
                    data.base.add(FeatureExtract.extract(raw));
                    data.tokens.add(ExtendedRuleMiner.tokenize(raw));
                }
            }
        }
        return data;
    }

    private static ItemMode itemMode(Config config) {
        return switch (config) {
            case TOKEN_ITEMS -> ItemMode.BASE_TOKEN;
            case TOKEN_NEG, EXTENDED -> ItemMode.BASE_TOKEN_NEG;
            default -> ItemMode.BASE;
        };
    }

    private static List<Set<Integer>> transactions(Config config, Dataset data) {
        List<Set<Integer>> out = new ArrayList<>();
        for (int i = 0; i < data.sql.size(); i++) {
            out.add(ExtendedRuleMiner.items(data.sql.get(i), data.tokens.get(i), itemMode(config)));
        }
        return out;
    }

    static List<ExtendedRule> mine(Config config, Dataset train, Params p) throws Exception {
        if (config == Config.BASE) return List.of();
        List<ExtendedRule> rules = new ArrayList<>();
        double lift = config == Config.STD_ARM ? 0.0 : p.minLift();
        if (config != Config.SEQ) {
            rules.addAll(ExtendedRuleMiner.mineItemRules(transactions(config, train), train.labels(),
                p.minSup(), MIN_CONF, lift, MAX_LEN, p.maxRules()));
        }
        if (config == Config.SEQ || config == Config.EXTENDED) {
            if (config == Config.SEQ) {
                rules.addAll(ExtendedRuleMiner.mineItemRules(transactions(Config.STD_ARM_LIFT, train),
                    train.labels(), p.minSup(), MIN_CONF, p.minLift(), MAX_LEN, p.maxRules()));
            }
            rules.addAll(ExtendedRuleMiner.mineSequenceRules(train.tokens, train.labels(),
                p.minSup(), MIN_CONF, p.minLift(), MAX_LEN, p.maxRules()));
        }
        return rules;
    }

    // Base features followed by one confidence feature per mined rule.
    static float[][] features(Config config, Dataset data, List<ExtendedRule> rules) {
        ItemMode mode = config == Config.SEQ ? ItemMode.BASE : itemMode(config);
        float[][] x = new float[data.sql.size()][];
        for (int i = 0; i < x.length; i++) {
            double[] base = data.base.get(i);
            Set<Integer> items = ExtendedRuleMiner.items(data.sql.get(i), data.tokens.get(i), mode);
            float[] ruleFeatures = ExtendedRuleMiner.ruleFeatures(rules, items, data.tokens.get(i));
            float[] row = new float[base.length + ruleFeatures.length];
            for (int j = 0; j < base.length; j++) row[j] = (float) base[j];
            System.arraycopy(ruleFeatures, 0, row, base.length, ruleFeatures.length);
            x[i] = row;
        }
        return x;
    }

    private static Instances toInstances(String name, float[][] x, int[] y) {
        int dim = x[0].length;
        ArrayList<Attribute> attrs = new ArrayList<>();
        for (int i = 0; i < dim; i++) attrs.add(new Attribute("f_" + i));
        attrs.add(new Attribute("label", List.of("0", "1")));
        Instances data = new Instances(name, attrs, x.length);
        data.setClassIndex(dim);
        for (int i = 0; i < x.length; i++) {
            double[] values = new double[dim + 1];
            for (int j = 0; j < dim; j++) values[j] = x[i][j];
            values[dim] = y[i];
            data.add(new DenseInstance(1.0, values));
        }
        return data;
    }

    // Predicted labels of the chosen classifier on the evaluation features.
    static int[] predict(String name, float[][] trainX, int[] trainY, float[][] evalX, int[] evalY)
            throws Exception {
        Classifier model;
        if (name.equals("NaiveBayes")) {
            model = new NaiveBayes();
        } else {
            RandomForest forest = new RandomForest();
            forest.setNumIterations(50);
            forest.setSeed(42);
            model = forest;
        }
        model.buildClassifier(toInstances("train", trainX, trainY));
        Instances eval = toInstances("eval", evalX, evalY);
        int[] predicted = new int[eval.size()];
        for (int i = 0; i < predicted.length; i++) predicted[i] = (int) model.classifyInstance(eval.get(i));
        return predicted;
    }

    // Returns {TP, FP, TN, FN} for malicious=1.
    static int[] confusion(int[] predicted, int[] truth) {
        int tp = 0, fp = 0, tn = 0, fn = 0;
        for (int i = 0; i < truth.length; i++) {
            if (truth[i] == 1) { if (predicted[i] == 1) tp++; else fn++; }
            else { if (predicted[i] == 1) fp++; else tn++; }
        }
        return new int[] {tp, fp, tn, fn};
    }

    private static int[] evaluate(String name, float[][] trainX, int[] trainY,
                                  float[][] testX, int[] testY) throws Exception {
        return confusion(predict(name, trainX, trainY, testX, testY), testY);
    }

    private static String row(Config config, String split, String classifier, int[] cm, int ruleCount) {
        double tp = cm[0], fp = cm[1], tn = cm[2], fn = cm[3];
        double precision = tp / Math.max(1, tp + fp);
        double recall = tp / Math.max(1, tp + fn);
        double f1 = 2 * precision * recall / Math.max(1e-12, precision + recall);
        double fpr = fp / Math.max(1, fp + tn);
        double accuracy = (tp + tn) / (tp + fp + tn + fn);
        return String.format(Locale.ROOT, "%s,%s,%s,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%s",
            config, split, classifier, precision, recall, f1, fpr, accuracy, ruleCount, config.description);
    }
}
