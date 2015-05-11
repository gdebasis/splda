/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.index;

/**
 *
 * @author dganguly
 */
import java.io.IOException;
import org.apache.lucene.analysis.payloads.PayloadHelper;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.payloads.*;
import org.apache.lucene.search.similarities.*;
import org.apache.lucene.search.similarities.LMSimilarity.CollectionModel;
import org.apache.lucene.search.similarities.Similarity.SimScorer;
import org.apache.lucene.search.spans.*;
import org.apache.lucene.util.*;

/**
 * Generalized Language model
 * To be used with LMLinearCombinationTermQuery.
 * Defers the linear combination for later. Simply returns the tf/doclen
 * The collection stats are to be used later on.
 * @lucene.experimental
 */
class GeneralizedLMSimilarity extends LMJelinekMercerSimilarity {
    boolean usePayload;
    
    public GeneralizedLMSimilarity(boolean usePayload) {       
        super(0.3f /*unused*/);
        this.usePayload = usePayload;
    }

	public CollectionModel getCollectionModel() { return collectionModel; }

    @Override
    protected float score(BasicStats stats, float freq, float docLen) {
      return (float)(stats.getTotalBoost() * freq / docLen);
    }
    
    @Override
    protected float scorePayload(int doc, int start, int end, BytesRef payload) {
        if (payload == null || !usePayload) return 1.0f;
        float wt = PayloadHelper.decodeFloat(payload.bytes, payload.offset);
        return wt;
    }    
}

/**
 * This class is very similar to
 * {@link org.apache.lucene.search.spans.SpanTermQuery} except that it factors
 * in the value of the payload located at each of the positions where the
 * {@link org.apache.lucene.index.Term} occurs.
 * <p/>
 * NOTE: In order to take advantage of this with the default scoring implementation
 * ({@link DefaultSimilarity}), you must override {@link DefaultSimilarity#scorePayload(int, int, int, BytesRef)},
 * which returns 1 by default.
 * <p/>
 * Payload scores are aggregated using a pluggable {@link PayloadFunction}.
 * @see org.apache.lucene.search.similarities.Similarity.SimScorer#computePayloadFactor(int, int, int, BytesRef)
 **/
public class LMLinearCombinationTermQuery extends PayloadTermQuery {
    float lambda;
    float tmLambda;  // lambda for the payload

    public LMLinearCombinationTermQuery(Term term, PayloadFunction function, float lambda, float tmLambda) {
        super(term, function, true);
        this.lambda = lambda;
        this.tmLambda = tmLambda;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher) throws IOException {
        return new LMLinearCombinationTermWeight(this, searcher);
    }

    protected class LMLinearCombinationTermWeight extends PayloadTermQuery.PayloadTermWeight {

    public LMLinearCombinationTermWeight(LMLinearCombinationTermQuery query, IndexSearcher searcher)
        throws IOException {
        super(query, searcher);
    }

    @Override
    public Scorer scorer(AtomicReaderContext context, Bits acceptDocs) throws IOException {
        return new LMLinearCombinationTermSpanScorer((TermSpans) query.getSpans(context, acceptDocs, termContexts),
            this, similarity.simScorer(stats, context));
    }

    protected class LMLinearCombinationTermSpanScorer extends PayloadTermSpanScorer {

        public LMLinearCombinationTermSpanScorer(TermSpans spans, Weight weight, Similarity.SimScorer docScorer) throws IOException {
            super(spans, weight, docScorer);
        }

        /**
         * The main tweak goes here, where instead of multiplying
         * the payload score, we add a linear combination of it and then
         * take log (1 + score_lm).
         * Remember that the scorer in this case needs to be changed to
         * a new class which doesn't return the log as in LMJelineckMercerSmoothing.
         * The log of the linear combination has to be taken here...
         * @return {@link #getSpanScore()} * {@link #getPayloadScore()}
         * @throws IOException if there is a low-level I/O error
         */
        @Override
        public float score() throws IOException {
            GeneralizedLMSimilarity lmSim = (GeneralizedLMSimilarity)similarity; // this sim has to be an LMSimilarity
            CollectionModel collectionModel = lmSim.getCollectionModel();

            // this score is tf(t)/doclen * cs/cf(t)
            float denom = (1 - lambda - tmLambda);
            float reciprocalCollectionProb = 1/collectionModel.computeProbability((BasicStats)stats);
            float lmScore = lambda * getSpanScore();
            float tmScore = tmLambda * getPayloadScore();

            return (float)(Math.log(1 + reciprocalCollectionProb/denom * (lmScore + tmScore)));
            }
        }
    }
}