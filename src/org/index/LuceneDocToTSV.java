/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.index;

import java.io.*;
import java.util.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.en.*;
import org.apache.lucene.analysis.core.*;
import org.apache.lucene.analysis.standard.*;
import org.apache.lucene.analysis.tokenattributes.*;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.sostruct.Question;

/**
 *
 * @author dganguly
 * Create a TSV which is to be read by the topic modeling packages.
 * TSV format: tags separated by commas <tab> Document text <tab> link ids comma separated
 * 
 */

class Tag {
    String name;
    Tag parent;  // reference to an ancestor tag
    
    Tag(String name) { this.name = name; }
}

public class LuceneDocToTSV {
    IndexReader reader;
    Properties prop;
    HashMap<String, Tag> tagSyns;
    HashMap<String, Integer> topTags;
    HashMap<Integer, String> docMap; // to ensure that we output docs with unique ids
    
    static final String delim = "\t";
    
    public LuceneDocToTSV(String propFile) throws Exception {
        prop = new Properties();
        prop.load(new FileReader(propFile));
        
        String tagSynFile = prop.getProperty("tagsynmap");
        loadTags(tagSynFile);        
        
        String topTagsFile = prop.getProperty("toptags");
        loadTopTags(topTagsFile);
        docMap = new HashMap<>();
    }
    
    // for unit testing the tag synonymy
    public LuceneDocToTSV() throws Exception  {
        loadTags("./test.tag");        
        docMap = new HashMap<>();
    }
    
    void loadTopTags(String tagFile) throws Exception {
        FileReader fr = new FileReader(tagFile);
        BufferedReader br = new BufferedReader(fr);
        String line;
        
        topTags = new HashMap<>();
        while ((line = br.readLine()) != null) {
            topTags.put(line, 0);
        }
        br.close();
        fr.close();        
    }
    
    void loadTags(String tagSynFile) throws Exception {
        FileReader fr = new FileReader(tagSynFile);
        BufferedReader br = new BufferedReader(fr);
        String line;
        
        tagSyns = new HashMap<>();
        
        while ((line = br.readLine()) != null) {
            String[] tags = line.split("\\s+");
            Arrays.sort(tags); // ensure that the smaller one is set as the key
            
            Tag childTag = tagSyns.get(tags[1]);
            if (childTag == null)
                childTag = new Tag(tags[1]);

            Tag parentTag = tagSyns.get(tags[0]);
            if (parentTag == null)
                parentTag = new Tag(tags[0]);
            
            // if parent of child already exists, then
            // traverse the parent links until you end up in a node
            // which doesn't have a parent
            if (childTag.parent != null) {
                Tag p = childTag;
                while (p.parent != null) {
                    p = p.parent;
                }
                // got a node 'p' whose parent is null
                if (parentTag != p)
                    parentTag.parent = p;
            }
            else {            
                childTag.parent = parentTag;
            }
            
            tagSyns.put(childTag.name, childTag);
            tagSyns.put(parentTag.name, parentTag);            
        }
        br.close();
        fr.close();
    }
    
    String getNormalizedTag(String tagName) {
        Tag tag = tagSyns.get(tagName);
        
        if (tag != null) {
            Tag p = tag;
            while (p.parent != null) p = p.parent;
            
            // Either the normalized tag name or the tag name itself MUST
            // belong to the list of top tags. If not, return NULL
            if (topTags.get(p.name) == null && topTags.get(tagName) == null)
                return null;
            
            //if (p != tag)
            //    System.out.println("Substituting " + p.name + " for " + tagName);
            
            return p.name;
        }
        return (topTags.get(tagName) == null)? null : tagName;
    }
    
    // Get comma separated tags
    // Note: Also checks if all tags belong to the list of popular tags
    // If not returns NULL so that this document can be skipped    
    String getNormalizedTags(String tagcsv) {
        StringBuffer tagstr = new StringBuffer();
        String[] tags = tagcsv.split(",");
        for (String tag: tags) {
            String ntag = getNormalizedTag(tag);
            if (ntag == null)
                return null;
            else {
                // for maintaining freq of tags
                Integer c = topTags.get(tag);
                if (c == null) {
                    c = topTags.get(ntag);
                    c++;
                    topTags.put(ntag, c);
                }
                else {
                    c++;
                    topTags.put(tag, c);
                }
            }
            tagstr.append(ntag).append(",");
        }
        
        if (tagstr.length() > 0)
            tagstr.deleteCharAt(tagstr.length()-1);
        return tagstr.toString();
    }
    
