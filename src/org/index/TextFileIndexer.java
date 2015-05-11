/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.index;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

/**
 * The topic modeller output is written to a file (of the same tsv format)
 * which is then used to create the Lucene index
 * @author Debasis
 */
public class TextFileIndexer {
    Properties prop;
    
    TextFileIndexer(String propFile) throws Exception {
        prop = new Properties();
        prop.load(new FileReader(propFile));        
    }
    
    void writeIndex() throws Exception {
        
        String tsvFile = prop.getProperty("stipped_tag_tsv");
        if (tsvFile != null) {
            putTags(tsvFile);
            return;
        }
        
        String docFileName, indexPath;
        String className;
        className = prop.getProperty("retrieve.topicmodel", "plda");
        docFileName = prop.getProperty(className + ".txt");
        indexPath = prop.getProperty(className + ".index");
        
    	IndexWriter writer;
        Map<String,Analyzer> analyzerPerField = new HashMap<String,Analyzer>();
        analyzerPerField.put(QuestionIndexer.FIELD_BODY_WITH_TOPICS, new PayloadAnalyzer());

        PerFieldAnalyzerWrapper aWrapper =
           new PerFieldAnalyzerWrapper(
               new WhitespaceAnalyzer(Version.LUCENE_4_9)/*SOAnalyzer(prop)*/,
               analyzerPerField);
 
        IndexWriterConfig iwcfg = new IndexWriterConfig(Version.LUCENE_4_9, aWrapper);
        iwcfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        
        File indexDir = new File(indexPath);        
        writer = new IndexWriter(FSDirectory.open(indexDir), iwcfg);        

        String line;
        FileReader fr = new FileReader(docFileName);
        BufferedReader br = new BufferedReader(fr);
        
        while ((line = br.readLine()) != null) {
            org.apache.lucene.document.Document lucenedoc =
                    new org.apache.lucene.document.Document();
            
            String[] tokens = line.split("\\t");
            String id = tokens[0];
            String content = tokens[2];
            tokens[1] = tokens[1].replaceAll("Global,", "");
            String tags = tokens[1].replace(',', ' ');
            
            lucenedoc.add(new Field(QuestionIndexer.FIELD_ID, id,
                                Field.Store.YES, Field.Index.NOT_ANALYZED));
            
            lucenedoc.add(new Field(QuestionIndexer.FIELD_TAGS, tags,
                                Field.Store.YES, Field.Index.ANALYZED));
            
            lucenedoc.add(new Field(QuestionIndexer.FIELD_BODY_WITH_TOPICS, content,
                                Field.Store.YES, Field.Index.ANALYZED));
            writer.addDocument(lucenedoc);
        }
        writer.close();
    }
    
    // LDA estimation index doesn't contain the tags. But we need the
    // tags in the retrieval. Hence have to dump the index to tsv and write
    // it back with the tags.
    void putTags(String tsvFile) throws Exception {
        FileWriter fw = new FileWriter(tsvFile);
        String className = prop.getProperty("retrieve.topicmodel", "lda");
        String indexLocation = prop.getProperty(className + ".index");        
        IndexReader reader = DirectoryReader.open(FSDirectory.open(new File(indexLocation)));
        
        int maxDoc = reader.maxDoc();
        for (int i = 0; i < maxDoc; i++) {
            Document d = reader.document(i);
            String content = d.get(QuestionIndexer.FIELD_BODY_WITH_TOPICS);
            fw.write(content + "\n");
        }
        fw.close();
        reader.close();
    }
    
    public static void main(String[] args) {
        if (args.length < 1) {
            args = new String[1];
            args[0] = "retrieve.properties";
        }
        try {
            TextFileIndexer indexer = new TextFileIndexer(args[0]);
            indexer.writeIndex();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
