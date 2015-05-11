/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.sostruct;

/**
 *
 * @author dganguly
 */
import java.util.regex.*;

public class InvPattern {

    static String subtract(String a, String b) {
        StringBuffer buff = new StringBuffer();
        int start = 0, found = -1;

        while ((found = a.indexOf(b, start)) >= 0) {
            buff.append(a.substring(start, found-1));
            start = found + b.length()-1;
        }

        if (start > 0 && start+1 < a.length())
            buff.append(a.substring(start+1));

        return buff.toString();
    }

    static String[] removePattern(String text, String pattern) {
        String[] results = new String[2]; // res[0] := subtracted text, res[1] := matches
        results[0] = new String(text);
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(text);
        StringBuffer q = new StringBuffer();
        String thisMatch;

        while (m.find()) {
            thisMatch = m.group();
            q.append(thisMatch).append(" ");
            text = subtract(text, thisMatch);
        }

        results[0] = text;
        results[1] = q.toString();
        return results;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: javac PatternTest <text> <pattern>");
            return;
        }
        String[] res = removePattern(args[0], args[1]);
        System.out.println(res[0] + ": " + res[1]);
    }
}
