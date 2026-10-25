package tw.edu.cse.nsysu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import tw.edu.cse.nsysu.AblationExperiment.Config;
import tw.edu.cse.nsysu.AblationExperiment.Dataset;
import tw.edu.cse.nsysu.AblationExperiment.Params;
import tw.edu.cse.nsysu.ExtendedRuleMiner.ExtendedRule;

/**
 * Statistical validation of the extended rules (RandomForest).
 * 1. Repeated stratified group k-fold CV on the train split only (rules re-mined inside every fold),
 *    compared with the Nadeau-Bengio corrected resampled t-test.
 * 2. On the untouched test split: exact McNemar tests and a paired bootstrap CI.
 * Rule parameters come from the validation grid search (tuning_results.csv).
 */
public class StatisticalValidation {
    private static final int FOLDS = 5;
    private static final int REPEATS = 2;
    private static final int BOOTSTRAPS = 2000;
    private static final String CLASSIFIER = "RandomForest";
    private static final String OUTPUT = "data/processed/statistical_results.csv";
    private static final List<Config> CONFIGS = List.of(Config.BASE, Config.STD_ARM, Config.EXTENDED);

    public static void main(String[] args) throws Exception {
        Dataset train;
        Dataset test;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:db/SQLIA.db")) {
            train = AblationExperiment.load(conn, "train");
            test = AblationExperiment.load(conn, "test");
        }
        Set<String> trainingGroups = new HashSet<>(train.groupKeys);
        if (test.groupKeys.stream().anyMatch(trainingGroups::contains)) {
            throw new IllegalStateException("Train and test splits share a payload group.");
        }
        Params params = loadBestParams();
        System.out.println("params=" + params + " train=" + train.sql.size() + " test=" + test.sql.size());

