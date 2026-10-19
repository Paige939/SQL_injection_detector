package tw.edu.cse.nsysu;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;


public class Main {

    private static final boolean IMPORT_DATA = true;
    private static final boolean EXPORT_TO_SPMF = true;
    private static final boolean RUN_ECLAT = true;
    private static final boolean RUN_ECLAT_FPGROWTH = true;
    private static final boolean RUN_FPGROWTH = true;
    private static final boolean FEATURE_EXTRACT = true;
    private static final boolean RUN_MODEL = true;

    private static final boolean RUN_HOEFFDING_TREE = true; // VFDT
    private static final boolean RUN_SGD = true;            // incremental SVM
    private static final boolean RUN_INC_NB = true;         // incremental Naive Bayes
    private static final boolean RUN_HAT = true;            // Hoeffding Adaptive Tree
    private static final int STAGE_NUMBER = 4;
    private static final int VERSION_NUMBER = 1;

    private static final double ECLAT_MIN_SUPPORT = 0.05;
    private static final double ECLAT_FPGROWTH_MIN_SUPPORT = 0.10;
    private static final double ECLAT_FPGROWTH_MIN_CONF = 0.60;
    private static final double FPGROWTH_MIN_SUPPORT = 0.10;
    private static final double FPGROWTH_MIN_CONF = 0.60;

    private static final String DB = "jdbc:sqlite:db/SQLIA.db";

    public static void main(String[] args) {

        String outputDir = getOutputDir(STAGE_NUMBER, VERSION_NUMBER);
        File outputDirectory = new File(outputDir);

        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            System.err.println("ERROR: Cannot create output directory: " + outputDir);
            return;
        }

        String spmfInput = outputDir + "/SPMF_input_data.txt";

        System.out.println("==================================================");
        System.out.println(" SQL Injection Data Preprocessing + ML Pipeline");
        System.out.println("==================================================");
        System.out.println("Stage       : " + STAGE_NUMBER);
        System.out.println("Version     : " + VERSION_NUMBER);
        System.out.println("Output dir  : " + outputDir);
        System.out.println("SPMF input  : " + spmfInput);
        System.out.println();

        Connection conn = null;

