/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.index;

import java.io.Reader;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.payloads.DelimitedPayloadTokenFilter;
import org.apache.lucene.analysis.payloads.FloatEncoder;
import org.apache.lucene.analysis.payloads.PayloadEncoder;
import org.apache.lucene.analysis.standard.UAX29URLEmailTokenizer;
import org.apache.lucene.util.Version;

/**
 *
 * @author dganguly
 */
public class PayloadAnalyzer extends Analyzer {
    private PayloadEncoder encoder;
    
    public static char delim = '|';

    public PayloadAnalyzer() {
        this.encoder = new FloatEncoder();
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName, Reader reader) {
        Tokenizer source = new WhitespaceTokenizer(Version.LUCENE_4_9, reader);
        TokenStream filter = new DelimitedPayloadTokenFilter(source, delim, encoder);
        return new TokenStreamComponents(source, filter);
    }
}
