/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.index;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.payloads.PayloadHelper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.payloads.AveragePayloadFunction;
import org.apache.lucene.search.payloads.PayloadFunction;
import org.apache.lucene.search.payloads.PayloadTermQuery;
import org.apache.lucene.search.similarities.BasicStats;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.search.similarities.LMSimilarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.sostruct.Question;

/**
 *
 * @author dganguly
 */

class TermScore implements Comparable<TermScore> {
    String term;
    float score;
    int freq;

    public TermScore(String term, float score) {
        this.term = term;
        this.score = score;
    }
    
    @Override
    public int compareTo(TermScore that) {
        return score < that.score? 1 : score == that.score? 0 : -1;
    }
}

class SOQuery {
    int qid;
    Query luceneQueryObj;

    public SOQuery(int qid, Query luceneQueryObj) {
        this.qid = qid;
        this.luceneQueryObj = luceneQueryObj;
    }
    
    public String toString() { return luceneQueryObj.toString(); }
}

// A simple query constructor which takes all terms to formulate a query.
class QueryConstructor {
    IndexReader reader;
    float lambda;
    float tmLambda;
    Properties prop;
    TreeMap<String, TermScore> tfMap;
    
    public QueryConstructor(Properties prop, IndexReader reader) {
        this.prop = prop;
        this.reader = reader;
        lambda = Float.parseFloat(prop.getProperty("retrieve.lambda", "0.3"));
        tmLambda = Float.parseFloat(prop.getProperty("retrieve.tm_lambda", "0.3"));
        tfMap = new TreeMap<>();
    }
        
    private List<String> getBagOfWords(String text) throws Exception {

        List<String> terms = new ArrayList<>();
        text = Question.removeTags(text);

		boolean toStem = Boolean.parseBoolean(prop.getProperty("stem", "true"));
		String stopFile = prop.getProperty("stopfile");
        Analyzer analyzer = new WhitespaceAnalyzer(Version.LUCENE_4_9); /*SOAnalyzer(toStem, stopFile)*/;
        TokenStream stream = analyzer.tokenStream("bow", new StringReader(text));
        CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
        stream.reset();

        while (stream.incrementToken()) {
			String term = termAtt.toString();
            terms.add(term);
        }

        stream.end();
        stream.close();

        return terms;
    }
    
    // Take as input a StackOverflow document and return the list
    // of terms
    BooleanQuery construct(List<String> terms, String[] tagTerms) {
        BooleanQuery q = new BooleanQuery();
        PayloadFunction pf = new AveragePayloadFunction();
        Boolean tagsOnly = Boolean.parseBoolean(prop.getProperty("retrieve.tags_only", "false"));
        
        Boolean useTags = Boolean.parseBoolean(prop.getProperty("retrieve.use_tags", "false"));
        Term thisTerm;
        
        //System.out.println("About to add " + terms.size() + " query terms..");
        if (!tagsOnly) {
            for (String term : terms) {
                thisTerm = new Term(QuestionIndexer.FIELD_BODY_WITH_TOPICS, term);
                q.add(new LMLinearCombinationTermQuery(thisTerm, pf, lambda, tmLambda), BooleanClause.Occur.SHOULD);
            }
        }
        
        if (useTags || tagsOnly) {
            for (String term : tagTerms) {
                thisTerm = new Term(QuestionIndexer.FIELD_TAGS, term);
                q.add(new TermQuery(thisTerm), BooleanClause.Occur.SHOULD);
            }
        }
        return q;
    }
    
    List<String> getSelectedTerms(String text) throws Exception {
        int nTerms = Integer.parseInt(prop.getProperty("query.nterms", "10"));
        List<String> terms = new ArrayList<String>();
        TreeMap<String, TermScore> tfMap = new TreeMap<>();
        String[] wtWords = text.split("\\s+");
        int doclen = wtWords.length;
        float termScore;
        float q_lambda = Float.parseFloat(prop.getProperty("query.lambda", "0.4"));
        float q_tmLambda = Float.parseFloat(prop.getProperty("query.tm_lambda", "0.4"));
        
        float collectionProb, collWt = (1-q_lambda-q_tmLambda);
        long numDocs = reader.numDocs();
        TermScore ts;
        List<TermScore> termScores = new ArrayList<>();

        // Collect the term so as to compute the frequencies
        for (String wtWord : wtWords) {
            String[] tokens = wtWord.split("\\" + String.valueOf(PayloadAnalyzer.delim));        
            String word = tokens[0];
            float tmProb = Float.parseFloat(tokens[1]);
            ts = tfMap.get(word);
            if (ts == null)
                ts = new TermScore(word, tmProb);
            ts.freq++;
            tfMap.put(word, ts);
        }
        
        // Revisit the map in second pass and compute scores
        for (Map.Entry<String, TermScore> entry : tfMap.entrySet()) {
            ts = entry.getValue();
            Term thisTerm = new Term(QuestionIndexer.FIELD_BODY_WITH_TOPICS, ts.term);
            collectionProb = (float)(numDocs/(double)reader.totalTermFreq(thisTerm)); // reciprocal
            termScore = (float)(Math.log(1 +
                    collectionProb*(
                        q_lambda/collWt * ts.freq/(float)doclen +
                        q_tmLambda/collWt * ts.score)
                    ));
            ts.score = termScore;
            termScores.add(ts);
        }
        
        // Sort the terms by the score values and return the top ones
        Collections.sort(termScores);
        int termCount = 0;
        for (TermScore topTermScore : termScores) {
            if (termCount >= nTerms)
                break;
            terms.add(topTermScore.term);
            termCount++;
            System.out.print(topTermScore.term + " ");
        }
        System.out.println();
        
        return terms;
    }
    
