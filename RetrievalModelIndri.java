/**
 *  Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 */

/**
 *  An object that stores parameters for the unranked Boolean
 *  retrieval model (there are none) and indicates to the query
 *  operators how the query should be evaluated.
 */
public class RetrievalModelIndri extends RetrievalModel {

  // Indri:mu= Acceptable values are integers >= 0.
  private double mu;
  // Indri:lambda= Acceptable values are between 0.0 and 1.0.
  private double lambda;

  public double getMu() {  return mu; }
  public double getLambda() {  return lambda; }

  public RetrievalModelIndri(double mu, double lambda) {
    if(mu >= 0.0 && lambda >= 0.0 && lambda <= 1.0) {
      this.mu = mu;
      this.lambda = lambda;
    }
    else {
      throw new IllegalArgumentException("Bad parameters for Indri model!");
    }
  }

  public String defaultQrySopName () {
    return new String ("#and");
  }

}
