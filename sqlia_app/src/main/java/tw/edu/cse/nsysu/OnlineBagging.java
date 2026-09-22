package tw.edu.cse.nsysu;

import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.classifiers.UpdateableClassifier;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.core.Instance;
import java.io.Serializable;
import java.util.Random;

public class OnlineBagging implements Serializable{
    
    private static final long serialVersionUID = 1L; //Version UID
    private Classifier[] ensembler; //Array of classifiers
    private Instances header;  //Header of the dataset
    private final Classifier baseClassifier; //Ensembling base classifier
    private final int ensembleSize; //Number of classifiers in the ensemble
    private final Random rnd; //Poisson random number generator

    //Constructor for the OnlineBagging class
    public OnlineBagging(Classifier baseLearner, int ensembleSize, long seed){
        this.baseClassifier = baseLearner;
        this.ensembleSize = ensembleSize;
        this.rnd = new Random(seed);
    }
    //Initialize the ensemble of classifiers
    public void initialize(Instances header) throws Exception{
        this.header = header;
        this.ensembler = new Classifier[ensembleSize];
        for(int i = 0; i < ensembleSize; i++){ 
            ensembler[i] = AbstractClassifier.makeCopy(baseClassifier);
            ensembler[i].buildClassifier(header);
        }
    }
    //Update the ensemble of classifiers with a new instance
    public void update(float[] x, int y) throws Exception{
        Instance inst = toLabelledInstance(x, y);
        for(Classifier classifier : ensembler){
            //Sample from k ~ Poisson(1) and simulating Bagging method
            int k = samplePoisson1();
            for(int i = 0; i < k; i++){ //update k times for each classifier
                ((UpdateableClassifier) classifier).updateClassifier(inst);
            }
        }
    }
    //Output binary class label
    public int predictLabel(float[] x) throws Exception{
        double[] probs = predictProb(x);
        return probs[1] >= 0.5 ? 1 : 0;  //Thresholding at 0.5 for binary classification
    }
    //Output: [P(class=0), P(class=1)]
    public double[] predictProb(float[] x) throws Exception{
        Instance inst = toUnlabelledInstance(x);  //Construct an unlabelled instance from the input features
        double p0 = 0.0; //class 0 probability
        double p1 = 0.0; //class 1 probability
        for(Classifier classifier : ensembler){
            double[] dist = classifier.distributionForInstance(inst); //Get the class distribution for the instance
            p0 += dist[0]; //Accumulate class 0 probability
            p1 += dist[1]; //Accumulate class 1 probability
        }

        p0 /= ensembler.length; //Average class 0 probability
        p1 /= ensembler.length; //Average class 1 probability
        return new double[]{p0, p1}; //Return the averaged probabilities
    }
    //Construct labelled training instances
    private Instance toLabelledInstance(float[] x, int y){
        Instance inst = new DenseInstance(header.numAttributes());
        inst.setDataset(header);
        for(int i = 0; i < x.length; i++){
            inst.setValue(i, x[i]); //Set feature values
        }
        inst.setValue(header.classIndex(), String.valueOf(y)); //Set class label(0 or 1)
        return inst; //Return the constructed instance
    }
    //Construct unlabelled testing instances
    private Instance toUnlabelledInstance(float[] x){
        Instance inst = new DenseInstance(header.numAttributes()); 
        inst.setDataset(header);
        for(int i = 0; i < x.length; i++){
            inst.setValue(i, x[i]); //Set feature values
        }
        inst.setMissing(header.classIndex()); //Set class label as missing
        return inst; //Return the constructed instance
    }
    //Sample from Poisson(1) distribution
    private int samplePoisson1(){
        final double L = Math.exp(-1.0); //L = e^(-lambda) where lambda=1
        int k = 0;
        double p = 1.0;
        do{
            k++;
            p *= rnd.nextDouble(); //Generate a uniform random number U(0,1) and multiply
        } while (p > L);
        return k - 1;
    }
}