    public String convertDoc(int docId) throws Exception {
        StringBuffer buff = new StringBuffer();
        StringBuffer qbuff = new StringBuffer();
        
        Document d = reader.document(docId);
        // append docid
        int questionId = Integer.parseInt(d.get(QuestionIndexer.FIELD_ID));
        buff.append(questionId).append("\t");
        
        // Append the tags
        String tags = getNormalizedTags(d.get(QuestionIndexer.FIELD_TAGS));
        if (tags == null)
            return null;
        buff.append(tags).append(delim);
        
        // Get a comma separated list of fields to model
        String fieldsToModel = prop.getProperty("model.fields",
                                QuestionIndexer.FIELD_BODY);
        
        // Extract content if present in this properties value
        if (fieldsToModel.indexOf(QuestionIndexer.FIELD_TITLE) >= 0)
            qbuff.append(d.get(QuestionIndexer.FIELD_TITLE)).append(" ");
        if (fieldsToModel.indexOf(QuestionIndexer.FIELD_BODY) >= 0)
            qbuff.append(d.get(QuestionIndexer.FIELD_BODY)).append(" ");
        if (fieldsToModel.indexOf(QuestionIndexer.FIELD_ANSWERS) >= 0)
            qbuff.append(d.get(QuestionIndexer.FIELD_ANSWERS)).append(" ");
        if (fieldsToModel.indexOf(QuestionIndexer.FIELD_COMMENTS) >= 0)
            qbuff.append(d.get(QuestionIndexer.FIELD_COMMENTS)).append(" ");
        
        String qtext = getBagOfWords(qbuff.toString());
        buff.append(qtext);
        buff.append(delim);
        
        // Append the links
        buff.append(d.get(QuestionIndexer.FIELD_LINKED));
        
        docMap.put(questionId, buff.toString());
        return buff.toString();        
    }
    
    /* Convert a question to bag-of-words */
    private String getBagOfWords(String text) throws Exception {

        StringBuffer buff = new StringBuffer();
        text = Question.removeTags(text);

		boolean toStem = Boolean.parseBoolean(prop.getProperty("stem", "true"));
		String stopFile = prop.getProperty("stopfile");
        Analyzer analyzer = new SOAnalyzer(toStem, stopFile);
        TokenStream stream = analyzer.tokenStream("bow", new StringReader(text));
        CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
        stream.reset();

        while (stream.incrementToken()) {
			String term = termAtt.toString();
            buff.append(term).append(" ");
        }

        stream.end();
        stream.close();

        return buff.toString();
    }
    
    
    public void convertAll() throws Exception {
        String indexLocation = prop.getProperty("index");        
        reader = DirectoryReader.open(FSDirectory.open(new File(indexLocation)));
        
        String oFileName = prop.getProperty("doctext.file"); // the o/p text file
        FileWriter fw = new FileWriter(oFileName);
        String docText = null;
        
        int maxDoc = reader.maxDoc();
        for (int i = 0; i < maxDoc; i++) {
            convertDoc(i);
        }

        // Print out the unique docs
        for (Map.Entry<Integer, String> e : docMap.entrySet()) {
            docText = e.getValue();
            fw.write(docText + "\n");            
        }
        
        if (fw!=null) fw.close();       
        if (reader!=null) reader.close();
        
        // Show tag freq
        int sum = 0;
        for (Map.Entry<String, Integer> entry : topTags.entrySet()) {
            String tag = entry.getKey();
            //int c = entry.getValue();
            //System.out.println(tag + ": " + c);
            sum += 1;
        }
        System.out.println("#tags:" + topTags.size() + ", #usages: " + sum);
    }
    
    public static void main(String[] args) {
        String propFile;
        if (args.length < 1) {
            System.err.println("Usage: java LuceneDocToTSV <prop file>");
            propFile = "init.properties";
            //return;
        }
        else {
            propFile = args[0];
        }
        
        try {
            LuceneDocToTSV docToTSV = new LuceneDocToTSV(propFile);
            docToTSV.convertAll();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        
        /*
        try {
            LuceneDocToTSV docToTSV = new LuceneDocToTSV();
            System.out.println(docToTSV.getNormalizedTag("a"));
            System.out.println(docToTSV.getNormalizedTag("b"));
            System.out.println(docToTSV.getNormalizedTag("c"));
            System.out.println(docToTSV.getNormalizedTag("d"));
            System.out.println(docToTSV.getNormalizedTag("e"));
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        */
    }
    
}