        try {
            conn = DriverManager.getConnection(DB);
            System.out.println("-- Database connection success! --");

        
            if (IMPORT_DATA) {
                System.out.println("\n=== STEP 1: Import Data ===");

                DataImporter importer = new DataImporter(conn);
                importer.Import();

                System.out.println("-- Data import complete. --");
            }

         
            if (EXPORT_TO_SPMF) {
                System.out.println("\n=== STEP 2: Export SPMF Transactions ===");

                ToTransactionList transaction = new ToTransactionList(conn);

                transaction.Export(spmfInput);

                System.out.println("-- SPMF transaction export complete. --");
                System.out.println("   File: " + spmfInput);
            }

            boolean anySPMF =
                    RUN_ECLAT ||
                    RUN_ECLAT_FPGROWTH ||
                    RUN_FPGROWTH;

            if (anySPMF) {
                System.out.println("\n=== STEP 3: SPMF Pattern Mining ===");

                if (!new File(spmfInput).exists()) {
                    throw new IllegalStateException(
                            "SPMF input file does not exist: " + spmfInput
                    );
                }

                if (RUN_ECLAT) {
                    runEclat(spmfInput, outputDir);
                }

                if (RUN_ECLAT_FPGROWTH) {
                    runEclatFPGrowth(spmfInput, outputDir);
                }

                if (RUN_FPGROWTH) {
                    runFPGrowth(spmfInput, outputDir);
                }

                System.out.println("-- SPMF stage complete. --");

                syncSPMFOutputsForFeatureExtraction(outputDir);
            } else {
                System.out.println("\n=== STEP 3: SPMF Pattern Mining SKIPPED ===");
                System.out.println(
                        "WARNING: FEATURE_EXTRACT is " + FEATURE_EXTRACT +
                        ". If the feature extractor depends on SPMF results, " +
                        "feature extraction may produce 0 rows."
                );
            }

        
            if (FEATURE_EXTRACT) {
                System.out.println("\n=== STEP 4: Feature Extraction ===");

                FeatureExtract featureExtract = new FeatureExtract();
                ExtractProcess processor = new ExtractProcess(featureExtract, conn);

                processor.FillFeatureVector();

                System.out.println("-- Feature extraction process complete. --");

                int featureRows = countValidFeatureRows(conn);

                System.out.println(
                        "-- Valid feature_vector rows: " + featureRows + " --"
                );

                if (featureRows == 0) {
                    System.err.println();
                    System.err.println("==================================================");
                    System.err.println("ERROR: 0 valid feature vectors were generated.");
                    System.err.println("==================================================");
                    System.err.println(
                            "The ML stage will NOT run because there is no training data."
                    );
                    System.err.println();
                    System.err.println("Check these items:");
                    System.err.println("1. SPMF algorithms actually ran.");
                    System.err.println("2. SPMF output files were generated.");
                    System.err.println("3. ExtractProcess reads the same output directory.");
                    System.err.println(
                            "4. The selected Stage/Version matches the files used by ExtractProcess."
                    );
                    return;
                }
            }

            if (RUN_MODEL) {
                System.out.println("\n=== STEP 5: Incremental / Streaming Models ===");

                List<float[]> X = new ArrayList<>();
                List<Integer> Y = new ArrayList<>();

                loadTrainingData(conn, X, Y);

                if (X.isEmpty()) {
                    System.err.println(
                            "ERROR: training_data contains no valid feature vectors."
                    );
                    return;
                }

                System.out.println("-- Loaded samples: " + X.size() + " --");
                System.out.println("-- Feature dimension: " + X.get(0).length + " --");

                String scenarioName =
                        "Stage " + STAGE_NUMBER + " - Version " + VERSION_NUMBER;

                if (RUN_HOEFFDING_TREE) {
                    System.out.println("\n--- Running Hoeffding Tree (VFDT) ---");
                    HoeffdingTreeTrainer.run(X, Y, scenarioName);
                }

                if (RUN_SGD) {
                    System.out.println("\n--- Running SGD / Incremental SVM ---");
                    SGDTrainer.run(X, Y, scenarioName);
                }

                if (RUN_INC_NB) {
                    System.out.println("\n--- Running Incremental Naive Bayes ---");
                    IncNBTrainer.run(X, Y, scenarioName);
                }

                if (RUN_HAT) {
                    System.out.println("\n--- Running Hoeffding Adaptive Tree (HAT) ---");
                    HATTrainer.run(X, Y, scenarioName);
                }
            } else {
                System.out.println("\n=== STEP 5: Model Training SKIPPED ===");
            }

            System.out.println("\n==================================================");
            System.out.println(" Pipeline finished.");
            System.out.println("==================================================");

        } catch (SQLException e) {
            System.err.println("ERROR: Database operation failed.");
            System.err.println(e.getMessage());
            e.printStackTrace();

        } catch (Exception e) {
            System.err.println("ERROR: Pipeline failed.");
            System.err.println(e.getMessage());
            e.printStackTrace();

        } finally {
            if (conn != null) {
                try {
                    conn.close();
                    System.out.println("-- Database connection closed. --");
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    private static String getOutputDir(int stageNumber, int versionNumber) {

        if (stageNumber == 3) {
            if (versionNumber == 1) {
                return "data/processed/Stage3/Version1";
            } else if (versionNumber == 2) {
                return "data/processed/Stage3/Version2";
            } else if (versionNumber == 3) {
                return "data/processed/Stage3/Version3";
            }
        }

        if (stageNumber == 4) {
            if (versionNumber == 1) {
                return "data/processed/Stage4/Version1";
            } else if (versionNumber == 2) {
                return "data/processed/Stage4/Version2";
            } else if (versionNumber == 3) {
                return "data/processed/Stage4/Version3";
            }
        }

        if (stageNumber == 5) {
            if (versionNumber == 1) {
                return "data/processed/Stage5/Version1";
            } else if (versionNumber == 2) {
                return "data/processed/Stage5/Version2";
            } else if (versionNumber == 3) {
                return "data/processed/Stage5/Version3";
            }
        }

        return "data/processed";
    }

    private static void runEclat(String input, String outputDir)
            throws Exception {

        String output =
                outputDir + "/Eclat_output_minSup_" + ECLAT_MIN_SUPPORT + ".txt";

        System.out.println("Eclat");
        System.out.println("  input : " + input);
        System.out.println("  output: " + output);
        System.out.println("  minSup: " + ECLAT_MIN_SUPPORT);

        invokeSPMF(
                "Eclat",
                "EclatRunner",
                input,
                output,
                ECLAT_MIN_SUPPORT
        );
    }

    private static void runEclatFPGrowth(String input, String outputDir)
            throws Exception {

        String output =
                outputDir +
                "/Eclat_FPGrowth_output_minSup_" +
                ECLAT_FPGROWTH_MIN_SUPPORT +
                "_minConf_" +
                ECLAT_FPGROWTH_MIN_CONF +
                ".txt";

        System.out.println("Eclat + FPGrowth");
        System.out.println("  input : " + input);
        System.out.println("  output: " + output);
        System.out.println("  minSup: " + ECLAT_FPGROWTH_MIN_SUPPORT);
        System.out.println("  minConf: " + ECLAT_FPGROWTH_MIN_CONF);

        invokeSPMF(
                "Eclat_FPGrowth",
                "runEclat_FPGrowth",
                input,
                output,
                ECLAT_FPGROWTH_MIN_SUPPORT,
                ECLAT_FPGROWTH_MIN_CONF
        );
    }

    private static void runFPGrowth(String input, String outputDir)
            throws Exception {

        String output =
                outputDir +
                "/FPGrowth_output_minSup_" +
                FPGROWTH_MIN_SUPPORT +
                "_minConf_" +
                FPGROWTH_MIN_CONF +
                ".txt";

        System.out.println("FPGrowth");
        System.out.println("  input : " + input);
        System.out.println("  output: " + output);
        System.out.println("  minSup: " + FPGROWTH_MIN_SUPPORT);
        System.out.println("  minConf: " + FPGROWTH_MIN_CONF);

        invokeSPMF(
                "FPGrowth",
                "runFPGrowth",
                input,
                output,
                FPGROWTH_MIN_SUPPORT,
                FPGROWTH_MIN_CONF
        );
    }

    private static void invokeSPMF(
            String className,
            String methodName,
            String input,
            String output,
            double... numericArgs) throws Exception {

        Class<?> clazz = Class.forName("tw.edu.cse.nsysu." + className);
        Object instance = clazz.getDeclaredConstructor().newInstance();

        Method[] methods = clazz.getMethods();

        for (Method method : methods) {
            if (!method.getName().equals(methodName)) {
                continue;
            }

            Class<?>[] types = method.getParameterTypes();

            if (types.length != 2 + numericArgs.length) {
                continue;
            }

            Object[] args = new Object[types.length];

            if (types[0] == String.class && types[1] == String.class) {
                args[0] = input;
                args[1] = output;
            } else {
                continue;
            }

            boolean compatible = true;

            for (int i = 0; i < numericArgs.length; i++) {
                Class<?> type = types[i + 2];
                double value = numericArgs[i];

                if (type == double.class || type == Double.class) {
                    args[i + 2] = value;
                } else if (type == float.class || type == Float.class) {
                    args[i + 2] = (float) value;
                } else if (type == int.class || type == Integer.class) {
                    args[i + 2] = (int) value;
                } else {
                    compatible = false;
                    break;
                }
            }

            if (!compatible) {
                continue;
            }

            try {
                method.invoke(instance, args);
                return;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();

                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }

                throw e;
            }
        }

        throw new NoSuchMethodException(
                "Cannot find compatible method " +
                className + "." + methodName +
                " with parameters (String, String, numeric...)."
        );
    }

    private static void syncSPMFOutputsForFeatureExtraction(String outputDir)
            throws IOException {

        File sourceDir = new File(outputDir);
        File targetDir = new File("data/processed");

        if (sourceDir.getCanonicalPath().equals(targetDir.getCanonicalPath())) {
            return;
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException(
                    "Cannot create feature-extractor compatibility directory: " +
                    targetDir.getPath()
            );
        }

        File[] files = sourceDir.listFiles();

        if (files == null) {
            throw new IOException(
                    "Cannot read SPMF output directory: " + sourceDir.getPath()
            );
        }

        int copied = 0;

        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }

            String name = file.getName();

            // Copy only files produced/consumed by the SPMF feature stage.
            boolean isSPMFOutput =
                    name.equals("SPMF_input_data.txt") ||
                    name.startsWith("Eclat_output") ||
                    name.startsWith("Eclat_FPGrowth_output") ||
                    name.startsWith("FPGrowth_output");

            if (!isSPMFOutput) {
                continue;
            }

            Path source = file.toPath();
            Path target = new File(targetDir, name).toPath();

            Files.copy(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );

            copied++;
        }

        System.out.println(
                "-- Mirrored " + copied +
                " SPMF files to data/processed for ExtractProcess. --"
        );
    }

