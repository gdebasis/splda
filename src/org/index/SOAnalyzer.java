/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.index;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.en.PorterStemFilter;
import org.apache.lucene.analysis.standard.StandardFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.standard.UAX29URLEmailTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.util.FilteringTokenFilter;
import org.apache.lucene.util.Version;

/**
 *
 * @author dganguly
 */
class NumericTokenFilter extends FilteringTokenFilter {
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

    public NumericTokenFilter(Version version, boolean enablePositionIncrements, TokenStream input) {
        super(version, enablePositionIncrements, input);
    }
    
    @Override
    protected boolean accept() throws IOException {
        String term = termAtt.toString();
        int len = term.length();
        for (int i = 0; i < len; i++) {
            char ch = term.charAt(i);
            if (Character.isDigit(ch))
                return false;
        }
        return true;
    }
}

public class SOAnalyzer extends Analyzer {
	boolean stem;
	List<String> stopwords;

    public SOAnalyzer(Properties prop) {
		boolean stem = Boolean.parseBoolean(prop.getProperty("stem", "true"));
		String stopFile = prop.getProperty("stopfile", "common_words");
        
		this.stem = stem;
		stopwords = new ArrayList<>();
		String line;

		try {
			FileReader fr = new FileReader(stopFile);
		   	BufferedReader br = new BufferedReader(fr);
			while ( (line = br.readLine()) != null ) {
				stopwords.add(line.trim());
			}
			br.close(); fr.close();
		}
		catch (Exception ex) {
			ex.printStackTrace();
		}        
    }
    
	public SOAnalyzer(boolean stem, String stopFile) {
		this.stem = stem;
		stopwords = new ArrayList<>();
		String line;

		try {
			FileReader fr = new FileReader(stopFile);
		   	BufferedReader br = new BufferedReader(fr);
			while ( (line = br.readLine()) != null ) {
				stopwords.add(line.trim());
			}
			br.close(); fr.close();
		}
		catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	protected Analyzer.TokenStreamComponents createComponents(String fieldName, Reader reader) {
		String token;
		TokenStream result = null;

        Version luceneVersion = Version.LUCENE_4_9;
        
		Tokenizer source = new StandardTokenizer(luceneVersion, reader);
		result = new LowerCaseFilter(luceneVersion, source);
		result = new StandardFilter(luceneVersion, result);
		result = new StopFilter(luceneVersion, result,
						StopFilter.makeStopSet(luceneVersion, stopwords));
		if (stem)
			result = new PorterStemFilter(result);
        result = new NumericTokenFilter(luceneVersion, true, result);

		return new Analyzer.TokenStreamComponents(source, result);
	}
}

