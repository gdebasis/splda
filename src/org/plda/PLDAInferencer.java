/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.plda;

import java.io.FileWriter;
import java.util.Properties;

/**
 *
 * @author dganguly
 */
class PLDAInferencer {
    PLDA estimatedModel;
    PLDA inferedModel;
    static final int UNDEF = -1;

    public PLDAInferencer() {
    }
    
    public PLDAInferencer(String estPropFile, String infPropFile) throws Exception {
        estimatedModel = new PLDA(estPropFile);
        inferedModel = new PLDA(infPropFile);
    }

    // Get the word id of the estimated model of this word
    // that we are seeing in the model to be infered. Note that
    // this is required because the word ids may not neccessarily
    // match even if two word strings match.
    int getCorrespondingWordId(int inferenceModelWordId) {
        // get the string content of this word from its id
        String word = inferedModel.docs.wordMap.getWord(inferenceModelWordId);
        boolean isOOV = estimatedModel.docs.wordMap.isOOV(word);
        return isOOV? UNDEF : estimatedModel.docs.wordMap.getId(word);
    }

    // Remember: the topics (since they are tied to the labels) need
    // to be mapped as well from infered model to estimated model
    int getCorrespondingLabelId(int inferenceModelLabelId) {
        // get the string content of this word from its id
        String label = inferedModel.docs.labelMap.getWord(inferenceModelLabelId);
        boolean isOOV = estimatedModel.docs.labelMap.isOOV(label);
        return isOOV? UNDEF : estimatedModel.docs.labelMap.getId(label);
    }
    
    float samplingProb(PLDADocModel docModel, Label l, int topicOffset, int t) {
        int t_est = getCorrespondingWordId(t);
        int j = l.id;
        int k = l.startTopicIndex + topicOffset;
        int j_est = getCorrespondingLabelId(j);
        int k_est = (j_est == UNDEF)? UNDEF : j_est + topicOffset;
        
        float uniformTopicProb = 0;
        float p_est_nwtopics = t_est==UNDEF || k_est==UNDEF? uniformTopicProb : estimatedModel.nw_topics[t_est][k_est];
        float p_est_topics_sum = k_est == UNDEF? uniformTopicProb : estimatedModel.nw_topics_sum[k_est];    
        if (p_est_nwtopics > 0)
            p_est_nwtopics = p_est_nwtopics;
        
        float prob = 
                (p_est_nwtopics + inferedModel.nw_topics[t][k] + inferedModel.beta) /
                (p_est_topics_sum + inferedModel.nw_topics_sum[k] + inferedModel.Vbeta) *
                (docModel.nd_topics[k] + inferedModel.alpha);
        
        return inferedModel.labelProp? docModel.nd_labels[j]*prob : prob;
    }
    
	LabelTopic sampling(int m, int n) {
        // Get the label for this word.
        PLDADocModel docModel = (PLDADocModel)inferedModel.getDocModel(m);
        LabelTopic jk = docModel.lz[n];
        int k = jk.topic;
        int j = jk.label;
		int t = docModel.doc.words[n];
        
		// remove l_n and z_n from the count variable
		inferedModel.nw_topics[t][k]--;
		inferedModel.nw_topics_sum[k]--;
        docModel.nd_topics[k]--;
        docModel.nd_labels[j]--;
        
        Collection docs = inferedModel.docs;
        
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
                probMass = samplingProb(docModel, l, topicIter, t);
                p[start + topicIter] = new TopicInfo(kk, probMass);
            }            
            start += l.numTopics;
        }
        
        k = PLDABase.sampleTopic(p);
        
        // We have sampled a new topic k. Get its corresponding label
        j = docs.getTiedLabelId(k);
		
		// add newly estimated z_i to count variables
		inferedModel.nw_topics[t][k]++;
		inferedModel.nw_topics_sum[k]++;
        docModel.nd_labels[j]++;
		docModel.nd_topics[k]++;
        		
 		return new LabelTopic(j, k);
	}
    
 
    void computePhi() {
        int K = inferedModel.K;
        int V = inferedModel.V;
        float beta = inferedModel.beta;
        float Vbeta = inferedModel.Vbeta;
        
        // For mapping the topics between the estimated and the infered,
        // we need to iterate over the labels and map between the corresponding
        // tied topics...
        for (int j = 0; j < inferedModel.L; j++) {
            Label l = inferedModel.docs.getLabel(j);
            
            for (int topicIter = 0; topicIter < l.numTopics; topicIter++) {
                int k = l.startTopicIndex + topicIter;
                
                int j_est = getCorrespondingLabelId(j);
                int k_est = (j_est == UNDEF)? UNDEF : j_est + topicIter;

                float uniformTopicProb = 0;
                float p_est_topics_sum = k_est == UNDEF? uniformTopicProb : estimatedModel.nw_topics_sum[k_est];    
                
                for (int t = 0; t < V; t++) {
                    int t_est = getCorrespondingWordId(t);
                    float p_est_nwtopics = t_est==UNDEF || k_est==UNDEF? uniformTopicProb : estimatedModel.nw_topics[t_est][k_est];
                    
                    inferedModel.phi[k][t] =
                        (p_est_nwtopics + inferedModel.nw_topics[t][k] + beta) /
                        (p_est_topics_sum + inferedModel.nw_topics_sum[k] + Vbeta);
                }
            }
        }        
	}

    public void infer() {
		System.out.println("Sampling " + inferedModel.niters + " iteration!");
		int M = inferedModel.docs.numDocs();
        PLDADocModel thisDocModel = null;
        
        int showProgress = Integer.parseInt(inferedModel.docs.getProperties().getProperty("plda.showprogress", "0"));
        int mleStep = Integer.parseInt(inferedModel.docs.getProperties().getProperty("splda.mstep", "50"));
        
		for (int iter = 1; iter <= inferedModel.niters; iter++) {
			System.out.println("Gibbs Sampling: iteration " + iter + " ...");
			
			// for all w(d, n) (nth word in dth document)            
			for (int m = 0; m < M; m++) {
                thisDocModel = (PLDADocModel)inferedModel.getDocModel(m);
                
				for (int n = 0; n < thisDocModel.nwords; n++) {
					// z_i = z[m][n]
					// sample from p(l_i,z_i|l_-i, z_-i, w)
					LabelTopic jk = sampling(m, n);
					thisDocModel.lz[n] = jk;  // sampled (label,topic) (j,k)
				} // end for each word
                
			} // end for each document
            
            if (showProgress > 0 && iter % showProgress == 0) {
                // Warning: Didn't store this information in a heap. (Too lazy!)
                // Hence have to reformulate every time. Hence slow!
                System.out.println("After " + iter + " iterations...");
                System.out.println(inferedModel.getTopWords());
            }
			
            if (iter % mleStep == 0) {
                computePhi();
				inferedModel.updateRegressionParams();
            }
		} // end iterations		
		
		inferedModel.computeTheta();
		computePhi();
        
        try {
            // save the model and the index as well...
            // the index here is constructed taking the help
            // of estimated documents...
            inferedModel.save();
        }
        catch (Exception ex) { ex.printStackTrace(); }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: java PLDAInferencer <estimate prop> <inference prop>");
            args = new String[2];
            args[0] = "lda.properties";
            args[1] = "lda.infer.properties";
        }
        
        try {
            PLDAInferencer inferencer = new PLDAInferencer(args[0], args[1]);
			inferencer.infer();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
