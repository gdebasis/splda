/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.plda;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.UAX29URLEmailAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.index.PayloadAnalyzer;
import org.index.QuestionIndexer;
import org.index.SOAnalyzer;

/**
 *
 * @author dganguly
 */
class LabelTopic {
    int label;
    int topic;

    LabelTopic(int label, int topic) {
        this.label = label;
        this.topic = topic;
    }
    
    public String toString() {
        return label + ":" + topic;
    }
}


class TermPhi implements Comparable<TermPhi> {
    int   word;
    float phi;

    public TermPhi(int word, float phi) {
        this.word = word;
        this.phi = phi;
    }
    
    @Override
    public int compareTo(TermPhi that) { // descending order
        return phi < that.phi? 1 : phi == that.phi? 0 : -1;
    }
}

abstract class PLDABase {
    Properties prop;
    int K;  //#topics
    
    // Hyper-parameters
    float alpha;
    float beta;
    float Vbeta;
    int V; // vocab size
    int L;

    // Per doc stats
    Collection docs;
    int niters;
    
    // Term-topic distributions
    float[][] phi;

    //+++start Abstract methods...
    abstract boolean initModel();
    abstract LabelTopic sampling(int m, int n);
    abstract void updateRegressionParams();
	abstract void computeTheta();
    abstract void computePhi();
    //---end
    
    //PLDABase estimated;
    
    int trace;
    boolean labelProp;
    
	PLDABase(String propFile) throws Exception {
        docs = new Collection(propFile);        
        prop = docs.getProperties();
        trace = Integer.parseInt(prop.getProperty("trace.level", "0"));
        
        V = docs.wordMap.getVocabSize();
        
        beta = Float.parseFloat(prop.getProperty("plda.beta", "0.01"));
        alpha = Float.parseFloat(prop.getProperty("plda.alpha", "0.01"));
        niters = Integer.parseInt(prop.getProperty("plda.niters", "1000"));
        Vbeta = beta * V;
        L = docs.labelMap.getNumLabels();
        
        System.out.println("Total #unique words = " + V);
        System.out.println("Total #labels = " + L);
        labelProp = Boolean.parseBoolean(prop.getProperty("label_proportional", "false"));
	}

    PLDADocModelBase getDocModel(int docid) {
        return docs.get(docid).model;
    }
        
    // Load label-topic assignments
    ArrayList<PLDADocModelBase> loadDocModels(String file) throws Exception {
        FileReader fr = new FileReader(file);
        BufferedReader br = new BufferedReader(fr);
        String line = null;
        
        ArrayList<PLDADocModelBase> docModels = new ArrayList<>();
        
        while ((line = br.readLine()) != null) {
            docModels.add(new PLDADocModel(line));
        }
        
        br.close();
        fr.close();
        
        return docModels;
    }
    
    // Uniformly sample a topic given probability masses
    static int sampleTopic(TopicInfo[] p) {
        int totalNumTopics = p.length;
        int k;
        
		// cumulate multinomial parameters
		for (int topicIter = 1; topicIter < totalNumTopics; topicIter++){
			p[topicIter].prob += p[topicIter - 1].prob;
		}
        		
		// scaled sample because of unnormalized p[]
		float u = (float)Math.random() * p[totalNumTopics - 1].prob;
		
		for (k = 0; k < totalNumTopics-1; k++) {
			if (p[k].prob > u) //sample topic w.r.t distribution p
				break;
		}
        return p[k].topicId;
    }

    String getModelSpecificFile(String fileSuffix) {
        String className = this.getClass().getName().toLowerCase();                
        int lastDotIndex = className.lastIndexOf('.');
        className = className.substring(lastDotIndex+1);
        String fileName = prop.getProperty(className + "." + fileSuffix);
        return fileName;
    }
    
    void save() throws Exception {
        String saveFile = getModelSpecificFile("saveto");
        File f = new File(saveFile);
        if (f.exists() && !f.isDirectory()) {
            f.renameTo(new File(saveFile + String.valueOf(System.currentTimeMillis()/1000) + ".bk"));
        }
        FileWriter fout = new FileWriter(saveFile);
        int M = docs.numDocs();
        for (int m = 0; m < M; m++) {
            PLDADocModelBase thisDocModel = getDocModel(m);
            fout.write(thisDocModel.toString() + "\n");
        }
        fout.close();
        saveTopWords();
        
        generateIndex();
        saveDocs();
    }        
 
