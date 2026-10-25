package tw.edu.cse.nsysu.api.service;

import weka.classifiers.functions.SGD;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.SelectedTag;

import java.util.ArrayList;
import java.util.Arrays;

public class SgdIncrementalClassifier implements IncrementalClassifier {
    private SGD classifier;
    private Instances header;
    private int totalTrained;
    private double[] mean;
    private double[] m2;
    private final boolean standardize;

    public SgdIncrementalClassifier() {
        this(true);
    }

    public SgdIncrementalClassifier(boolean standardize) {
        this.standardize = standardize;
    }

    @Override
    public void initialize(int featureCount) throws Exception {
        ArrayList<Attribute> attributes = new ArrayList<>();
        for (int i = 0; i < featureCount; i++) {
            attributes.add(new Attribute("f_" + i));
        }
        attributes.add(new Attribute("target_label", Arrays.asList("0", "1")));
        header = new Instances("incremental_sgd", attributes, 0);
        header.setClassIndex(featureCount);

        mean = new double[featureCount];
        m2 = new double[featureCount];
        classifier = new SGD();
        classifier.setDontNormalize(true);
        classifier.setLossFunction(new SelectedTag(SGD.LOGLOSS, SGD.TAGS_SELECTION));
        classifier.buildClassifier(new Instances(header, 0));
        totalTrained = 0;
    }

    @Override
    public double[] predictProb(float[] features) throws Exception {
        double[] distribution = classifier.distributionForInstance(toInstance(features, null));
        if (distribution.length < 2) {
            return new double[] {0.5, 0.5};
        }
        double probability0 = Double.isFinite(distribution[0])
            ? Math.max(0, distribution[0]) : 0;
        double probability1 = Double.isFinite(distribution[1])
            ? Math.max(0, distribution[1]) : 0;
        double sum = probability0 + probability1;
        if (sum <= 0) return new double[] {0.5, 0.5};
        return new double[] {
            probability0 / sum,
            probability1 / sum
        };
    }

    @Override
    public void update(float[] features, int label) throws Exception {
        totalTrained++;
        if (standardize) {
            // Welford running statistics, so scaling needs no separate fit step
            for (int i = 0; i < features.length; i++) {
                double delta = features[i] - mean[i];
                mean[i] += delta / totalTrained;
                m2[i] += delta * (features[i] - mean[i]);
            }
        }
        classifier.updateClassifier(toInstance(features, label));
    }

    @Override
    public int getTotalTrained() {
        return totalTrained;
    }

    private Instance toInstance(float[] features, Integer label) {
        if (header == null || features.length != header.classIndex()) {
            throw new IllegalArgumentException("Feature count does not match the initialized SGD model.");
        }
        Instance instance = new DenseInstance(header.numAttributes());
        instance.setDataset(header);
        for (int i = 0; i < features.length; i++) {
            instance.setValue(i, scale(features[i], i));
        }
        if (label == null) {
            instance.setMissing(header.classIndex());
        } else {
            instance.setValue(header.classIndex(), String.valueOf(label));
        }
        return instance;
    }

    private double scale(double value, int i) {
        if (!standardize || totalTrained < 2) return value;
        double std = Math.sqrt(m2[i] / (totalTrained - 1));
        return std < 1e-9 ? 0 : (value - mean[i]) / std;
    }
}
