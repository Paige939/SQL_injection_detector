package tw.edu.cse.nsysu;

import weka.classifiers.trees.SimpleCart;
import weka.classifiers.Evaluation;
import weka.core.Instances;

public class CartTrainer {

    public static void run(Instances train, Instances test) {
        System.out.println("\n=== CART Decision Tree (SimpleCart) ===");
        
        try {

            SimpleCart cartModel = new SimpleCart();
            cartModel.buildClassifier(train);
            Evaluation eval = new Evaluation(train);
            eval.evaluateModel(cartModel, test)
            printMetrics(eval);
        } catch (Exception e) {
            System.err.println("CART Error: " + e.getMessage());
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
        System.out.println("---Class 1 (Malicious)---");
        System.out.printf("Precision: %.4f\n", precision1);
        System.out.printf("Recall: %.4f\n", recall1);
        System.out.printf("F1-Score: %.4f\n", f1_1);
        System.out.println("---Class 0 (Benign)---");
        System.out.printf("Precision: %.4f\n", precision0);
        System.out.printf("Recall: %.4f\n", recall0);
        System.out.printf("F1-Score: %.4f\n", f1_0);
        System.out.println("---Macro Average F1-Score---");
        System.out.printf("Macro F1-Score: %.4f\n", macro_f1);
    }
}