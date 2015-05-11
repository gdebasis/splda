/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.plda;

import java.util.*;

/**
 *
 * @author dganguly
 */

class PLDP extends PLDABase {

    // Global stats: since #topics can grow, the following data structures are dynamic
	HashMap<Integer, Integer>[][] nw_labels_topics; // nw_labels_topics[j][t][k]: number of instances of
										 // word t assigned to label j, topic k, size VxLXK
										 // Note: K is not fixed, hence indirect indexing with hash
    
    // Label and topic to term distribution over the whole collection
	HashMap<Integer, Integer>[] nw_labels_topics_sum;  // nw_labels_topics_sum[k]: total number of words
											// assigned to label j topic k, size LxK    
    HashMap<Integer, List<Integer>> labelTopicMap; // to get the topic ids assigned to a label (globally)
	int K; // Growable (not really a parameter) 
    boolean newAssignments;
    int numNewTopics;

    PLDP(String propFile) throws Exception {
		super(propFile);
        
        boolean initTopics = Boolean.parseBoolean(prop.getProperty("pldp.inittopics", "false"));
        labelTopicMap = new HashMap<>();
		K = 0;

        // initialize the document models for every document object
        for (Document doc : docs.docs) {
            doc.model = new PLDPDocModel(doc, this);
        }

		nw_labels_topics = new HashMap[L][V];
        nw_labels_topics_sum = new HashMap[L];
		for (int j = 0; j < L; j++) {
			for (int t = 0; t < V; t++) {
				nw_labels_topics[j][t] = new HashMap<>();
			}
			nw_labels_topics_sum[j] = new HashMap<>();
            if (initTopics)
                createNewTopic(j);
		}
        
        if (!initModel())
			throw new Exception("Unable to initialize model!");
        
        newAssignments = false;
	}
    
	int getGlobalLabelTopicCountForTerm(int j, int k, int t) {
        int estCount = /*estimated == null?*/ 0 /*: ((PLDP)estimated).getGlobalLabelTopicCountForTerm(j, k, t)*/;
		HashMap<Integer, Integer> tcounts = nw_labels_topics[j][t];
		Integer tcount = tcounts.get(k);
		return tcount == null? 0 : estCount + tcount.intValue();	
	}

	int getGlobalLabelTopicCount(int j, int k) {
        int estCount = /*estimated == null?*/ 0 /*: ((PLDP)estimated).getGlobalLabelTopicCount(j, k)*/;
		HashMap<Integer, Integer> tcounts = nw_labels_topics_sum[j];
		Integer tcount = tcounts.get(k);
		return tcount == null? 0 : estCount + tcount.intValue();	
	}

	void updateGlobalLabelTopicCount(int j, int k, int t, int delta) {
		HashMap<Integer, Integer> tcounts = nw_labels_topics[j][t];
		Integer tcount = tcounts.get(k);
		if (tcount == null) {
			if (delta < 0)
				return;
			tcount = 0;
		}
		tcounts.put(k, tcount+delta);

		tcounts = nw_labels_topics_sum[j];
		tcount = tcounts.get(k);
		if (tcount == null) {
			if (delta < 0)
				return;
			tcount = 0;
		}
		tcounts.put(k, tcount+delta);
	}

