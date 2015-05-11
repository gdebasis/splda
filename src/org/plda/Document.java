/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.plda;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Properties;
import java.util.TreeMap;

/**
 *
 * @author dganguly
 * The document object contains data structures for maintaining
 * per-document topic/label assignments in PLDA (and variants).
 * 
 * Each document is a tab-separated text file comprising of the id,
 * labels (separated by commas), text and response variable or link
 * to other document id. Note: No tabs are allowed in the document text.
 * [id] [tags] [text] [link docids]
 * 
 */

class MalformedDocumentException extends Exception {
    String msg;

    public MalformedDocumentException(String msg) {
        this.msg = msg;
    }
    
    @Override
    public String toString() {
        return msg;
    }
}

class BiHashMap<K, V> {
    HashMap<K, V> fwdmap;
    HashMap<V, K> revmap;

    public BiHashMap() {
        fwdmap = new HashMap<>();
        revmap = new HashMap<>();
    }
    
    boolean containsKey(K key) {
        return fwdmap.containsKey(key);
    }
        
    public V getFwd(K key) {
        return fwdmap.get(key);
    }
    
    public K getRev(V key) {
        return revmap.get(key);
    }
    
    public void put(K key, V value) {
        fwdmap.put(key, value);
        revmap.put(value, key);
    }
}

// In addition to the word-id mapping, this also
// has to keep track of the topic id ranges.
// Label - topics may be uniformly distributed
// or may be specified by a tab-separated file
// where the number of topics for each label is specified.

class Label {
    String name;
    int    id;
    int    startTopicIndex; // range of topics: [startTopicIndex..startTopicIndex+numTopics]
    int    numTopics;  // the range of topics for this label is [id, id+numTopics-1]
    
    Label(String name, int id) { this.name = name; this.id = id;}
    
    Label(String name, int id, int numTopics) {
        this.name = name;
        this.id = id;
        this.numTopics = numTopics;
    }
    
    public String toString() { return name + ":" + id + "(" + startTopicIndex + ", " + (startTopicIndex + numTopics - 1) + ")"; }
}

// For maintaining bi-directional mappings
class WordMap {
    BiHashMap<String, Integer> map;    
    int maxId;

    public WordMap() {
        maxId = 0;
        map = new BiHashMap<>();
    }
    
    public boolean isOOV(String word) {
        return !map.containsKey(word);
    }
    
    public int getId(String word) {
        return map.getFwd(word);
    }
    
    public String getWord(int id) {
        return map.getRev(id);
    }
    
    public int put(String word) {
        Integer wordId = map.getFwd(word);
        if (wordId == null) {
            wordId = maxId++;
            map.put(word, wordId);
        }
        return wordId;
    }
    
    int getVocabSize() { return maxId; }
}

class LabelMap extends WordMap {
    Label[] labels;
    NavigableMap<Integer, Integer> labelRangeMap;
    int K; // total number of topics
    
    static final String GlobalLabel = "Global";

    public LabelMap() {
        labelRangeMap = new TreeMap<>();
        K = 0;
    }

    int getNumLabels() { return labels == null? 0 : labels.length; } 

    /* Load the number of topics for each label from a file
       which has tab separated label name followed by the
       number of topics for that label.
       For any label missing in this file, the number of topics
       is set to the default value.
       Note: This is to be called for setting the numTopics field of
       each label object.
    */
    void loadLabelTopicDistribution(Properties prop) throws Exception {

        String labelTopicFile = prop.getProperty("label.topic.map");
        int perLabelTopics = Integer.parseInt(prop.getProperty("plda.perlabel.topics", "10"));
        
        // Construct the label objects now.. We've now seen the documents
        int i;
        labels = new Label[maxId];
        
        for (i = 0; i < maxId; i++) {
            String labelName = map.getRev(i);
            labels[i] = new Label(labelName, i);
            labels[i].numTopics = perLabelTopics;
        }
        
        if (labelTopicFile != null) {
            // Load num labels from an external file.
            FileReader fr = new FileReader(labelTopicFile);
            BufferedReader br = new BufferedReader(fr);
            String line = null;
            while ( (line = br.readLine()) != null ) {
                String[] tokens = line.split("\\t+");
                if (tokens.length < 2) {
                    System.err.println("Malformed content in label-topic file: " + line);
                    continue;
                }
                Integer lid = map.getFwd(tokens[0]); // get label id from name
                if (lid == null) {
                    throw new MalformedDocumentException("Label named " + tokens[0] + "not found.");
                }
                Label l = labels[lid];
                l.numTopics = Integer.parseInt(tokens[1]);
            }
            if (br!=null) br.close();
            if (fr!=null) fr.close();            
        }
        
        // construct the label range map
        int topicStart = 0;
        for (i = 0; i < maxId; i++) { // all label ids are within this range
            Label l = labels[i];
            for (int k = 0; k < l.numTopics; k++) {
                labelRangeMap.put(topicStart + k, i);
            }
            l.startTopicIndex = topicStart;
            topicStart += l.numTopics;
        }
        K = topicStart;
    }
    
