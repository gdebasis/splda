package org.index;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.standard.*;
import org.apache.lucene.analysis.tokenattributes.*;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.*;
import org.json.*;
import org.sostruct.Question;

// This class is used to index a StackOverflow question...
public class QuestionIndexer {

	IndexWriter writer;
	File topDir;
	File indexDir;
    Properties prop;
    
	HashMap<Integer, ArrayList<Integer>> linkMap;

	static final public String FIELD_LINKED = "linked";    // ids to linked questions
	static final public String FIELD_ID = "id";
	static final public String FIELD_TITLE = "title";
	static final public String FIELD_BODY = "body";
	static final public String FIELD_CODE = "code";
	static final public String FIELD_ANSWERS = "answers";  // answers for related questions
	static final public String FIELD_ANSWERS_CODE = "acode";  // answers for related questions
	static final public String FIELD_COMMENTS = "comments"; // comments in question/answers
	static final public String FIELD_TAGS = "tags"; // tags in questions
	static final public String FIELD_BODY_WITH_TOPICS = "tbody"; // indexing with the topic weights

	QuestionIndexer(String propFile) throws Exception {
        prop = new Properties();
        prop.load(new FileReader(propFile));
                
		this.topDir = new File(prop.getProperty("coll"));
        String indexPath = prop.getProperty("index");
        String mapFile = prop.getProperty("linkmap");
        
		Analyzer analyzer = new UAX29URLEmailAnalyzer(Version.LUCENE_4_9); 
        indexDir = new File(indexPath);

        IndexWriterConfig iwcfg = new IndexWriterConfig(Version.LUCENE_4_9, analyzer);
        iwcfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

		if (!DirectoryReader.indexExists(FSDirectory.open(indexDir)))
            writer = new IndexWriter(FSDirectory.open(indexDir), iwcfg);
		loadLinks(mapFile);
	}

	private void loadLinks(String linkFile) throws Exception {
        System.out.println("Loading the links in memory");
		linkMap = new HashMap<>();
		FileReader fr = new FileReader(linkFile);
		BufferedReader br = new BufferedReader(fr);
		String line = null;
		int qid, linkid;

		while ((line = br.readLine()) != null) {
			String[] tokens = line.split("\\s+");
			qid = Integer.parseInt(tokens[0]);
			linkid = Integer.parseInt(tokens[1]);
			ArrayList<Integer> links = linkMap.get(qid);
			if (links == null) {
				links = new ArrayList<>();
			}
			links.add(linkid);
			linkMap.put(qid, links);
		}
		if (br!=null) br.close();
		if (fr!=null) fr.close();
        System.out.println("Loaded links in memory");
	}

	void indexAll() throws Exception {
		if (writer == null) {
			System.err.println("Skipping indexing... Index already exists at " + indexDir.getName() + "!!");
			return;
		}

	    indexDirectory(topDir);

	    writer.close();
	}

    private void indexDirectory(File dir) throws Exception {
        File[] files = dir.listFiles();
        for (int i=0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) {
                System.out.println("Indexing directory " + f.getName());
                indexDirectory(f);  // recurse
            }
            else if (f.getName().endsWith(".json")) { // file type filter
                indexFile(f);
            }
        }
    }
 
	void indexFile(File jsonFile) throws Exception {
		System.out.println("Processing JSON file " + jsonFile.getAbsolutePath());

		FileReader fr = new FileReader(jsonFile);
		BufferedReader br = new BufferedReader(fr);
		String line;
		StringBuffer buff = new StringBuffer();
		JSONArray commentArray;

		while ((line = br.readLine()) != null) {
			buff.append(line + "\n");
		}

        try {
            JSONObject jsonObject = new JSONObject(buff.toString());
            JSONArray items = (JSONArray) jsonObject.get("items");
            JSONObject item;
            Question question = null;

            for (int i = 0; i < items.length(); i++) {
                question = null;
                try {
                    item = items.getJSONObject(i);
                    question = new Question(item);  // the current question
                }
                catch (JSONException jex) {
                    System.err.println("JSON parse exception");
                }

                if (question != null)
                    indexQuestion(question);
            }
        }
        catch (JSONException jex) {
            // simply skip this file
            System.err.println("Couldn't index file " + jsonFile.getAbsolutePath());
        }

		br.close();
		fr.close();
	}
    
	static String getContentWOTags(String body) {
		String strippedBody = body.replaceAll("\\<.*?>"," ");
		return strippedBody;
	}

	void indexQuestion(Question thisQuestion) throws Exception {

            Document doc = new Document();

            doc.add(new Field(FIELD_ID, String.valueOf(thisQuestion.getID()),
                                Field.Store.YES, Field.Index.NOT_ANALYZED));
            doc.add(new Field(FIELD_TITLE, thisQuestion.getTitle(),
                                Field.Store.YES, Field.Index.ANALYZED));
            doc.add(new Field(FIELD_BODY, thisQuestion.getBody(),
                                Field.Store.YES, Field.Index.ANALYZED));

            doc.add(new Field(FIELD_CODE, thisQuestion.getCode(),
                                Field.Store.YES, Field.Index.ANALYZED));
            doc.add(new Field(FIELD_ANSWERS_CODE, thisQuestion.getCodeFromAnswers(),
                                Field.Store.YES, Field.Index.ANALYZED));
            
            doc.add(new Field(FIELD_COMMENTS, thisQuestion.getConcatComments(),
                                Field.Store.YES, Field.Index.ANALYZED));
            doc.add(new Field(FIELD_ANSWERS, thisQuestion.getConcatAnswers(),
                                Field.Store.YES, Field.Index.ANALYZED));
            
            doc.add(new Field(FIELD_LINKED, getLinkedIds(thisQuestion),
                                Field.Store.YES, Field.Index.ANALYZED));
            doc.add(new Field(FIELD_TAGS, thisQuestion.getTags(),
                                Field.Store.YES, Field.Index.ANALYZED));

            System.out.println("Adding document " + thisQuestion.getID() + " to the index...");
            writer.addDocument(doc);
	}

	String getLinkedIds(Question thisQuestion) {
		StringBuffer buff = new StringBuffer();
		ArrayList<Integer> links = linkMap.get(thisQuestion.getID());
        if (links == null) {
            // it might hapen that there're no links to
            // this question
            return "";
        }
		for (Integer link : links) {
			buff.append(link).append(",");
		}
		if (buff.length() > 0)
			buff.deleteCharAt(buff.length()-1);
		return buff.toString();
	}


	public static void main(String[] args) throws Exception {

        if (args.length < 1) {
            System.err.println("usage: java QuestionIndexer <prop file>");
            return;
        }

        try {
            QuestionIndexer indexer = new QuestionIndexer(args[0]);
            indexer.indexAll();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
	}
}
