package org.crawler;

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
import org.index.*;
import org.json.*;
import org.jsoup.*;
import org.sostruct.Question;

// This class is used to index a StackOverflow question...
class LinkedQuestionExtractor {

    File topDir;
    static final int delay = 5000;

    LinkedQuestionExtractor(String topDir) throws Exception {
            this.topDir = new File(topDir);
    }

    void processAll() throws Exception {
        processDirectory(topDir);
    }

private void processDirectory(File dir) throws Exception {
    File[] files = dir.listFiles();
    for (int i = 0; i < files.length; i++) {
        File f = files[i];
        if (f.isDirectory()) {
            System.out.println("Processing directory " + f.getName());
            processDirectory(f);  // recurse
        }
        else if (f.getName().endsWith(".json")) { // file type filter
            processFile(f);
            if (i != files.length-1) {
                System.out.println("Waiting for " + delay + " ms");
                Thread.sleep(delay);	  
            }
        }
    }
}

    void processFile(File jsonFile) throws Exception {

        File parentDir = jsonFile.getParentFile();
        File linkedDir = new File(parentDir.getCanonicalPath() + "/linked");

        System.out.println("Processing JSON file " + jsonFile.getName());

        FileReader fr = new FileReader(jsonFile);
        BufferedReader br = new BufferedReader(fr);
        String line;
        StringBuffer buff = new StringBuffer();
        JSONArray commentArray;

        while ((line = br.readLine()) != null) {
            buff.append(line + "\n");
        }

        JSONObject jsonObject = new JSONObject(buff.toString());
        JSONArray items = (JSONArray) jsonObject.get("items");
        JSONObject item;
        Question question = null;

        final int numQuestionToQuery = 100;
        Question[] qlist = new Question[numQuestionToQuery];
        int numQuestions = 0;

        if (!linkedDir.exists()) {
            try {
                    System.out.println("Creating dir " + parentDir.getCanonicalPath() + "/linked");
                    linkedDir.mkdir();
            } catch (SecurityException se) { }
        }

        FileWriter fout = new FileWriter(linkedDir.getCanonicalPath() + "/" + jsonFile.getName());
        String json = null;

        for (int i = 0; i < items.length(); i++) {
            question = null;
            try {
                item = items.getJSONObject(i);
                question = new Question(item);  // the current question
            }
            catch (JSONException jex) {
                System.err.println("JSON parse exception");
            }
            if (question == null)
                continue;

            if (numQuestions == numQuestionToQuery) {
                json = processQuestion(qlist, numQuestions);  // batch extract links (for 100)
                if (json != null)
                        fout.write(json + "\n");
                numQuestions = 0;
            }
            else
                qlist[numQuestions++] = question;
        }

        if (numQuestions > 0 && numQuestions <= numQuestionToQuery) {
            json = processQuestion(qlist, numQuestions);  // batch extract links (for 100)
            if (json != null)
                fout.write(json + "\n");
        }

        br.close();
        fr.close();
        fout.close();
    }

    String processQuestion(Question[] questions, int numQuestions) throws Exception {

        System.out.println("Extracting linked questions for ids: " +
                                        questions[0].getID() + "-" + questions[numQuestions-1].getID());

        String urlPrefix = "https://api.stackexchange.com/2.2/questions/";
        String urlSuffix = "/linked?site=stackoverflow&filter=!)E0gne8hbBtKRihDwO5j_ZFVA2zh5*rgM93GjBpUU1pUo1w*9&pagesize=100&key=SkjoA6hPLw50*v8PEShiUw((";

        // Fill in the question ids
        StringBuffer qidBuff = new StringBuffer();
        for (int i = 0; i < numQuestions; i++)
            qidBuff.append(questions[i].getID()).append(";");

        if (qidBuff.length() > 0)
            qidBuff.deleteCharAt(qidBuff.length()-1);

        String url = urlPrefix + qidBuff.toString() + urlSuffix;

        String json = null;
        StringBuffer jsonBuff = new StringBuffer();
        boolean hasMore = true;

        while (hasMore) {
            try {
                json = Jsoup.connect(url).
                        userAgent("Mozilla").
                        timeout(20000).  // timeout 20s
                        ignoreContentType(true).execute().body();

                // parse this JSON to see if there's more to be fetched
                JSONObject jsonObject = new JSONObject(json);
                hasMore = Boolean.parseBoolean(jsonObject.get("has_more").toString());

                jsonBuff.append(json).append("\n");
                Thread.sleep(delay);
            }
            catch (IOException ex) {
                System.err.println(ex);
                System.err.println("Unable to fetch JSON from url " + url);
                hasMore = false;
            }
        }
        return jsonBuff.toString();
    }


    /* Reads in a top level folder. Recurses into each JSON file. Extracts linked
     * questions for each batch of 100 questions and then writes out the linked
     * questions JSON file in a user specified o/p folder. */
    public static void main(String[] args) throws Exception {

        if (args.length < 1) {
            System.err.println("usage: java LinkedQuestionExtractor <top level folder>");
            return;
        }

        try {
            LinkedQuestionExtractor extractor = new LinkedQuestionExtractor(args[0]);
            extractor.processAll();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