        List<String> rows = new ArrayList<>();
        rows.add("analysis,comparison,metric,value_a,value_b,diff,ci_low,ci_high,p_value,note");
        crossValidation(train, params, rows);
        testSplit(train, test, params, rows);
        Files.write(Path.of(OUTPUT), rows);
        System.out.println("Results written to " + OUTPUT);
    }

    // Best row of the validation grid search: highest F1, then lowest FPR, first wins on ties.
    private static Params loadBestParams() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("data/processed/tuning_results.csv"));
        Params best = null;
        double bestF1 = -1;
        double bestFpr = 1;
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split(",");
            double f1 = Double.parseDouble(c[6]);
            double fpr = Double.parseDouble(c[7]);
            if (f1 > bestF1 || (f1 == bestF1 && fpr < bestFpr)) {
                best = new Params(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Integer.parseInt(c[2]));
                bestF1 = f1;
                bestFpr = fpr;
            }
        }
        return best;
    }

    private static void crossValidation(Dataset train, Params params, List<String> rows) throws Exception {
        int runs = FOLDS * REPEATS;
        double[][] f1 = new double[CONFIGS.size()][runs];
        double[][] fpr = new double[CONFIGS.size()][runs];
        int[] labels = train.labels();
        double testTrainRatioSum = 0;
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            int[] fold = stratifiedGroupFolds(
                train.groupKeys, labels, 1000 + repeat);
            for (int k = 0; k < FOLDS; k++) {
                List<Integer> tr = new ArrayList<>();
                List<Integer> va = new ArrayList<>();
                for (int i = 0; i < fold.length; i++) (fold[i] == k ? va : tr).add(i);
                Dataset trainPart = train.subset(tr.stream().mapToInt(Integer::intValue).toArray());
                Dataset evalPart = train.subset(va.stream().mapToInt(Integer::intValue).toArray());
                testTrainRatioSum += (double) evalPart.sql.size() / trainPart.sql.size();
                int run = repeat * FOLDS + k;
                for (int c = 0; c < CONFIGS.size(); c++) {
                    Config config = CONFIGS.get(c);
                    List<ExtendedRule> rules = AblationExperiment.mine(config, trainPart, params);
                    int[] pred = AblationExperiment.predict(CLASSIFIER,
                        AblationExperiment.features(config, trainPart, rules), trainPart.labels(),
                        AblationExperiment.features(config, evalPart, rules), evalPart.labels());
                    double[] m = metrics(AblationExperiment.confusion(pred, evalPart.labels()));
                    f1[c][run] = m[0];
                    fpr[c][run] = m[1];
                    System.out.printf(Locale.ROOT, "cv repeat=%d fold=%d %s F1=%.4f FPR=%.4f%n",
                        repeat, k, config, m[0], m[1]);
                }
            }
        }
        double averageTestTrainRatio = testTrainRatioSum / runs;
        String note = FOLDS + "-fold x " + REPEATS + " repeats";
        for (int c = 0; c < CONFIGS.size(); c++) {
            rows.add(String.format(Locale.ROOT, "cv,%s,f1_mean_std,%.4f,%.4f,,,,,%s",
                CONFIGS.get(c), mean(f1[c]), std(f1[c]), note + " (value_a=mean, value_b=std)"));
            rows.add(String.format(Locale.ROOT, "cv,%s,fpr_mean_std,%.4f,%.4f,,,,,%s",
                CONFIGS.get(c), mean(fpr[c]), std(fpr[c]), note + " (value_a=mean, value_b=std)"));
        }
        int extended = CONFIGS.indexOf(Config.EXTENDED);
        for (int c = 0; c < CONFIGS.size(); c++) {
            if (c == extended) continue;
            for (String metric : List.of("f1", "fpr")) {
                double[] a = metric.equals("f1") ? f1[c] : fpr[c];
                double[] b = metric.equals("f1") ? f1[extended] : fpr[extended];
                double[] d = new double[runs];
                for (int i = 0; i < runs; i++) d[i] = b[i] - a[i];
                double p = correctedTTest(d, averageTestTrainRatio);
                rows.add(String.format(Locale.ROOT, "cv_ttest,%s vs EXTENDED,%s,%.4f,%.4f,%.4f,,,%.6f,%s",
                    CONFIGS.get(c), metric, mean(a), mean(b), mean(d), p,
                    "Nadeau-Bengio corrected resampled t-test, two-sided"));
            }
        }
    }

    private static void testSplit(Dataset train, Dataset test, Params params, List<String> rows) throws Exception {
        int[] truth = test.labels();
        int[][] predictions = new int[CONFIGS.size()][];
        for (int c = 0; c < CONFIGS.size(); c++) {
            Config config = CONFIGS.get(c);
            List<ExtendedRule> rules = AblationExperiment.mine(config, train, params);
            predictions[c] = AblationExperiment.predict(CLASSIFIER,
                AblationExperiment.features(config, train, rules), train.labels(),
                AblationExperiment.features(config, test, rules), truth);
        }
        int extended = CONFIGS.indexOf(Config.EXTENDED);
        for (int c = 0; c < CONFIGS.size(); c++) {
            if (c == extended) continue;
            String name = CONFIGS.get(c) + " vs EXTENDED";
            int[] a = predictions[c];
            int[] b = predictions[extended];
            // Discordant counts: only the baseline wrong vs only EXTENDED wrong.
            for (String scope : List.of("all", "benign", "malicious")) {
                int onlyA = 0;
                int onlyB = 0;
                for (int i = 0; i < truth.length; i++) {
                    if (scope.equals("benign") && truth[i] != 0) continue;
                    if (scope.equals("malicious") && truth[i] != 1) continue;
                    boolean aWrong = a[i] != truth[i];
                    boolean bWrong = b[i] != truth[i];
                    if (aWrong && !bWrong) onlyA++;
                    if (!aWrong && bWrong) onlyB++;
                }
                rows.add(String.format(Locale.ROOT, "mcnemar,%s,errors_%s,%d,%d,%d,,,%.6g,%s",
                    name, scope, onlyA, onlyB, onlyB - onlyA, exactMcNemar(onlyA, onlyB),
                    "exact two-sided; value_a=only baseline wrong, value_b=only EXTENDED wrong"));
            }
            bootstrap(name, a, b, truth, rows);
        }
    }

    // Paired bootstrap over test samples for the difference EXTENDED - baseline.
    private static void bootstrap(String name, int[] a, int[] b, int[] truth, List<String> rows) {
        Random random = new Random(7);
        int n = truth.length;
        double[] f1Diff = new double[BOOTSTRAPS];
        double[] fprDiff = new double[BOOTSTRAPS];
        for (int r = 0; r < BOOTSTRAPS; r++) {
            int[] ca = new int[4];
            int[] cb = new int[4];
            for (int i = 0; i < n; i++) {
                int j = random.nextInt(n);
                add(ca, a[j], truth[j]);
                add(cb, b[j], truth[j]);
            }
            double[] ma = metrics(ca);
            double[] mb = metrics(cb);
            f1Diff[r] = mb[0] - ma[0];
            fprDiff[r] = mb[1] - ma[1];
        }
        double[] full = metrics(AblationExperiment.confusion(a, truth));
        double[] fullB = metrics(AblationExperiment.confusion(b, truth));
        rows.add(ciRow(name, "f1", full[0], fullB[0], f1Diff));
        rows.add(ciRow(name, "fpr", full[1], fullB[1], fprDiff));
    }

    private static String ciRow(String name, String metric, double a, double b, double[] diffs) {
        double[] sorted = diffs.clone();
        java.util.Arrays.sort(sorted);
        double low = sorted[(int) (0.025 * sorted.length)];
        double high = sorted[(int) (0.975 * sorted.length) - 1];
        return String.format(Locale.ROOT, "bootstrap,%s,%s,%.4f,%.4f,%.4f,%.4f,%.4f,,%s",
            name, metric, a, b, b - a, low, high, BOOTSTRAPS + " paired resamples, 95% percentile CI of EXTENDED - baseline");
    }

    private static void add(int[] cm, int predicted, int truth) {
        if (truth == 1) { if (predicted == 1) cm[0]++; else cm[3]++; }
        else { if (predicted == 1) cm[1]++; else cm[2]++; }
    }

    // {F1, FPR} from {TP, FP, TN, FN}.
    private static double[] metrics(int[] cm) {
        double tp = cm[0], fp = cm[1], tn = cm[2], fn = cm[3];
        double precision = tp / Math.max(1, tp + fp);
        double recall = tp / Math.max(1, tp + fn);
        return new double[] {2 * precision * recall / Math.max(1e-12, precision + recall),
            fp / Math.max(1, fp + tn)};
    }

    static int[] stratifiedGroupFolds(
            List<String> groupKeys, int[] labels, long seed) {
        int[] fold = new int[labels.length];
        Random random = new Random(seed);
        Map<String, List<Integer>> grouped = new HashMap<>();
        for (int i = 0; i < groupKeys.size(); i++) {
            grouped.computeIfAbsent(groupKeys.get(i), ignored -> new ArrayList<>()).add(i);
        }
        List<String> groups = new ArrayList<>(grouped.keySet());
        Collections.shuffle(groups, random);
        groups.sort((a, b) -> Integer.compare(
            grouped.get(b).size(), grouped.get(a).size()));

        int[] totalByClass = new int[2];
        for (int label : labels) totalByClass[label]++;
        int[][] foldCounts = new int[FOLDS][2];
        for (String group : groups) {
            int[] groupCounts = new int[2];
            for (int index : grouped.get(group)) groupCounts[labels[index]]++;
            int bestFold = 0;
            double bestScore = Double.POSITIVE_INFINITY;
            for (int candidate = 0; candidate < FOLDS; candidate++) {
                double score = 0;
                for (int cls = 0; cls <= 1; cls++) {
                    double target = (double) totalByClass[cls] / FOLDS;
                    double difference = foldCounts[candidate][cls]
                        + groupCounts[cls] - target;
                    score += difference * difference;
                }
                if (score < bestScore) {
                    bestScore = score;
                    bestFold = candidate;
                }
            }
            for (int index : grouped.get(group)) fold[index] = bestFold;
            for (int cls = 0; cls <= 1; cls++) {
                foldCounts[bestFold][cls] += groupCounts[cls];
            }
        }
        return fold;
    }

    static double mean(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return s / x.length;
    }

    static double std(double[] x) {
        double m = mean(x);
        double s = 0;
        for (double v : x) s += (v - m) * (v - m);
        return Math.sqrt(s / (x.length - 1));
    }

    // Nadeau & Bengio (2003): variance inflated by (1/J + n_test/n_train) for overlapping training sets.
    static double correctedTTest(double[] diffs, double testTrainRatio) {
        int n = diffs.length;
        double variance = Math.pow(std(diffs), 2);
        double mean = mean(diffs);
        if (variance == 0) return mean == 0 ? 1.0 : 0.0;
        double t = mean / Math.sqrt((1.0 / n + testTrainRatio) * variance);
        return 2 * (1 - studentTCdf(Math.abs(t), n - 1));
    }

    // Exact two-sided McNemar (binomial) test on the discordant pair counts.
    static double exactMcNemar(int onlyA, int onlyB) {
        int n = onlyA + onlyB;
        if (n == 0) return 1.0;
        int k = Math.min(onlyA, onlyB);
        double tail = 0;
        for (int i = 0; i <= k; i++) tail += Math.exp(logChoose(n, i) - n * Math.log(2));
        return Math.min(1.0, 2 * tail);
    }

    private static double logChoose(int n, int k) {
        return logGamma(n + 1) - logGamma(k + 1) - logGamma(n - k + 1);
    }

    static double studentTCdf(double t, double df) {
        double x = df / (df + t * t);
        double tail = 0.5 * regularizedBeta(x, df / 2.0, 0.5);
        return t >= 0 ? 1 - tail : tail;
    }

    // Regularized incomplete beta via Lentz's continued fraction.
    static double regularizedBeta(double x, double a, double b) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        double front = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x) + b * Math.log(1 - x));
        if (x < (a + 1) / (a + b + 2)) return front * betaCf(x, a, b) / a;
        return 1 - front * betaCf(1 - x, b, a) / b;
    }

    private static double betaCf(double x, double a, double b) {
        double tiny = 1e-30;
        double c = 1;
        double d = 1 - (a + b) * x / (a + 1);
        if (Math.abs(d) < tiny) d = tiny;
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 300; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2));
            d = 1 + aa * d; if (Math.abs(d) < tiny) d = tiny;
            c = 1 + aa / c; if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            h *= d * c;
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1));
            d = 1 + aa * d; if (Math.abs(d) < tiny) d = tiny;
            c = 1 + aa / c; if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1) < 1e-12) break;
        }
        return h;
    }

    // Lanczos approximation.
    static double logGamma(double x) {
        double[] coef = {76.18009172947146, -86.50532032941677, 24.01409824083091,
            -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double c : coef) ser += c / ++y;
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }
}
