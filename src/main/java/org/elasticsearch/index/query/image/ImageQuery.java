package org.elasticsearch.index.query.image;

import net.semanticmetadata.lire.imageanalysis.LireFeature;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Copied from {@link MatchAllDocsQuery}, calculate score for all docs
 */
public class ImageQuery extends Query {

    private float boost;
    private String luceneFieldName;
    private LireFeature lireFeature;

    public ImageQuery(String luceneFieldName, LireFeature lireFeature, float boost) {
        this.luceneFieldName = luceneFieldName;
        this.lireFeature = lireFeature;
        this.boost = boost;
    }

    private class ImageScorer extends AbstractImageScorer {

        private final TwoPhaseIterator twoPhaseIterator;
        private final DocIdSetIterator disi;

        public ImageScorer(IndexReader reader, Weight w, DocIdSetIterator disi) {
            super(w, luceneFieldName, lireFeature, reader, boost);
            this.twoPhaseIterator = null;
            this.disi = disi;
        }

        @Override
        public DocIdSetIterator iterator() {
            return disi;
        }

        @Override
        public TwoPhaseIterator twoPhaseIterator() {
            return twoPhaseIterator;
        }

        @Override
        public int docID() {
            return disi.docID();
        }
    }

    private class ImageWeight extends Weight {

        protected ImageWeight(Query query) {
            super(query);
        }

        @Override
        public String toString() {
            return "weight(" + ImageQuery.this + ")";
        }

        @Override
        public float getValueForNormalization() {
            return 1.0f;
        }

        @Override
        public void normalize(float queryNorm, float topLevelBoost) {
        }

        @Override
        public ImageScorer scorer(LeafReaderContext context) throws IOException {
            return new ImageScorer(context.reader(), this, DocIdSetIterator.all(context.reader().maxDoc()));
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            final Scorer s = scorer(context);
            final boolean exists;
            if (s == null) {
                exists = false;
            } else {
                final TwoPhaseIterator twoPhase = s.twoPhaseIterator();
                if (twoPhase == null) {
                    exists = s.iterator().advance(doc) == doc;
                } else {
                    exists = twoPhase.approximation().advance(doc) == doc && twoPhase.matches();
                }
            }

            if (exists) {
                float score = s.score();
                List<Explanation> details = new ArrayList<>();
                if (boost != 1.0f) {
                    details.add(Explanation.match(boost, "boost"));
                    score = score / boost;
                }
                details.add(Explanation.match(score, "image score (1/distance)"));
                return Explanation.match(score, ImageQuery.this.toString() + ", product of:", details);
            } else {
                return Explanation.noMatch(ImageQuery.this.toString() + " doesn't match id " + doc);
            }
        }

        @Override
        public void extractTerms(Set<Term> terms) {

        }
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, boolean needsScores) {
        return new ImageWeight(this);
    }

    @Override
    public String toString(String field) {
        return luceneFieldName +
                "," +
                lireFeature.getClass().getSimpleName() +
                (boost != 1.0f ? "^" + Float.toString(boost) : "");
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ImageQuery)) {
            return false;
        }
        ImageQuery other = (ImageQuery) o;
        return (this.boost == other.boost)
                && luceneFieldName.equals(other.luceneFieldName)
                && lireFeature.equals(other.lireFeature);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + luceneFieldName.hashCode();
        result = 31 * result + lireFeature.hashCode();
        result = Float.floatToIntBits(boost) ^ result;
        return result;
    }
}
