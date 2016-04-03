/**
 *  Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 */

/**
 *  An object that stores parameters for the unranked Boolean
 *  retrieval model (there are none) and indicates to the query
 *  operators how the query should be evaluated.
 */
public class RetrievalModelLetor extends RetrievalModel {

  RetrievalModelBM25 BM25Model;
  RetrievalModelIndri indriModel;

  public RetrievalModelBM25 getBM25Model() {
    return BM25Model;
  }

  public RetrievalModelIndri getIndriModel() {
    return indriModel;
  }

  public RetrievalModelLetor(RetrievalModelBM25 BM25Model,
                             RetrievalModelIndri indriModel) {
    this.BM25Model = BM25Model;
    this.indriModel = indriModel;
  }

  public String defaultQrySopName () {
    return new String ("#sum");
  }

}
