package tw.edu.cse.nsysu;

import weka.classifiers.trees.J48;
import weka.classifiers.Evaluation;
import weka.core.Instances;

public class CartTrainer {
    
    public static void run(Instances train, Instances test) {
        System.out.println("\n=== Decision Tree (Weka J48) ===");
        try {
            J48 treeModel = new J48();
            treeModel.buildClassifier(train);

            Evaluation eval = new Evaluation(train);
            eval.evaluateModel(treeModel, test);

            printMetrics(eval);
        } catch (Exception e) {
            System.err.println("Tree Training Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printMetrics(Evaluation eval) {
        double accuracy = eval.pctCorrect();
        double precision1 = eval.precision(1);
        double recall1 = eval.recall(1);
        double f1_1 = eval.fMeasure(1);
        double precision0 = eval.precision(0);
        double recall0 = eval.recall(0);
        double f1_0 = eval.fMeasure(0);
        double macro_f1 = (f1_1 + f1_0) / 2;

        System.out.printf("Total Accuracy: %.2f%%\n", accuracy);
        System.out.printf("Class 1 (Malicious) -> Precision: %.4f | Recall: %.4f | F1: %.4f\n", precision1, recall1, f1_1);
        System.out.printf("Class 0 (Benign)    -> Precision: %.4f | Recall: %.4f | F1: %.4f\n", precision0, recall0, f1_0);
        System.out.printf("Macro Average F1    -> %.4f\n", macro_f1);
    }
}