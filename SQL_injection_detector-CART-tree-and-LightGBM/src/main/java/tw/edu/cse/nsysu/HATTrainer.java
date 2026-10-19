package tw.edu.cse.nsysu;

import moa.classifiers.trees.HoeffdingAdaptiveTree;
import com.yahoo.labs.samoa.instances.Attribute;
import com.yahoo.labs.samoa.instances.DenseInstance;
import com.yahoo.labs.samoa.instances.Instances;
import com.yahoo.labs.samoa.instances.InstancesHeader;
import java.util.*;

public class HATTrainer {
    
    public static void run(List<float[]> featureRecords, List<Integer> labels, String scenarioName) {
        System.out.println("\n=== Hoeffding Adaptive Tree (HAT) | " + scenarioName + " ===");
        if (featureRecords.isEmpty()) return;

        int featureDim = featureRecords.get(0).length;
        int totalSize = featureRecords.size();

        try {
            // 1. 打亂與切分資料 (80% 訓練, 20% 測試)
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < totalSize; i++) indices.add(i);
            Collections.shuffle(indices, new Random(42));
            int trainSize = (int) (totalSize * 0.8);

            // 2. 建立 MOA 專屬的資料格式 (Samoa Instances)
            ArrayList<Attribute> attrs = new ArrayList<>();
            for (int i = 0; i < featureDim; i++) {
                attrs.add(new Attribute("f_" + i));
            }
            ArrayList<String> classLabels = new ArrayList<>();
            classLabels.add("0");
            classLabels.add("1");
            attrs.add(new Attribute("target_label", classLabels));

            Instances dataset = new Instances("HAT_Data", attrs, 0);
            dataset.setClassIndex(featureDim);

            // 3. 初始化 HAT 模型
            HoeffdingAdaptiveTree hatModel = new HoeffdingAdaptiveTree();
            hatModel.setModelContext(new InstancesHeader(dataset));
            hatModel.prepareForUse();

            // 4. 增量訓練 (Streaming Training) - HAT 是一筆一筆學的
            for (int i = 0; i < trainSize; i++) {
                int originalIdx = indices.get(i);
                float[] f = featureRecords.get(originalIdx);
                
                double[] values = new double[featureDim + 1];
                for (int j = 0; j < featureDim; j++) values[j] = f[j];
                values[featureDim] = labels.get(originalIdx);
                
                DenseInstance inst = new DenseInstance(1.0, values);
                inst.setDataset(dataset);
                hatModel.trainOnInstance(inst);
            }

            // 5. 測試與手動計算評估指標
            int tp = 0, fp = 0, fn = 0, tn = 0, correct = 0;
            int testSize = totalSize - trainSize;

            for (int i = trainSize; i < totalSize; i++) {
                int originalIdx = indices.get(i);
                float[] f = featureRecords.get(originalIdx);
                
                double[] values = new double[featureDim + 1];
                for (int j = 0; j < featureDim; j++) values[j] = f[j];
                values[featureDim] = labels.get(originalIdx); 
                
                DenseInstance inst = new DenseInstance(1.0, values);
                inst.setDataset(dataset);

                // 取得預測機率 (Class 0 與 1 的投票權重)
                double[] votes = hatModel.getVotesForInstance(inst);
                int predLabel = 0;
                if (votes != null && votes.length > 1) {
                    predLabel = (votes[1] > votes[0]) ? 1 : 0;
                } else if (votes != null && votes.length == 1) {
                    predLabel = (votes[0] > 0.5) ? 1 : 0;
                }
                
                int trueLabel = labels.get(originalIdx);

                if (predLabel == trueLabel) correct++;
                if (predLabel == 1 && trueLabel == 1) tp++;
                if (predLabel == 1 && trueLabel == 0) fp++;
                if (predLabel == 0 && trueLabel == 1) fn++;
                if (predLabel == 0 && trueLabel == 0) tn++;
            }

            // 6. 印出報表
            double accuracy = (double) correct / testSize * 100;
            double precision1 = (tp + fp == 0) ? 0 : (double) tp / (tp + fp);
            double recall1 = (tp + fn == 0) ? 0 : (double) tp / (tp + fn);
            double f1_1 = (precision1 + recall1 == 0) ? 0 : 2 * (precision1 * recall1) / (precision1 + recall1);

            double precision0 = (tn + fn == 0) ? 0 : (double) tn / (tn + fn);
            double recall0 = (tn + fp == 0) ? 0 : (double) tn / (tn + fp);
            double f1_0 = (precision0 + recall0 == 0) ? 0 : 2 * (precision0 * recall0) / (precision0 + recall0);

            double macro_f1 = (f1_1 + f1_0) / 2;

            System.out.printf("Total Accuracy: %.2f%%\n", accuracy);
            System.out.printf("Class 1 (Malicious) -> Precision: %.4f | Recall: %.4f | F1: %.4f\n", precision1, recall1, f1_1);
            System.out.printf("Class 0 (Benign)    -> Precision: %.4f | Recall: %.4f | F1: %.4f\n", precision0, recall0, f1_0);
            System.out.printf("Macro Average F1    -> %.4f\n", macro_f1);

        } catch (Exception e) {
            System.err.println("HAT Training Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}