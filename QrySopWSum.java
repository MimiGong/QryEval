/**
 * Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 */

import java.io.*;
import java.util.ArrayList;

/**
 *  The OR operator for all retrieval models.
 */
public class QrySopWSum extends QrySop {

    private ArrayList<Double> weights = new ArrayList<>();

    private double sumWeight = 0;

    public void addWeight(String token) {
        Double weight = Double.parseDouble(token);
        weights.add(weight);
        sumWeight += weight;
    }

    public void popWeight() {
        double weight = weights.get(weights.size() - 1);
        weights.remove(weights.size() - 1);
        sumWeight -= weight;
    }

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
        if (r instanceof RetrievalModelIndri) {
            return this.getScoreIndri(r);
        } else {
            throw new IllegalArgumentException
                    (r.getClass().getName() + " doesn't support the WSUM operator.");
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
    public double calculateIndriScore(RetrievalModel r) throws IOException {
        double average = 0.0;
        if(this.args.size() == 0)
            return 0.0;
        for (int i = 0; i < this.args.size(); i++) {
            // must at least has an implied score
            double weight = this.weights.get(i);
            if(this.args.get(i).docIteratorHasMatchCache() &&
                    this.args.get(i).docIteratorGetMatch() == this.docIteratorGetMatch()) {
                average += weight/sumWeight * ((QrySop) this.args.get(i)).getScore(r);
            }
            else {
                average += weight/sumWeight *
                        ((QrySop) this.args.get(i)).getDefaultScore(r, this.docIteratorGetMatch());
            }
        }
        return average;
    }

    public double getDefaultScore(RetrievalModel r, int docId) throws IOException {
        if (r instanceof RetrievalModelIndri){
            double average = 0.0;
            for (int i = 0; i < this.args.size(); i++) {
                double weight = this.weights.get(i);
                if(this.args.get(i).docIteratorHasMatchCache() &&
                        this.args.get(i).docIteratorGetMatch() == docId) {
                    average += weight/sumWeight * ((QrySop) this.args.get(i)).getScore(r);
                }
                else {
                    average += weight/sumWeight *
                            ((QrySop) this.args.get(i)).getDefaultScore(r, docId);
                }
            }
            return average;
        } else {
            throw new IllegalArgumentException
                    (r.getClass().getName() + " doesn't support the SCORE operator.");
        }
    }
}
