package org.sostruct;

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
import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.select.*;
import org.jsoup.safety.*;
import org.apache.commons.lang.StringEscapeUtils;

class Post {
	String code;
	String body;

	// Segregate into code and text parts
	void parseBody(String xml) throws Exception {

		org.jsoup.nodes.Document doc = Jsoup.parse(xml);
		Elements codes = doc.select("code");
		StringBuffer codeBuff = new StringBuffer();

		for (Element code: codes) {
			codeBuff.append(code.text()).append(" ");
		}
		if (codeBuff.length() > 0)
			codeBuff.deleteCharAt(codeBuff.length()-1);

		this.code = codeBuff.toString();

		codes.remove();
		// if not removed, the cleaner will drop the <div> but leave the inner text
		this.body = Jsoup.clean(doc.body().text(), Whitelist.basic());
		this.body = StringEscapeUtils.unescapeHtml(this.body);
        this.body = preProcessPuncts(this.body);
	}
    
    /* Fix inappropriate punctuations.
       1) Insert space before start of a new sentence if not already present.
          Simple check: is a lower case letter followed by a punct followed
                        by an uppercase letter?
                        if yes: insert a space!
    */
    String preProcessPuncts(String text) {
        StringBuffer otext = new StringBuffer();

        Pattern p = Pattern.compile("([a-z][.])([A-Z])");
        Matcher m = p.matcher(text);
        while (m.find()) {
            m.appendReplacement(otext, m.group(1) + " " + m.group(2));
        }
        m.appendTail(otext);
        return otext.toString();
    }
}

public class Question extends Post {
    int id;
    String title;
    String creationDate;
    int askerReputation;
    List<String> tags;
    List<Answer> answers;
    List<Comment> comments;
    int score;

    JSONObject jsonObject;

    public Question(JSONObject questionObj) {
        this.jsonObject = questionObj;

        tags = new LinkedList<String>();
        answers = new LinkedList<Answer>();
        comments = new LinkedList<Comment>();

        this.id = Integer.parseInt(questionObj.get("question_id").toString());
        this.title = questionObj.get("title").toString();

        String bodyxml = questionObj.get("body").toString();
		try {
			parseBody(bodyxml);
		}
		catch (Exception ex) { ex.printStackTrace(); }
        
        this.creationDate = questionObj.get("creation_date").toString();

        try {
            JSONObject ownerObj = questionObj.getJSONObject("owner");
            askerReputation = Integer.parseInt(ownerObj.get("reputation").toString());

            setTags(questionObj.getJSONArray("tags"));

            setAnswers(questionObj);

            JSONArray commentArray = questionObj.getJSONArray("comments");
            setComments(commentArray);
            score = Integer.parseInt(questionObj.get("score").toString());
        }
        catch (JSONException ex) { }
    }

    public int getID() { return id; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    
    public String getTags() {
        StringBuffer buff = new StringBuffer();
        for (String tag: tags) {
            buff.append(tag).append(",");
        }
        if (buff.length() > 0)
            buff.deleteCharAt(buff.length()-1);
        return buff.toString();
    }
    
    int getScore() { return score; }

    public String getCode() throws Exception {
        return code;
    }

    public String getCodeFromAnswers() throws Exception {
        StringBuffer buff = new StringBuffer();
        for (Answer a : answers) {
            buff.append(a.getCode()).append(" ");
        }
        return buff.toString();
    }

    void setAnswers(JSONObject questionObj) throws JSONException {
        JSONArray answers = questionObj.getJSONArray("answers");
        Answer thisAns;
        for (int i = 0; i < answers.length(); i++) {
            JSONObject answerObj = answers.getJSONObject(i);
            thisAns = new Answer(answerObj);
            this.answers.add(thisAns);
        }
    }

    void setComments(JSONArray commentArray) {
        Comment thisComment = null;

        for (int i = 0; i < commentArray.length(); i++) {
            JSONObject jsonCommentObj = commentArray.getJSONObject(i);
            thisComment = new Comment(jsonCommentObj);
            comments.add(thisComment);
        }
    }

    void setTags(JSONArray tagStrings) {
        for (int i = 0; i < tagStrings.length(); i++) {
            tags.add(tagStrings.get(i).toString());
        }
    }

    float totalAnswererRep() {
        int sum = 0;
        for (Answer a : answers) {
            sum += a.userReputation;
        }
        return answers.size() == 0? 0 : sum/(float)answers.size();
    }


    /* Get a concatenated string of answer bodies */ 
    public String getConcatAnswers() {
        StringBuffer buff = new StringBuffer();
        for (Answer a : answers) {
            buff.append(a.body).append(" ");
        }
        return buff.toString();
    }

    /* Get a concatenated string of comment bodies */ 
    public String getConcatComments() {
        StringBuffer buff = new StringBuffer();
        for (Comment c : comments) {
            buff.append(c.content).append(" ");
        }
        return buff.toString();
    }
    
    public static String removeTags(String content) {
        String strippedBody = content.replaceAll("\\<.*?>"," ");
        return strippedBody;
    }
    
    public String toString() {
        return jsonObject.toString();
    }	
    
}


