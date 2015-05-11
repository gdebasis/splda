/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.plda;

import Jama.Matrix;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

/**
 *
 * @author dganguly
*/

class TopicInfo {
    int topicId;
    float prob;
    
    TopicInfo(int topicId, float prob) {
        this.topicId = topicId;
        this.prob = prob;
    }
}

public class PLDA extends PLDABase {
    
    // Global stats
	int[][] nw_topics; // nw_topics[k][t]: number of instances of word t assigned to topic k, size KxV
    
    // Label and topic to term distribution over the whole collection
	int[] nw_topics_sum; // nw_topics_sum[k]: total number of words assigned to topic k, size K    
    
    PLDA(String propFile) throws Exception {
		super(propFile);

        K = docs.labelMap.getTotalNumTopics();
        L = docs.labelMap.getNumLabels();
        System.out.println("Total: #topics = " + K + " #labels = " + L);
        
        // initialize the document models for every document object
        for (Document doc : docs.docs) {
            doc.model = new PLDADocModel(doc, this.K, this.L);
        }
        
		phi = new float[K][V];
        if (!initModel())
			throw new Exception("Unable to initialize model!");        
    }

    Properties getProperties() { return docs.getProperties(); }

    // This function either initializes an empty model if
    // the properties load.from is empty or loads an existing
    // model. Note: the topic assignments, i.e. values of z(d,n)
    // are loaded from a file. Everything else is reconstructed.
    @Override
    boolean initModel() {
        String modelFile = getModelSpecificFile("loadfrom");
        
        ArrayList<PLDADocModelBase> loadedModels = null;
        if (modelFile != null) {
            try {
                loadedModels = loadDocModels(modelFile);
            }
            catch (Exception ex) { ex.printStackTrace(); }
        }
        
        LabelTopic labelTopic;
		int M = docs.numDocs();
        
        PLDADocModel thisDocModel;
        
        // Create and zero-init (new does it automatically)
        nw_topics = new int[V][K];
        nw_topics_sum = new int[K];
        
        for (int m = 0; m < M; m++) {
            thisDocModel = (PLDADocModel)getDocModel(m);
            // the labels for this document are observed
            // unlike LDA            
            for (int n = 0; n < thisDocModel.nwords; n++) {
                // choose a label at random (from the observed
                // set of labels) and choose a
                // topic at random within its range
                if (modelFile == null) {                
                    labelTopic = thisDocModel.getRandom(this);
                    thisDocModel.lz[n] = labelTopic;
                }
                else {
                    // check if the loaded model is consistent with
                    // this one.. else flag an error
                    PLDADocModelBase loadedModel = loadedModels.get(m);
                    if (!thisDocModel.equals(loadedModel))
                        return false;
                    thisDocModel.lz = loadedModel.lz;
                    labelTopic = thisDocModel.lz[n];
                }
                
                // update global counts
				// number of instances of this word assigned to topic 
                nw_topics[thisDocModel.doc.words[n]][labelTopic.topic]++;
                
                // per-doc counts
                thisDocModel.nd_topics[labelTopic.topic]++;
                thisDocModel.nd_labels[labelTopic.label]++;
                
                nw_topics_sum[labelTopic.topic]++;
            }
            
            // DEBUG_INFO:
            if (trace > 0) {
                System.out.println("Doc: " + m);
                for (int n = 0; n < thisDocModel.nwords; n++) {
                    System.out.print(thisDocModel.lz[n] + " ");
                }
                System.out.println();
            }
        }
        return true;
    }
    
    void updateRegressionParams() {
        // do nothing.. to be implemented in SPLDA        
    }
    
   
	void computeTheta() {
		int M = docs.numDocs();
        float Kalpha = K*alpha;
        PLDADocModel thisDocModel;
        
		for (int m = 0; m < M; m++) {
            thisDocModel = (PLDADocModel)getDocModel(m);
            
			for (int k = 0; k < K; k++) {
				thisDocModel.theta[k] = (thisDocModel.nd_topics[k] + alpha)/(thisDocModel.nwords + Kalpha);
			}
		}
	}
	
    // The numbers are different depending on estimation/inference
	void computePhi() {
        
        //PLDA estimated = (PLDA)(this.estimated);
        
		for (int k = 0; k < K; k++) {
			for (int t = 0; t < V; t++) {
                // check est/inf
                float est_nw_topics = /*estimated == null?*/ 0 /*: estimated.nw_topics[t][k]*/;
                float est_nw_topics_sum = /* estimated == null? */ 0 /* : estimated.nw_topics_sum[k] */;
                
				phi[k][t] = (est_nw_topics + nw_topics[t][k] + beta) /
                            (est_nw_topics_sum + nw_topics_sum[k] + Vbeta);
			}
		}
	}

    float samplingProb(PLDADocModel docModel, int j, int k, int t) {
        
        //PLDA estimated = (PLDA)(this.estimated);
        float est_nw_topics = /*estimated == null? */ 0 /*: estimated.nw_topics[t][k]*/;
        float est_nw_topics_sum = /*estimated == null?*/ 0 /*: estimated.nw_topics_sum[k]*/;
        
        float prob = (est_nw_topics + nw_topics[t][k] + beta)/
                    (est_nw_topics_sum + nw_topics_sum[k] + Vbeta) *
                    (docModel.nd_topics[k] + alpha);

        return labelProp? docModel.nd_labels[j] * prob : prob;
    }
    
	LabelTopic sampling(int m, int n) {
        // Get the label for this word.
        PLDADocModel docModel = (PLDADocModel)getDocModel(m);
        LabelTopic jk = docModel.lz[n];
        int k = jk.topic;
        int j = jk.label;
		int t = docModel.doc.words[n];
        
		// remove l_n and z_n from the count variable
		this.nw_topics[t][k]--;
		this.nw_topics_sum[k]--;
        docModel.nd_topics[k]--;
        docModel.nd_labels[j]--;
        
        // Allocate variable to store cumulative prob. mass for
        // all topics corresponding to the observed labels for
        // this document.
        int totalNumTopics = 0;  // this is NOT the global number of topics
                                 // available in the collection, but the 
                                 // number of topics that we can choose from
                                 // the observed labels.
        for (int jj : docModel.doc.labels) {
            totalNumTopics += docs.getLabel(jj).numTopics;
        }
        TopicInfo[] p = new TopicInfo[totalNumTopics];
        
		// do multinominal sampling via cumulative method
        int start = 0;
        float probMass;
        
        for (int jj : docModel.doc.labels) {
            Label l = docs.getLabel(jj); // get the label object corresponding to this sampled label id
            for (int topicIter = 0; topicIter < l.numTopics; topicIter++) {
                int kk = l.startTopicIndex + topicIter;
                probMass = samplingProb(docModel, jj, kk, t);                
                p[start + topicIter] = new TopicInfo(kk, probMass);
            }            
            start += l.numTopics;
        }
        
        // sample a new topic
        k = sampleTopic(p);         
        // We have sampled a new topic k. Get its corresponding label
        j = getTiedLabelId(k);
		
		// add newly estimated z_i to count variables
		nw_topics[t][k]++;
		nw_topics_sum[k]++;
        docModel.nd_labels[j]++;
		docModel.nd_topics[k]++;
        		
 		return new LabelTopic(j, k);
	}
    
    public static void main(String[] args) {
        String propFile = "plda.properties";
        if (args.length >= 1) {
            propFile = args[0];
        }
        try {
            PLDA plda = new PLDA(propFile);
            plda.estimate();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }    
}