    private static void loadTrainingData(
            Connection conn,
            List<float[]> X,
            List<Integer> Y) throws SQLException {

        String sql =
                "SELECT feature_vector, label " +
                "FROM training_data " +
                "WHERE feature_vector IS NOT NULL " +
                "AND TRIM(feature_vector) <> '' " +
                "AND label IS NOT NULL";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            int skipped = 0;
            int expectedDimension = -1;

            while (rs.next()) {

                String fv = rs.getString("feature_vector");
                int label = rs.getInt("label");

                fv = fv.trim();

            if (fv.startsWith("[") && fv.endsWith("]")) {
                fv = fv.substring(1, fv.length() - 1).trim();
            }

            if (fv.isEmpty()) {
                skipped++;
                continue;
            }

            String[] parts = fv.split(",");
            float[] features = new float[parts.length];

            try {
                for (int i = 0; i < parts.length; i++) {
                    features[i] = Float.parseFloat(parts[i].trim());

                    if (!Float.isFinite(features[i])) {
                        throw new NumberFormatException("Non-finite feature value");
                    }
                }
            } catch (NumberFormatException e) {
                skipped++;
                continue;
            }

                // ML models should receive a consistent feature dimension.
                if (expectedDimension == -1) {
                    expectedDimension = features.length;
                }

                if (features.length != expectedDimension) {
                    skipped++;
                    continue;
                }

                X.add(features);
                Y.add(label);
            }

            System.out.println(
                    "-- Training rows loaded: " + X.size() +
                    ", skipped: " + skipped + " --"
            );
        }
    }

    private static int countValidFeatureRows(Connection conn)
            throws SQLException {

        String sql =
                "SELECT COUNT(*) " +
                "FROM training_data " +
                "WHERE feature_vector IS NOT NULL " +
                "AND TRIM(feature_vector) <> '' " +
                "AND label IS NOT NULL";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getInt(1);
            }
        }

        return 0;
    }
}