    @Override
	boolean initModel() {
        Properties prop = docs.getProperties();
        String modelFile = prop.getProperty("pldp.loadfrom");
        ArrayList<PLDADocModelBase> loadedModels = null;
        if (modelFile != null) {
            try {
                loadedModels = loadDocModels(modelFile);
            }
            catch (Exception ex) { ex.printStackTrace(); }
        }
        else
            newAssignments = true;
        
        LabelTopic labelTopic;
		int M = docs.numDocs();
        
        PLDPDocModel thisDocModel;
        
        for (int m = 0; m < M; m++) {
            thisDocModel = (PLDPDocModel)getDocModel(m);
            // the labels for this document are observed
            // unlike LDA 
            for (int n = 0; n < thisDocModel.nwords; n++) {
                // choose a label at random (from the observed
                // set of labels) and choose a
                // topic at random within its range
                if (modelFile == null) {
                    labelTopic = sampling(m, n);
                    thisDocModel.lz[n] = labelTopic;
                }
                else {
                    // check if the loaded model is consistent with
                    // this one.. else flag an error
                    PLDPDocModel loadedModel = (PLDPDocModel)loadedModels.get(m);
                    if (!thisDocModel.equals(loadedModel))
                        return false;
                    thisDocModel.lz = loadedModel.lz;
                    labelTopic = thisDocModel.lz[n];

        			// Update counts for the newly sampled label-topic
        			thisDocModel.updateDocLabelTopicCounts(labelTopic.label, labelTopic.topic, 1);
        			this.updateGlobalLabelTopicCount(labelTopic.label, labelTopic.topic, n, 1);
                }
            }
        }
        return true;
    }
    
	float samplingProb(PLDPDocModel docmodel, int j, int k, int t) {
        int numTermsInLabelTopic = docmodel.numTermsAssignedToLabelTopic(j, k);
        return numTermsInLabelTopic == 0? alpha/(float)V :
            docmodel.numTermsAssignedToLabelTopic(j, k) * 
            (getGlobalLabelTopicCountForTerm(j, k, t) + beta) /
            (getGlobalLabelTopicCount(j, k) + Vbeta);        
	}
    
    int createNewTopic(int j) { // update the data structures
        List<Integer> topics = labelTopicMap.get(j);
        if (topics == null) {
            topics = new ArrayList<>();
        }
        topics.add(K);
        labelTopicMap.put(j, topics);
        K++;
        return K-1;
    }
    
    @Override
    void updateRegressionParams() {
        // Do nothing
    }

    // Uniformly sample a topic given probability masses
    LabelTopic sampleLabelTopic(ArrayList<LabelTopic> lz, float[] p) {
        int totalNumTopics = p.length;
        int j, topic;
        
		// cumulate multinomial parameters
		for (int topicIter = 1; topicIter < totalNumTopics; topicIter++){
			p[topicIter] += p[topicIter - 1];
		}
        		
		// scaled sample because of unnormalized p[]
		float u = (float)Math.random() * p[totalNumTopics - 1];
		
		for (topic = 0; topic < totalNumTopics; topic++){
			if (p[topic] > u) //sample topic w.r.t distribution p
				break;
		}
        LabelTopic jk = lz.get(topic);
        return jk;
    }
    
 	// The generation process is different in PLDP. There's
	// no fixed range of topics.
	// Note: Largely based on Ramage's scala code.
    // newAssignments is set to true during initialization of
    // model. For Gibbs sampling, this is set to false which
    // enables us to decrement counts before sampling.
    @Override
    LabelTopic sampling(int m, int n) {
        PLDPDocModel docmodel = (PLDPDocModel)getDocModel(m);
		int t = docmodel.doc.words[n];
        int j, k;
        ArrayList<LabelTopic> labelTopics = new ArrayList<>();
        ArrayList<Float> probMassForTopics = new ArrayList<>(); // might grow
        
        if (!newAssignments) {
            // Decrement counts before proceeding
        	LabelTopic jk = docmodel.lz[n];
            j = jk.label;
            k = jk.topic;
            docmodel.updateDocLabelTopicCounts(j, k, -1);
            this.updateGlobalLabelTopicCount(j, k, t, -1);
        }
        
        // The rest is identical for (new/re-) assignments
       	for (int label : docmodel.doc.labels) {
            List<Integer> topics = labelTopicMap.get(label);            
            // proportional probabilities to select an existing topic
            for (int topic : topics) {
                labelTopics.add(new LabelTopic(label, topic));
                probMassForTopics.add(samplingProb(docmodel, label, topic, t));
            }
            // additional prob. of selecting a new topic
            labelTopics.add(new LabelTopic(label, K));
            probMassForTopics.add(alpha/(float)V);
		}

        int numTopics = probMassForTopics.size();
        float[] p = new float[numTopics];        
        for (int i = 0; i < numTopics; i++) {
            p[i] = probMassForTopics.get(i);
        }
        
        // Get a new sampled topic
        LabelTopic jk = sampleLabelTopic(labelTopics, p);
        if (jk.topic == K) {
            // did we select the option of creating a new topic?
            // if so, then create one corresponding to a label drawn at random
            createNewTopic(jk.label);
            System.out.println("Created new topic for label " + jk.label);
            numNewTopics++;
        }
        // Get its corresponding label
        j = jk.label;
        k = jk.topic;
        
        // Update counts for the newly sampled label-topic
        docmodel.updateDocLabelTopicCounts(j, k, 1);
        this.updateGlobalLabelTopicCount(j, k, t, 1);
        
        return new LabelTopic(j, k);
    }

