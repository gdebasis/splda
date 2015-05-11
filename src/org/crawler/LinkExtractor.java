/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.crawler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.sostruct.Question;

/**
 *
 * @author dganguly
 */
// This class is used to index a StackOverflow question...
public class LinkExtractor {

    File topDir;
    String oFileName;
    HashMap<Integer, Integer> crawledLinks;    
    FileWriter fout;
    int numRequests;
    
    static final int delay = 200;
	static final int delayAfterQuotaReq = 60000;

    LinkExtractor(String topDir, String inputMap, String oFileName) throws Exception {
        this.topDir = new File(topDir);
        this.oFileName = oFileName;
        numRequests = 0;
        
        // Use the existing links to know where to start crawling from...
        loadExistingLinks(inputMap);
    }
    
    private void loadExistingLinks(String imap) throws Exception {
        crawledLinks = new HashMap<>();
        
        FileReader fr = new FileReader(imap);
        BufferedReader br = new BufferedReader(fr);
        String line = null;
        
        while ((line = br.readLine()) != null) {
            String[] tokens = line.split("\\t");
            int questionId = Integer.parseInt(tokens[0]);
            crawledLinks.put(questionId, new Integer(questionId));
        }
        
        if (br != null) br.close();
        if (fr != null) fr.close();
    }

    void processAll() throws Exception {
        fout = new FileWriter(oFileName);
        processDirectory(topDir);
        fout.close();
    }

    private void processDirectory(File dir) throws Exception {
        File[] files = dir.listFiles();
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory() && !f.getName().equals("linked")) {
                System.out.println("Processing directory " + f.getName());
                processDirectory(f);  // recurse
            }
            else if (f.getName().endsWith(".json")) { // file type filter
                processFile(f);
            }
        }
    }

    void processFile(File jsonFile) throws Exception {

        System.out.println("Processing JSON file " + jsonFile.getName());

        FileReader fr = new FileReader(jsonFile);
        BufferedReader br = new BufferedReader(fr);
        String line;
        StringBuffer buff = new StringBuffer();

        while ((line = br.readLine()) != null) {
            buff.append(line + "\n");
        }
        
        JSONObject jsonObject = new JSONObject(buff.toString());
        JSONArray items = (JSONArray) jsonObject.get("items");
        JSONObject item;
        Question question = null;

        for (int i = 0; i < items.length(); i++) {
            question = null;

            try {
                item = items.getJSONObject(i);
                question = new Question(item);  // the current question
                int qid = question.getID();
                // Skip if the linked questions for this one
                // has already been crawled                
                if (crawledLinks.get(qid) != null) {
                    System.out.println("Skipping crawling of question: " + qid);
                    continue;
                }
                
                String links = extractLinkedIds(question);
                numRequests++;
                if (numRequests == 10000) {
                    System.out.println("Breaking after 10000 requests");
                }
                
                String linkMsg = qid + "\t" + links;
                fout.write(linkMsg + "\n");
                
                System.out.println(linkMsg);
                Thread.sleep(delay);
            }
            catch (Exception ex) {
                System.err.println(ex);
            }
        }

        fout.flush();
        
        br.close();
        fr.close();
    }

    // Returns comma separated linked question ids
    // Note: This function calls the StackExchange API to obtain the
    // linked question ids.
    public String extractLinkedIds(Question thisQuestion) throws Exception {
        StringBuffer links = new StringBuffer();
		final String key = "SljJ5BMNJM*UKmsOMwMx4w((";  //"R7HIsOi63KTX52j*7XK6mg((";
		final String userAgent = "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:24.0) Gecko/20100101 Firefox/24.0";
                
        String urlPrefix = "https://api.stackexchange.com/2.2/questions/";
        //String urlSuffix = "/linked?pagesize=100&site=stackoverflow&filter=!LURkzC_(wGQOeHiFvabvfS&key=SkjoA6hPLw50*v8PEShiUw((";
        String urlSuffix = "/linked?pagesize=100&site=stackoverflow&filter=!LURkzC_(wGQOeHiFvabvfS&key=" + key;

        String url = urlPrefix + String.valueOf(thisQuestion.getID()) + urlSuffix;

        String json = null;

        json = Jsoup.connect(url).
                userAgent(userAgent).
                timeout(20000).  // timeout 20s
                ignoreContentType(true).execute().body();

        // Now parse the JSON returned to obtain a list of ids
        if (json.length() == 0)
            return "";
        
        // parse this JSON to see if there's more to be fetched
        JSONObject jsonObject = new JSONObject(json);
		JSONArray items = (JSONArray) jsonObject.get("items");

		for (int i = 0; i < items.length(); i++) {
            try {
                JSONObject item = items.getJSONObject(i);
                links.append(item.get("question_id").toString()).append(",");
				String backoff = (String)item.get("backoff");
				if (backoff != null) {
					int backoffDelay = Integer.parseInt(backoff);
					Thread.sleep((backoffDelay + 1) * 1000);
				}
				String quotaLeft = (String)item.get("quota_remaining");
                int quotaRemVal = -1;
				if (quotaLeft != null) {
                    try {
                        quotaRemVal = Integer.parseInt(quotaLeft);
                    }
                    catch (NumberFormatException nex) { }                    
				}
                System.out.println("#requests left: " + quotaRemVal + ", #requests sent: " + numRequests);
                if (quotaRemVal <= 1)
                    break;
            }
            catch (JSONException jex) {
                System.err.println("JSON parse exception" + jex);
            }
        }
        
        if (links.length() > 0)
            links.deleteCharAt(links.length()-1); // remove trailing comma
        return links.toString();
    }
    
    /* Reads in a top level folder. Recurses into each JSON file. Extracts linked
     * questions for each batch of 100 questions and then writes out the linked
     * questions JSON file in a user specified o/p folder. */
    public static void main(String[] args) throws Exception {

        if (args.length < 3) {
            System.err.println("usage: java LinkExtractor <top level folder> <i/p map file> <o/p mapping file>");
            return;
        }

        try {
            LinkExtractor extractor = new LinkExtractor(args[0], args[1], args[2]);
            extractor.processAll();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
