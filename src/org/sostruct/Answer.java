/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.sostruct;

import java.util.LinkedList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.*;
import org.jsoup.select.*;
import org.jsoup.safety.*;
import org.jsoup.nodes.*;

/**
 *
 * @author dganguly
 */
public class Answer extends Post {
    List<Comment> comments;
    int userReputation;
    JSONObject jsonObject;
    boolean isAccepted;
    int score;

    public Answer(JSONObject answerObj) {
        this.jsonObject = answerObj;

        comments = new LinkedList<Comment>();
        try {
            JSONArray commentArray = answerObj.getJSONArray("comments");
            setComments(commentArray);
        }
        catch (JSONException ex) { }

        String bodyxml = answerObj.get("body").toString();
       	try {
			parseBody(bodyxml);
		}
		catch (Exception ex) { }

        this.score = Integer.parseInt(answerObj.get("score").toString());
        this.isAccepted = Boolean.parseBoolean(answerObj.get("is_accepted").toString());

        try {
            JSONObject ownerObj = answerObj.getJSONObject("owner");
            this.userReputation = Integer.parseInt(ownerObj.get("reputation").toString());
        }
        catch (JSONException ex) { }
    }

    void setComments(JSONArray commentArray) {
        Comment thisComment = null;

        for (int i = 0; i < commentArray.length(); i++) {
            JSONObject jsonCommentObj = commentArray.getJSONObject(i);
            thisComment = new Comment(jsonCommentObj);
            comments.add(thisComment);
        }
    }

    int totalCommentScore() {
        int sum = 0;
        for (Comment c: comments) {
            sum += c.score;
        }
        return sum;
    }

    public String toString() {
        return jsonObject.toString();
    }

    String getCode() throws Exception {
        return code;
    }	
}