    @Override
   	void computeTheta() {
		int M = docs.numDocs();
        float Kalpha = K*alpha;

		for (int m = 0; m < M; m++) {
            PLDPDocModel thisDocModel = (PLDPDocModel)getDocModel(m);
			thisDocModel.theta = new float[K];  // zero-init
           
		   	// unlike PLDA (where we had a one-one mapping between
			// labels and topics), we need to iterate over the labels
			// to get to their allocated topics
       		for (int j : thisDocModel.doc.labels) {
				for (int k : thisDocModel.nd_labels_topics[j].keySet()) {
					thisDocModel.theta[k] =
							(thisDocModel.nd_labels_topics[j].get(k) + alpha)/
							(thisDocModel.nwords + Kalpha);
				}
			}
		}
	}
	
    @Override
	void computePhi() {
		phi = new float[K][V];
		for (int t = 0; t < V; t++) {
			for (int j = 0; j < L; j++) {
				for (int k : nw_labels_topics[j][t].keySet()) {
                    phi[k][t] = (getGlobalLabelTopicCountForTerm(j, k, t) + beta)/
                                (getGlobalLabelTopicCount(j, k) + Vbeta);
				}
			}
		}
        System.out.println("Created " + numNewTopics + " new topics!");
	}
    
    @Override
	String getTopWords() {
        List<TermPhi> wordsProbsList;
        StringBuffer buff = new StringBuffer();
        
        int twords = Integer.parseInt(docs.prop.getProperty("plda.out.ntopwords", "10"));
        
        // For all labels in the set of labels
        for (int j = 0; j < L; j++) {
            Label l = docs.getLabel(j); // get the label object corresponding to this sampled label id
            
            buff.append("Label: ").append(l.name).append("\n");
            
			List<Integer> topics = labelTopicMap.get(j);
			if (topics == null)
				continue;

            for (int k : topics) {
                wordsProbsList = new ArrayList<>(); 
                
                for (int w = 0; w < V; w++) {
                    TermPhi p = new TermPhi(w, phi[k][w]);
                    wordsProbsList.add(p);
                } // end foreach word

                // print topic				
                buff.append("Topic: ").append(k).append("\n");
                
                Collections.sort(wordsProbsList);

                for (int i = 0; i < twords; i++) {
                    TermPhi termPhi = wordsProbsList.get(i);
                    String term = docs.wordMap.getWord(termPhi.word);
                    buff.append("(").append(term).append(",").append(String.format("%.4f", termPhi.phi)).append(")\t");
                }
                buff.append("\n");
            } // end foreach topic
        } // end for each label

        return buff.toString();
    }

    public static void main(String[] args) {
        String propFile = "init.properties";
        if (args.length >= 1) {
            propFile = args[0];
        }
        try {
            PLDP pldp = new PLDP(propFile);
            pldp.estimate();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}

