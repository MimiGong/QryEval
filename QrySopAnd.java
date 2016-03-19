/**
 * Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 */

import java.io.*;

/**
 *  The OR operator for all retrieval models.
 */
public class QrySopAnd extends QrySop {

    /**
     *  Indicates whether the query has a match.
     *  @param r The retrieval model that determines what is a match
     *  @return True if the query matches, otherwise false.
     */
    public boolean docIteratorHasMatch(RetrievalModel r) {
        if(r instanceof  RetrievalModelIndri)
            return this.docIteratorHasMatchMin(r);
        else
            return this.docIteratorHasMatchAll(r);
    }

    /**
     *  Get a score for the document that docIteratorHasMatch matched.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    public double getScore(RetrievalModel r) throws IOException {
        if (r instanceof RetrievalModelUnrankedBoolean) {
            return this.getScoreUnrankedBoolean(r);
        } else if (r instanceof RetrievalModelRankedBoolean) {
            return this.getScoreRankedBoolean(r);
        } else if (r instanceof RetrievalModelIndri) {
            return this.getScoreIndri(r);
        } else {
            throw new IllegalArgumentException
                    (r.getClass().getName() + " doesn't support the OR operator.");
        }
    }

    /**
     *  getScore for the UnrankedBoolean retrieval model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    private double getScoreUnrankedBoolean(RetrievalModel r) throws IOException {
        if (!this.docIteratorHasMatchCache()) {
            return 0.0;
        } else {
            return 1.0;
        }
    }

    /**
     *  getScore for the RankedBoolean retrieval model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    private double getScoreRankedBoolean(RetrievalModel r) throws IOException {
        if (!this.docIteratorHasMatchCache()) {
            return 0.0;
        } else {
            return calculateRankedScore(r);
        }
    }

    /**
     *  getScore for the RankedBoolean retrieval model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    private double getScoreIndri(RetrievalModel r) throws IOException {
        if (!this.docIteratorHasMatch(r)) {
            return 0.0;
        } else {
            return calculateIndriScore(r);
        }
    }

    /**
     *  calculate ranked score the RankedBoolean retrieval model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    public double calculateRankedScore(RetrievalModel r) throws IOException {
        double min = Double.MAX_VALUE;
        if(this.args.size() == 0)
            return 0.0;
        for (int i = 0; i < this.args.size(); i++) {
            // must at least has an implied score
            if(this.args.get(i).docIteratorHasMatchCache() &&
                    this.args.get(i).docIteratorGetMatch() == this.docIteratorGetMatch()) {
                double tmp = ((QrySop) this.args.get(i)).getScore(r);
                min = Math.min(tmp, min);
            }
            else
                return 0.0;
        }
        return min;
    }

    /**
     *  calculate ranked score the RankedBoolean retrieval model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    public double calculateIndriScore(RetrievalModel r) throws IOException {
        double product = 1.0;
        if(this.args.size() == 0)
            return 0.0;
        for (int i = 0; i < this.args.size(); i++) {
            // must at least has an implied score
            if(this.args.get(i).docIteratorHasMatchCache() &&
                    this.args.get(i).docIteratorGetMatch() == this.docIteratorGetMatch()) {
                product *= Math.pow(((QrySop) this.args.get(i)).getScore(r), 1.0/(double)this.args.size());
            }
            else {
                product *= Math.pow(
                        ((QrySop) this.args.get(i)).getDefaultScore(r, this.docIteratorGetMatch()),
                        1.0 / (double) this.args.size());
            }
        }
        return product;
    }

    public double getDefaultScore(RetrievalModel r, int docId) throws IOException {
        if (r instanceof RetrievalModelIndri){
            double product = 1.0;
            for (int i = 0; i < this.args.size(); i++) {
                if(this.args.get(i).docIteratorHasMatchCache() &&
                        this.args.get(i).docIteratorGetMatch() == docId) {
                    product *= Math.pow(((QrySop) this.args.get(i)).getScore(r), 1.0/(double)this.args.size());
                }
                else {
                    product *= Math.pow(
                            ((QrySop) this.args.get(i)).getDefaultScore(r, docId),
                            1.0 / (double) this.args.size());
                }
            }
            return product;
        } else {
            throw new IllegalArgumentException
                    (r.getClass().getName() + " doesn't support the SCORE operator.");
        }
    }
}
