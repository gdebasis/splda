/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.sostruct;

import org.json.JSONObject;

/**
 *
 * @author dganguly
 */
public class Comment {
    String content;
    int userReputation;
    int score;
    JSONObject jsonObject;

    Comment(JSONObject jsonCommentObj) {
        this.jsonObject = jsonCommentObj;

        this.content = jsonCommentObj.get("body").toString();
        this.score = Integer.parseInt(jsonCommentObj.get("score").toString());
        JSONObject ownerObj = jsonCommentObj.getJSONObject("owner");
        this.userReputation = Integer.parseInt(ownerObj.get("reputation").toString());
    }

    public String toString() {
        return jsonObject.toString();
    }	
}