    public List<SOQuery> constructQueries() throws Exception {        
        List<SOQuery> queryList = new LinkedList<SOQuery>();
        boolean allTerms = true;
        BooleanQuery.setMaxClauseCount(20000);
        
        String queryFile = prop.getProperty("query.tsv.file");
        if (queryFile == null) {
            // the weighted tsv files
            queryFile = prop.getProperty("query.wtsv.file");
            allTerms = false;
        }
        
        FileReader fr = new FileReader(queryFile);
        BufferedReader br = new BufferedReader(fr);
        String line;
        List<String> terms;
        
        while ((line = br.readLine()) != null) {            
            String[] tokens = line.split("\t");
            String tags = tokens[1];
            String[] tagTerms = tokens[1].split("\\,");
            // the first token is the question id, the second is a list
            // of labels...
            if (allTerms)
                terms = getBagOfWords(tokens[2]);
            else
                terms = getSelectedTerms(tokens[2]);
            
            Query q = construct(terms, tagTerms);
            queryList.add(new SOQuery(Integer.parseInt(tokens[0]), q));
        }
        return queryList;
    }    
}

class ScoreDocComparator implements Comparator<ScoreDoc> {

    @Override
    public int compare(ScoreDoc o1, ScoreDoc o2) {
        return o1.score < o2.score? 1 : o1.score == o2.score? 0 : -1;
    }    
}

public class SOQuestionSearcher {
    IndexReader reader;
    IndexSearcher searcher;
    Properties prop;
    int numWanted;
    QueryConstructor qc;
    HashMap<Integer, Float> docScorePredictionMap;
    boolean isSupervised;
    
    public SOQuestionSearcher(String propFile) throws Exception {
        prop = new Properties();
        prop.load(new FileReader(propFile));
        
        String className = prop.getProperty("retrieve.topicmodel", "plda");
        String index_dir = null;
        index_dir = prop.getProperty(className + ".index");
        
        System.out.println("Running queries against index: " + index_dir);
        boolean useTopicWeights = Boolean.parseBoolean(
                prop.getProperty("retrieve.use_topics", "true"));
        try {
            File indexDir = new File(index_dir);
            reader = DirectoryReader.open(FSDirectory.open(indexDir));
            searcher = new IndexSearcher(reader);

            searcher.setSimilarity(new GeneralizedLMSimilarity(useTopicWeights));

            numWanted = Integer.parseInt(prop.getProperty("retrieve.num_wanted", "100"));
            qc = new QueryConstructor(prop, reader);
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        
        /*
        if (className.equals("splda") || className.equals("slda")) {
            isSupervised = true;
            String predictionsFile = prop.getProperty("slda.infer.predictions");
            docScorePredictionMap = new HashMap<>();
            FileReader fr = new FileReader(predictionsFile);
            BufferedReader br = new BufferedReader(fr);
            String line;
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\s+");
                int docId = Integer.parseInt(tokens[0]);
                float score = Float.parseFloat(tokens[1]);
                score = (score + 1)/2;  // [-1,1] => [0,1]
                docScorePredictionMap.put(docId, score);
            }
            br.close();
            fr.close();
        }
        */
    }

    public void retrieveAll() throws Exception {
        ScoreDoc[] hits;
        TopDocs topDocs;
        String runName = prop.getProperty("retrieve.runname", "baseline");
        
        String resultsFile = prop.getProperty("retrieve.results_file");        
        FileWriter fw = new FileWriter(resultsFile);
        
        List<SOQuery> queries = qc.constructQueries();
        
        for (SOQuery thisQuery : queries) {

            //System.out.println(thisQuery);
            TopScoreDocCollector collector = TopScoreDocCollector.create(numWanted, true);
            Query query = thisQuery.luceneQueryObj;
            searcher.search(query, collector);
            topDocs = collector.topDocs();
            hits = topDocs.scoreDocs;
            
            if (isSupervised) {
                float predFactor;
                int docId;
                // Rerank the results based on the predicted scores
                for (int i = 0; i < hits.length; ++i) {
                    Document d = searcher.doc(hits[i].doc);
                    docId = Integer.parseInt(d.get(QuestionIndexer.FIELD_ID));
                    predFactor = docScorePredictionMap.get(docId);
                    hits[i].score = 0.5f*predFactor + 0.5f*hits[i].score;
                }
                Arrays.sort(hits, new ScoreDocComparator());
            }
            
            StringBuffer buff = new StringBuffer();            
            for (int i = 0; i < hits.length; ++i) {
                int docId = hits[i].doc;
                Document d = searcher.doc(docId);
                
                buff.append(thisQuery.qid).append("\tQ0\t").
                        append(d.get(QuestionIndexer.FIELD_ID)).append("\t").
                        append((i+1)).append("\t").
                        append(hits[i].score).append("\t").
                        append(runName).append("\n");                
            }
            fw.write(buff.toString());
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
            SOQuestionSearcher searcher = new SOQuestionSearcher(args[0]);
            searcher.retrieveAll();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
