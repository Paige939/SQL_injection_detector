package tw.edu.cse.nsysu.api.service;

import com.yahoo.labs.samoa.instances.Attribute;
import com.yahoo.labs.samoa.instances.DenseInstance;
import com.yahoo.labs.samoa.instances.Instances;
import com.yahoo.labs.samoa.instances.InstancesHeader;
import moa.classifiers.trees.HoeffdingTree;

import java.util.ArrayList;
import java.util.Arrays;

public class HoeffdingTreeIncrementalClassifier implements IncrementalClassifier {
    private HoeffdingTree classifier;
    private Instances dataset;
    private int featureCount;
    private int totalTrained;

    @Override
    public void initialize(int featureCount) {
        this.featureCount = featureCount;
        ArrayList<Attribute> attributes = new ArrayList<>();
        for (int i = 0; i < featureCount; i++) {
            attributes.add(new Attribute("f_" + i));
        }
        attributes.add(new Attribute("target_label", new ArrayList<>(Arrays.asList("0", "1"))));
        dataset = new Instances("incremental_hoeffding_tree", attributes, 0);
        dataset.setClassIndex(featureCount);

        classifier = new HoeffdingTree();
        classifier.setModelContext(new InstancesHeader(dataset));
        classifier.prepareForUse();
        totalTrained = 0;
    }

    @Override
    public double[] predictProb(float[] features) {
        double[] votes = classifier.getVotesForInstance(toInstance(features, null));
        if (votes == null || votes.length < 2) {
            return new double[] {0.5, 0.5};
        }
        double probability0 = Double.isFinite(votes[0]) ? Math.max(0, votes[0]) : 0;
        double probability1 = Double.isFinite(votes[1]) ? Math.max(0, votes[1]) : 0;
        double sum = probability0 + probability1;
        if (sum <= 0) return new double[] {0.5, 0.5};
        return new double[] {
            probability0 / sum,
            probability1 / sum
        };
    }

    @Override
    public void update(float[] features, int label) {
        classifier.trainOnInstance(toInstance(features, label));
        totalTrained++;
    }

    @Override
    public int getTotalTrained() {
        return totalTrained;
    }

    private com.yahoo.labs.samoa.instances.Instance toInstance(
            float[] features,
            Integer label
    ) {
        if (dataset == null || features.length != featureCount) {
            throw new IllegalArgumentException(
                "Feature count does not match the initialized Hoeffding Tree model."
            );
        }
        double[] values = new double[featureCount + 1];
        for (int i = 0; i < featureCount; i++) {
            values[i] = features[i];
        }
        if (label == null) {
            values[featureCount] = Double.NaN;
        } else {
            values[featureCount] = label;
        }
        DenseInstance instance = new DenseInstance(1.0, values);
        instance.setDataset(dataset);
        return instance;
    }
}
