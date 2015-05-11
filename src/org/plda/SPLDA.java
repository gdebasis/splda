/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.plda;

//import Jama.Matrix;
//import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;
import java.io.FileWriter;


class SGDLinearRegressor {
    int M;
	int K;
    float[] y;
    float[][] A;
    float[] theta; // params of the model (to be estimated)
	float alpha = 0.01f;
	int niters;
							    
    public SGDLinearRegressor(float[][] A, float[] y) {
	    this.M = y.length;
	    this.K = A[0].length + 1; // one dimension extra for the x^0 term
		this.A = new float[M][this.K];
		this.y = y;

		for (int i = 0; i < M; i++) {
			for (int j = 0; j < this.K; j++) {
				this.A[i][j] = j==0? 1 : A[i][j-1];
			}
		}

		theta = new float[this.K];
		for (int i = 0; i < this.K; i++) {
			theta[i] = (float)Math.random(); 
		}
	}

	void setLearningRate(float alpha) { this.alpha = alpha; }

	void setNumIterations(int niters) { this.niters = niters; }

	float hypothesis(float[] x) {  // h(x) = \theta^T x
		float sum = 0;
		for (int i = 0; i < K; i++) {
			sum += theta[i] * x[i];
		}
		return sum;
	}

	void showTheta() {
		for (int i = 0; i < K; i++)
			System.out.println(theta[i]);
	}

	void epoch() {
		for (int i = 0; i < M; i++) {
			float err = y[i] - hypothesis(A[i]);
			for (int j = 0; j < K; j++) {
				theta[j] = theta[j] + alpha * err * A[i][j];
			}
		}
	}

	void estimate() {
		for (int i = 0; i < niters; i++) {
            System.out.println("SGD itertion: " + i);
			epoch();
		}
	}

	float[] getParams() {
		float[] modelParams = new float[K-1];
		System.arraycopy(theta, 1, modelParams, 0, K-1);
		return modelParams;
	}

	float getIntercept() { return this.theta[0]; }
}

/**
 *
 * @author dganguly
 */
// Supervised PLDA
// Methods to overload: estimate and sampling
public class SPLDA extends PLDA {
    
    float[] eta;   // regression parameters for topic-output
    float   sigma; // response drawn from N(eta,sigma)

    static PLDADocModel lastDocModel = null;
    static float lastEtaZ = 0;
    
    SPLDA(String propFile) throws Exception {
        super(propFile);
        eta = new float[K];
        for (int i = 0; i < K; i++)
            eta[i] = -1 + 2*(float)Math.random();
    }
    
    @Override
    float samplingProb(PLDADocModel docModel, int j, int k, int t) {
        float regFactor;
        float normalizedEtaComponent = eta[k]/(float)docModel.nwords;
        float etaZ = 0.0f;  // the inner product of eta and z
        
        if (docModel != lastDocModel) {
            for (int kk = 0; kk < K; kk++) {
                etaZ += eta[kk] * docModel.nd_topics[kk]/(float)docModel.nwords;
            }
            lastDocModel = docModel;
            lastEtaZ = etaZ;
        }
        else {
            etaZ = lastEtaZ;
        }
    
        float errorComponent = docModel.getResponse() - sigma - etaZ;
        
        // There's no regression parameter to be put in the sampling prob.
        // during the inference process.
        regFactor = //estimated != null? 1 :
				(float)Math.exp(normalizedEtaComponent*(2*errorComponent-normalizedEtaComponent));

        float prob = 
                (nw_topics[t][k] + beta)/(nw_topics_sum[k] + Vbeta) *
                (docModel.nd_topics[k] + alpha) * regFactor;
        
        return labelProp? docModel.nd_labels[j] * prob : prob;
    }
    
    // The M-step: update eta and sigma
    @Override
    void updateRegressionParams() throws IllegalArgumentException {
        
        // Compute the outer-product of eta with itself
        int M = docs.numDocs();
        
        // A simple Stochastic Gradient Descent Linear Regressor with
        // 1000 iterations.
        float[][] A = new float[M][K];
        float[] y = new float[M];
        
        for (int i = 0; i < M; i++) {
            PLDADocModel docModel = (PLDADocModel)getDocModel(i);
            for (int j = 0; j < K; j++) {
                A[i][j] = docModel.nd_topics[j]/(float)docModel.nwords;
            }
            y[i] = docModel.getResponse();
        }
        
        System.out.println("Performing MLE...");
        SGDLinearRegressor sgdreg = new SGDLinearRegressor(A, y);
        sgdreg.setNumIterations(Integer.parseInt(prop.getProperty("splda.mle.iter", "50")));
        sgdreg.estimate();
        eta = sgdreg.getParams();
        sigma = sgdreg.getIntercept();
        
        if (this.trace > 1) {
            for (int i = 0; i < eta.length; i++)
                System.out.print(eta[i]+" ");        
            System.out.println();
        }
        System.out.println("sigma = " + sigma);        
    }
    
 	// Predict response based on infered topic distribution
	float predictResponse(SPLDA estimated, int m) {
		PLDADocModel model = (PLDADocModel)getDocModel(m);
		// return the inner product of eta and zbar
		float yhat = 0;
		for (int k = 0; k < K; k++) {
			yhat += ((SPLDA)estimated).eta[k] * model.nd_topics[k]/(float)model.nwords; // no sigma! that's a surprise!
		}
		return yhat;
	}

	public void predictResponses(SPLDA estimated) {
    	try {		
            String outFileName = getProperties().getProperty("slda.infer.predictions");
            FileWriter fw = new FileWriter(outFileName);
            int M = docs.numDocs();
            for (int m = 0; m < M; m++) {
                Document thisDoc = docs.docs.get(m);
                float yhat = predictResponse(estimated, m);
                fw.write(thisDoc.id + "\t" + yhat + "\n");
            }
            fw.close();
        }
        catch (Exception ex) { ex.printStackTrace(); }
	}

    /*
    @Override
	public void estimate() {
        // Estimate using the PLDABase implementation.
        // Note that SPLDA specific functions 'samplingProb'
        // and updateRegParams (being virtual) are called.
        super.estimate(); 
        
        // Now predict the responses. This is the extra bit which
        // is not there in the base class implementation.
		predictResponses();
	}
    */
    
    public static void main(String[] args) {
        String propFile = "slda.properties";
        if (args.length >= 1) {
            propFile = args[0];
        }
        try {
            SPLDA plda = new SPLDA(propFile);
            plda.estimate();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }    
}