    int getNumTopics(String name) {
        Label l = labels[map.getFwd(name)];
        return l.numTopics;
    }
        
    int getTiedLabelId(int topicId) {
        return labelRangeMap.get(topicId);
    }
    
    int getTotalNumTopics() { return K; }
}

class Document {
    int[] labels;   // an index into a global array of label names
    int id;
    int[] words;
    float y;        // the regression output
    int[] links;    // links to other documents
    boolean addGlobal;
    
    PLDADocModelBase model;
    
    // Maps words to strings and vice-versa
    private int[] mapWords(String[] words, WordMap map, boolean isLabel) {
        int[] wordids = isLabel && addGlobal? new int[words.length+1] : new int[words.length];
        int i = 0;
        
        // Insert the global label for every document.
        // Note that since this is the first call to the label-map
        // the label-id assigned to the global label is 0.
        if (isLabel && addGlobal) {
            wordids[i++] = map.put(LabelMap.GlobalLabel);
        }
        
        for (String word : words) {
            wordids[i++] = map.put(word);
        }
        
        return wordids;
    }

    public Document(String line, int lineNum,
            WordMap wordMap, LabelMap labelMap,
            boolean addGlobal, boolean ignoreLabels)
            throws MalformedDocumentException {
    
        this.addGlobal = !ignoreLabels? true : addGlobal;
        
        // The label and wordmaps are output parameters
        // (part of the documents object).
        String[] tokens = line.split("\\t");
        
        if (tokens.length < 3) {
            throw new MalformedDocumentException("Document " + lineNum + " not in expected format");
        }
        try {
            this.id = Integer.parseInt(tokens[0]);
        }
        catch (NumberFormatException nex) {
            throw new MalformedDocumentException("Illegal document id in line: " + lineNum);
        }
        
        if (tokens.length >= 4) {
            String[] doclinks = tokens[3].split(",");
            links = new int[doclinks.length];
            for (int i = 0; i < doclinks.length; i++) {
                this.links[i] = Integer.parseInt(doclinks[i]);
            }
            y = (float)doclinks.length;
        }
        
        String[] labels = {LabelMap.GlobalLabel};
        if (!ignoreLabels)
            labels = tokens[1].split(",");
        
        String[] words = tokens[2].split(" +");
        
        this.labels = mapWords(labels, labelMap, true);
        this.words = mapWords(words, wordMap, false);
    }
    
    public String toString() {
        StringBuffer buff = new StringBuffer();
        for (int label : labels) {
            buff.append(label).append(",");
        }
        buff.append("\t");
        if (links != null) {
            for (int link : links) {
                buff.append(link).append(",");
            }
        }
        buff.append("\t");
        for (int word : words) {
            buff.append(word).append(",");
        }
        return buff.toString();
    }
}

class Collection {
    List<Document> docs;
    WordMap wordMap;
    LabelMap labelMap;
    File ifile;
    Properties prop;
    
    Collection(String propFile) throws Exception {
        prop = new Properties();
        prop.load(new FileReader(propFile));
        
        String fileName = prop.getProperty("doctext.file");
        this.ifile = new File(fileName);
        
        docs = new ArrayList<>();
        wordMap = new WordMap();
        labelMap = new LabelMap();
        
        load();
    }

    Properties getProperties() { return prop; }
    
    int numDocs() { return docs.size(); }
    
    void load() {
        System.out.println("Loading collection in memory...");
        try (
            BufferedReader br = new BufferedReader(new FileReader(ifile));
        ) {
            String line = null;
            Document d = null;
            int lc = 0;
            boolean addGlobal = Boolean.parseBoolean(prop.getProperty("global_label", "false"));
            boolean ignoreLabels = Boolean.parseBoolean(prop.getProperty("ignore_labels", "false"));
            
            while ( (line = br.readLine()) != null ) {
                try {
                    d = new Document(line, ++lc, wordMap, labelMap, addGlobal, ignoreLabels);
                }
                catch (MalformedDocumentException mdex) {
                    System.err.println(mdex);
                }
                if (d != null)
                    docs.add(d);
            }

            // After all documents are loaded update the label map.
            // No need to process wordmap.
            labelMap.loadLabelTopicDistribution(prop);
        }
        catch (Exception ex) {
            System.err.println("Unable to load documents from file " + ifile.getName());
            ex.printStackTrace();
        }
    }
    
    public Document get(int i) {
        return this.docs.get(i);
    }

    public Label getLabel(int labelId) {
        return this.labelMap.labels[labelId];
    }
    
    public int getTiedLabelId(int topicId) {
        return labelMap.getTiedLabelId(topicId);
    }
    
    // Just for testing!!
    public static void main(String[] args) {
        String propertiesFile = "init.properties";
        Collection docs = null;
        try {
            docs = new Collection(propertiesFile);
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        
        System.out.println(docs.get(0));
        System.out.println(docs.get(95));
    }
}
