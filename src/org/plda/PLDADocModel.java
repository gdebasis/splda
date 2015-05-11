/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.plda;

import java.util.HashMap;
import java.util.List;

/**
 *
 * @author dganguly
 */

class PLDADocModelBase {
	Document doc;
	int nwords;
	LabelTopic[] lz;

    float[] theta;
    
    static String LabelTopicSeparator = ":";

	PLDADocModelBase(Document doc) {
        this.nwords = doc.words.length;
        this.doc = doc;
        lz = new LabelTopic[nwords];
	}

	PLDADocModelBase(String line) throws Exception {
        // A saved label-topic assignment is a space separated list
        // of tuples : labels and topics (separated by comma)
        String[] tokens = line.split("\\s+");
        nwords = tokens.length;
        lz = new LabelTopic[nwords];
        int i = 0;
        for (String token : tokens) {
            String[] tuple = token.split(LabelTopicSeparator);
            lz[i++] = new LabelTopic(Integer.parseInt(tuple[0]), Integer.parseInt(tuple[1]));
        }        
    }

    public String toString() {
        StringBuffer buff = new StringBuffer();
        for (LabelTopic jk : lz) {
            buff.append(jk.label).append(LabelTopicSeparator)
                .append(jk.topic).append(" ");
        }
        buff.deleteCharAt(buff.length()-1);
        return buff.toString();
    }
 
}

// The genertaive model corresponding to each document
class PLDADocModel extends PLDADocModelBase {
    int K;  // #topics
    int L;  // #labels 
    
	int[] nd_topics; // nd_topics[k]: #words in this document assigned to topic k, size K
    int[] nd_labels; // nd_labels[k]: #words in this document assigned to label j, size L
    
    PLDADocModel(Document doc, int K, int L) {
		super(doc);

        this.K = K;
        this.L = L;
        
        nd_topics = new int[K];
        nd_labels = new int[L];
        
        theta = new float[K];
    }
    
    @Override
    public boolean equals(Object that) {
        return this.nwords == ((PLDADocModel)that).nwords;
    }

    float getResponse() { return doc.y; }
    
   
    PLDADocModel(String line) throws Exception {
		super(line);
    }

    // Generates a random label-topic pair from the observed set of labels.
    // Global set of topics which comes from
    // global label is automatically taken care of.
    LabelTopic getRandom(PLDA plda) {
        int numLabelsForThisDoc = doc.labels.length;
        int rndLabel = (int)(Math.random() * numLabelsForThisDoc);
        
        int chosenLabelIndex = doc.labels[rndLabel];
        Label chosenLabel = plda.docs.labelMap.labels[chosenLabelIndex];
        
        // generate a random topic in this range
        int rndTopic = chosenLabel.startTopicIndex + (int)(Math.random() * chosenLabel.numTopics);
        return new LabelTopic(chosenLabelIndex, rndTopic);
    }
}

// Document model for PLDP
class PLDPDocModel extends PLDADocModelBase {

    int L;
	HashMap<Integer, Integer>[] nd_labels_topics; // nd_labels_topics[j][k]: #words in this document assigned to label j and topic k, size LxK

	PLDPDocModel(Document doc, PLDP pldp) {
		super(doc);

        this.L = pldp.docs.labelMap.getNumLabels();

		nd_labels_topics = new HashMap[L];
		for (int l = 0; l < L; l++) {
			nd_labels_topics[l] = new HashMap();
		}
	}

	void updateDocLabelTopicCounts(int j, int k, int delta) {
		HashMap<Integer, Integer> labelTopicCounts = nd_labels_topics[j];
		Integer count = labelTopicCounts.get(k);
		if (count == null) {
			if (delta > 0)
				count = delta;
			else
				return;
		}
		else
			count = count + delta;
        
		labelTopicCounts.put(k, count);
	}

	int numberTopicsAssignedToLabel(int j) {
		HashMap<Integer, Integer> labelTopicCounts = nd_labels_topics[j];
		return labelTopicCounts.size();
	}

	int numTermsAssignedToLabelTopic(int j, int k) {
		HashMap<Integer, Integer> labelTopicCounts = nd_labels_topics[j];
		Integer count = labelTopicCounts.get(k);
		return count == null? 0 : count;
	}
}
