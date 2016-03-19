/**
 *  Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 */

import java.io.*;
import java.lang.IllegalArgumentException;
import java.rmi.UnexpectedException;

/**
 *  The SCORE operator for all retrieval models.
 */
public class QrySopScore extends QrySop {

  /**
   *  Document-independent values that should be determined just once.
   *  Some retrieval models have these, some don't.
   */
  
  /**
   *  Indicates whether the query has a match.
   *  @param r The retrieval model that determines what is a match
   *  @return True if the query matches, otherwise false.
   */
  public boolean docIteratorHasMatch (RetrievalModel r) {
    return this.docIteratorHasMatchFirst (r);
  }

  /**
   *  Get a score for the document that docIteratorHasMatch matched.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  public double getScore (RetrievalModel r) throws IOException {
    if (r instanceof RetrievalModelUnrankedBoolean) {
      return this.getScoreUnrankedBoolean ((RetrievalModelUnrankedBoolean)r);
    } else if (r instanceof RetrievalModelRankedBoolean){
      return this.getScoreRankedBoolean ((RetrievalModelRankedBoolean)r);
    } else if (r instanceof RetrievalModelBM25){
      return this.getScoreBM25 ((RetrievalModelBM25)r);
    } else if (r instanceof RetrievalModelIndri){
      return this.getScoreIndri ((RetrievalModelIndri)r);
    } else {
      throw new IllegalArgumentException
        (r.getClass().getName() + " doesn't support the SCORE operator.");
    }
  }

  public double getDefaultScore(RetrievalModel r, int docId) throws IOException {
    if (r instanceof RetrievalModelIndri){
      return this.calculateIndriScoreByTf((RetrievalModelIndri)r, 0, docId);
    } else {
      throw new IllegalArgumentException
              (r.getClass().getName() + " doesn't support the SCORE operator.");
    }
  }

  /**
   *  Initialize the query operator (and its arguments), including any
   *  internal iterators.  If the query operator is of type QryIop, it
   *  is fully evaluated, and the results are stored in an internal
   *  inverted list that may be accessed via the internal iterator.
   *  @param r A retrieval model that guides initialization
   *  @throws IOException Error accessing the Lucene index.
   */
  public void initialize (RetrievalModel r) throws IOException {
    Qry q = this.args.get (0);
    q.initialize (r);
  }

  /**
   *  getScore for the Unranked retrieval model.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  private double getScoreUnrankedBoolean (RetrievalModelUnrankedBoolean r)
          throws IOException {
    if (! this.docIteratorHasMatchCache()) {
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
  private double getScoreRankedBoolean (RetrievalModelRankedBoolean r)
          throws IOException {
    if (! this.docIteratorHasMatchCache()) {
      return 0.0;
    } else {
      return calculateRankedScore(r);
    }
  }

  /**
   *  getScore for the BM25 retrieval model.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  private double getScoreBM25 (RetrievalModelBM25 r) throws IOException {
    if (! this.docIteratorHasMatchCache()) {
      return 0.0;
    } else {
      return calculateBM25Score(r);
    }
  }

  /**
   *  getScore for the Indri retrieval model.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  private double getScoreIndri (RetrievalModelIndri r) throws IOException {
    if (! this.docIteratorHasMatchCache()) {
      // should not reach here
      return 0.0;
    } else {
      return calculateIndriScore(r);
    }
  }

  /**
   *  calculate ranked score for the RankedBoolean retrieval model.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  private double calculateRankedScore(RetrievalModelRankedBoolean r)
          throws IOException {
    // score op has only one arg
    if(this.args.size() == 1) {
      return this.getArg(0).docIteratorGetMatchPosting().tf;
    }
    else {
      throw new IllegalArgumentException("The number of arguments is incorrect");
    }
  }

  /**
   *  calculate score for the BM25 retrieval model.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  private double calculateBM25Score(RetrievalModelBM25 r) throws IOException {
    // score op has only one arg
    if(this.args.size() == 1) {
      // compute BM25 score according to formula
      double k1 = r.getK1(), k3 = r.getK3();
      double b = r.getB();
      long N = Idx.getNumDocs();
      long tf = this.getArg(0).docIteratorGetMatchPosting().tf;
      long df = this.getArg(0).getDf();
      String field = this.getArg(0).getField();
      int docId = this.docIteratorGetMatch();
      int qtf = 1;
      double docLen = Idx.getFieldLength(field, docId);
      double avgDocLen = (double)Idx.getSumOfFieldLengths(field) / (double)Idx.getDocCount(field);
      double floorRSJWeight = Math.max(0, Math.log(((double)N-(double)df+0.5)/((double)df+0.5)));
      double tfWeight = (double)tf / ((double)tf + k1 * ((1-b)+b*docLen/avgDocLen));
      double userWeight = (k3 + 1) * (double)qtf/ (k3 + (double)qtf);
      return floorRSJWeight * tfWeight * userWeight;
    }
    else {
      throw new IllegalArgumentException("The number of arguments is incorrect");
    }
  }

  /**
   *  calculate score for the Indri retrieval model.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  private double calculateIndriScore(RetrievalModelIndri r) throws IOException {
    // score op has only one arg
    if(this.args.size() == 1) {
      long tf = this.getArg(0).docIteratorGetMatchPosting().tf;
      int docId = this.docIteratorGetMatch();
      return this.calculateIndriScoreByTf(r, tf, docId);
    }
    else {
      throw new IllegalArgumentException("The number of arguments is incorrect");
    }
  }

  private double calculateIndriScoreByTf(RetrievalModelIndri r, long tf, int docId)
          throws IOException {
    String field = this.getArg(0).getField();
    long ctf = this.getArg(0).getCtf();
    int docLen = Idx.getFieldLength(field, docId);
    long sumDocLen = Idx.getSumOfFieldLengths(field);
    double qiUnderC = (double)ctf / (double)sumDocLen;
    // acquire parameters
    double mu = r.getMu();
    double lambda = r.getLambda();
    // smoothing
    return (1- lambda) * (((double)tf + mu * qiUnderC)/((double)docLen + mu))
            + lambda * qiUnderC;
  }

}