	public void estimate() {
        
		int M = docs.numDocs();
		System.out.println("Sampling " + M + " documents for " + niters + " iterations!");
        
        PLDADocModelBase thisDocModel = null;
        int showProgress = Integer.parseInt(docs.getProperties().getProperty("plda.showprogress", "0"));
        int mleStep = Integer.parseInt(docs.getProperties().getProperty("splda.mstep", "50"));
        
		for (int iter = 1; iter <= niters; iter++) {
			System.out.println("Gibbs Sampling: iteration " + iter + " ...");
			
			// for all w(d, n) (nth word in dth document)            
			for (int m = 0; m < M; m++) {
            	thisDocModel = getDocModel(m);
                
				for (int n = 0; n < thisDocModel.nwords; n++) {
					// z_i = z[m][n]
					// sample from p(l_i,z_i|l_-i, z_-i, w)
					LabelTopic jk = sampling(m, n);
					thisDocModel.lz[n] = jk;  // sampled (label,topic) (j,k)
				} // end for each word
                
                if (trace > 1) {
                    System.out.println("Doc: " + m + " (after " + iter + " iteations...");
                    System.out.println(thisDocModel.toString());
                }                
			} // end for each document
            
            if (showProgress > 0 && iter % showProgress == 0) {
                // Warning: Didn't store this information in a heap. (Too lazy!)
                // Hence have to reformulate every time. Hence slow!
                System.out.println("After " + iter + " iterations...");
                System.out.println(this.getTopWords());
            }

            if (iter % mleStep == 0) { 
				updateRegressionParams();
            }
		} // end iterations		
		
		computeTheta();
		computePhi();
		updateRegressionParams();
        
        try {
            save();
        }
        catch (Exception ex) { ex.printStackTrace(); }
	}
    
    int getTiedLabelId(int topicId) {
        return docs.getTiedLabelId(topicId);
    }
    
    void saveTopWords() throws Exception {
        String saveFile = getModelSpecificFile("topwords");
        File f = new File(saveFile);
        if (f.exists() && !f.isDirectory()) {
            f.renameTo(new File(saveFile + String.valueOf(System.currentTimeMillis()/1000) + ".bk"));
        }
        
        FileWriter fw = new FileWriter(saveFile);
        
        /* For debugging label names
        StringBuffer buff = new StringBuffer();
        
        // Print the label names
        for (Label l : docs.labelMap.labels) {
            buff.append(l.toString()).append("\n");
        }
        fw.write(buff.toString() + "\n");
        */
        
        /* For debugging topic-label mapping
        buff.setLength(0);        
        // Now show the mapping from the topics to the labels
        for (int k = 0; k < K; k++) {
            buff.append("Topic, Label: ");
            buff.append(k).append(", ").append(getTiedLabelId(k)).append("\n");
        }
        fw.write(buff.toString() + "\n");
        */
        
        fw.write(this.getTopWords() + "\n");
        
        if (fw!=null) fw.close();
    }
    
