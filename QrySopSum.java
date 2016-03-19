/**
 *  Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 */

import java.io.*;

/**
 *  The OR operator for all retrieval models.
 */
public class QrySopSum extends QrySop {

  /**
   *  Indicates whether the query has a match.
   *  @param r The retrieval model that determines what is a match
   *  @return True if the query matches, otherwise false.
   */
  public boolean docIteratorHasMatch (RetrievalModel r) {
    return this.docIteratorHasMatchMin (r);
  }

  /**
   *  Get a score for the document that docIteratorHasMatch matched.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  public double getScore (RetrievalModel r) throws IOException {
    if (r instanceof RetrievalModelBM25) {
      return this.getScoreBM25(r);
    } else {
      throw new IllegalArgumentException
        (r.getClass().getName() + " doesn't support the OR operator.");
    }
  }

  /**
   *  getScore for the BM25 retrieval model.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  private double getScoreBM25 (RetrievalModel r) throws IOException {
    if (! this.docIteratorHasMatchCache()) {
      return 0.0;
    } else {
      return calculateBM25Score(r);
    }
  }

  /**
   *  calculate ranked score the RankedBoolean retrieval model.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  public double calculateBM25Score(RetrievalModel r) throws IOException {
    double sum = 0.0;
    if(this.args.size() == 0)
      return 0.0;
    for(int i = 0; i < this.args.size(); i++) {
      if(this.args.get(i).docIteratorHasMatchCache() &&
              this.args.get(i).docIteratorGetMatch() == this.docIteratorGetMatch()) {
        // must at least has an implied score
        sum += ((QrySop) this.args.get(i)).getScore(r);
      }
    }
    return sum;
  }

}
