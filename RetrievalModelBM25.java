/**
 *  Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 */

/**
 *  An object that stores parameters for the unranked Boolean
 *  retrieval model (there are none) and indicates to the query
 *  operators how the query should be evaluated.
 */
public class RetrievalModelBM25 extends RetrievalModel {

  // BM25:k_1= Acceptable values are numbers >= 0.0.
  private double k1;
  // BM25:b= Acceptable values are between 0.0 and 1.0.
  private double b;
  //BM25:k_3= Acceptable values are numbers >= 0.0.
  private double k3;

  // getters
  public double getK1() {  return k1; }
  public double getB() {  return b; }
  public double getK3() {  return k3; }

  public RetrievalModelBM25(double k1, double b, double k3) {
    if(k1 >= 0.0 && b >= 0.0 && b <= 1.0 && k3 >= 0.0) {
      this.k1 = k1;
      this.b = b;
      this.k3 = k3;
    }
    else {
      throw new IllegalArgumentException("Bad parameters for BM25 model!");
    }
  }

  public String defaultQrySopName () {
    return new String ("#sum");
  }

}