    String getTopWords() {
        List<TermPhi> wordsProbsList;
        StringBuffer buff = new StringBuffer();
        
        int twords = Integer.parseInt(docs.prop.getProperty("plda.out.ntopwords", "10"));
        
        // For all labels in the set of labels
        for (int j = 0; j < L; j++) {
            Label l = docs.getLabel(j); // get the label object corresponding to this sampled label id
            
            buff.append("Label: ").append(l.name).append("\n");
            
            for (int topicIter = 0; topicIter < l.numTopics; topicIter++) {
                int k = l.startTopicIndex + topicIter;
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
    
    void saveDocs() throws Exception {
        String docFile = getModelSpecificFile("txt"); // plda.txt, splda.txt etc.
        if (docFile == null)
            return;
        FileWriter fw = new FileWriter(docFile);
        
        int wordid;
        int i;
        String word;
        float p_w_d, p_w_z, sum;
        
        for (Document doc : this.docs.docs) {  // for each document
            StringBuffer buff = new StringBuffer();
            StringBuffer tagBuff = new StringBuffer();
            
            for (int l : doc.labels) {
                Label label = docs.getLabel(l);
                tagBuff.append(label.name).append(",");
            }
            if (tagBuff.length() > 0)
                tagBuff.deleteCharAt(tagBuff.length()-1);
            
            for (i = 0; i < doc.words.length; i++) { // for each word in this doc
                wordid = doc.words[i];
                word = docs.wordMap.getWord(wordid);
                // Compute topic marginalized probabilities for this word
                // i.e. P(w,d) = \sum_{k=1}^{K} P(w|z_k;\phi)P(z_k|d;\theta)
                // Restrict computation to only the topics observed for the
                // labels of this document.
                sum = 0;
                for (int l : doc.labels) {
                    Label label = docs.getLabel(l);
                    for (int topicIter = 0; topicIter < label.numTopics; topicIter++) {
                        int k = label.startTopicIndex + topicIter;
                        p_w_z = phi[k][wordid];
                        p_w_d = doc.model.theta[k];
                        sum += p_w_z * p_w_d;
                    }
                }                
                buff.append(word).append(PayloadAnalyzer.delim).append(sum).append(" ");
            }
            if (buff.length()>0)
                buff.deleteCharAt(buff.length()-1);
            // save this doc
            fw.write(doc.id + "\t" + tagBuff.toString() + "\t" + buff.toString() + "\n");
        }
        fw.close();
    }
    
    // Note: to be called both by plda and splda
    void generateIndex() throws Exception {
        String indexPath = getModelSpecificFile("index"); // plda.index, splda.index etc.
        if (indexPath == null)
            return;
        
        int wordid;
        int i;
        String word;
        float p_w_d, p_w_z, sum;
    	IndexWriter writer;
        
        System.out.println("Writing out the index");
        
        Map<String,Analyzer> analyzerPerField = new HashMap<String,Analyzer>();
        analyzerPerField.put(QuestionIndexer.FIELD_BODY_WITH_TOPICS, new PayloadAnalyzer());

        PerFieldAnalyzerWrapper aWrapper =
           new PerFieldAnalyzerWrapper(
               new WhitespaceAnalyzer(Version.LUCENE_4_9)/*SOAnalyzer(prop)*/,
               analyzerPerField);
 
        IndexWriterConfig iwcfg = new IndexWriterConfig(Version.LUCENE_4_9, aWrapper);
        iwcfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        File indexDir = new File(indexPath);
		//if (DirectoryReader.indexExists(FSDirectory.open(indexDir))) {
        //    System.err.println("Index already exists...");
        //    return;
        //}
        
        writer = new IndexWriter(FSDirectory.open(indexDir), iwcfg);        
        
        for (Document doc : this.docs.docs) {  // for each document
            StringBuffer buff = new StringBuffer();
            // for baselines (just use the tags and terms (w/o) topic payloads)
            // for retrieval
            StringBuffer tagBuff = new StringBuffer();
            
            org.apache.lucene.document.Document lucenedoc = new org.apache.lucene.document.Document();

            for (int l : doc.labels) {
                Label label = docs.getLabel(l);
                tagBuff.append(label.name).append(" ");
            }
            
            for (i = 0; i < doc.words.length; i++) { // for each word in this doc
                wordid = doc.words[i];
                word = docs.wordMap.getWord(wordid);
                // Compute topic marginalized probabilities for this word
                // i.e. P(w,d) = \sum_{k=1}^{K} P(w|z_k;\phi)P(z_k|d;\theta)
                // Restrict computation to only the topics observed for the
                // labels of this document.
                sum = 0;
                for (int l : doc.labels) {
                    Label label = docs.getLabel(l);
                    for (int topicIter = 0; topicIter < label.numTopics; topicIter++) {
                        int k = label.startTopicIndex + topicIter;
                        p_w_z = phi[k][wordid];
                        p_w_d = doc.model.theta[k];
                        sum += p_w_z * p_w_d;
                    }
                }
                
                // word followed by the topic model probability
                // passed through the delimiter token filter
                // to ensure that Lucene does all the work of saving the payload
                buff.append(word).append(PayloadAnalyzer.delim).append(sum).append(" ");
            }
            
            lucenedoc.add(new Field(QuestionIndexer.FIELD_ID, String.valueOf(doc.id),
                                Field.Store.YES, Field.Index.NOT_ANALYZED));
            
            lucenedoc.add(new Field(QuestionIndexer.FIELD_TAGS, tagBuff.toString(),
                                Field.Store.YES, Field.Index.ANALYZED));
            
            // Topic modelling retrieval
            lucenedoc.add(new Field(QuestionIndexer.FIELD_BODY_WITH_TOPICS, buff.toString(),
                                Field.Store.YES, Field.Index.ANALYZED));
            writer.addDocument(lucenedoc);            
        }
        writer.close();
    }
}

