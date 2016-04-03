import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * create learn to rank features
 */
public class FeatureExtractor {

    int iterator = 0;

    ArrayList<Integer> qidList = new ArrayList<>();

    ArrayList<DocFeatures> docFeatureList = new ArrayList<>();

    public void setScore(int index, double score) {
        if (index >= docFeatureList.size()) {
            System.out.println("set score out of bound!");
            return;
        }
        docFeatureList.get(index).predictRelevance = score;
    }

    /* must call by qid in ascending order */
    public void getSortedDocsByQid(int qid, ArrayList<String> externalIds,
                                   ArrayList<Double> scores) {
        ArrayList<DocFeatures> docs = new ArrayList<>();
        while(iterator < docFeatureList.size() && docFeatureList.get(iterator).qid == qid) {
            docs.add(docFeatureList.get(iterator));
            iterator++;
        }
        Collections.sort(docs, (d1, d2) ->
                ((Double)d2.predictRelevance).compareTo((Double)d1.predictRelevance));
        for (DocFeatures doc : docs) {
            externalIds.add(doc.externalId);
            scores.add(doc.predictRelevance);
        }
    }

    /**
     *
     * @param testDocs external Id list
     * @param queryTerms stemmed query
     * @param bm25Model BM25 model
     * @param indriModel Indri model
     * @param qid query id
     * @param pagerankScoreMap pagerank map
     * @param relevanceList relevance score in the same order
     *                      as external ids for training or null for test
     * @throws IOException
     */
    public void extract(ArrayList<String> testDocs,
                        String[] queryTerms,
                        RetrievalModelBM25 bm25Model,
                        RetrievalModelIndri indriModel,
                        int qid,
                        PageRankScoreMap pagerankScoreMap,
                        ArrayList<Integer> relevanceList) throws IOException {
        double [] scores = new double[18];
        qidList.add(qid);
        for (int i = 0; i < testDocs.size(); i++) {
            String externalId = testDocs.get(i);
            int docId;
            try {
                docId = Idx.getInternalDocid(externalId);
            } catch (Exception e) {
                e.printStackTrace();
                continue;
            }
            // f1: Spam score for d (read from index)
            int spamScore = Integer.parseInt(Idx.getAttribute("score", docId));
            scores[0] = spamScore;
            // f2: Url depth for d(number of '/' in the rawUrl field).
            String rawUrl = Idx.getAttribute("rawUrl", docId);
            int urlDepth = countMatches(rawUrl, '/');
            scores[1] = (double) urlDepth;
            // f3: FromWikipedia score for d (1 if the rawUrl contains "wikipedia.org", otherwise 0).
            int fromWikipedia = rawUrl.toLowerCase().contains("wikipedia.org") ? 1 : 0;
            scores[2] = (double) fromWikipedia;
            // f4: PageRank score for d (read from file).
            double pagerankScore = pagerankScoreMap.get(externalId);
            scores[3] = pagerankScore;
            // f5: BM25 score for <q, dbody>.
            // f6: Indri score for <q, dbody>.
            // f7: Term overlap score for <q, dbody>.
            getScoreFeatures(queryTerms, docId, "body", bm25Model, indriModel, scores, 4);
            // f8: BM25 score for <q, dtitle>.
            // f9: Indri score for <q, dtitle>.
            // f10: Term overlap score for <q, dtitle>.
            getScoreFeatures(queryTerms, docId, "title", bm25Model, indriModel, scores, 7);
            // f11: BM25 score for <q, durl>.
            // f12: Indri score for <q, durl>.
            // f13: Term overlap score for <q, durl>.
            getScoreFeatures(queryTerms, docId, "url", bm25Model, indriModel, scores, 10);
            // f14: BM25 score for <q, dinlink>.
            // f15: Indri score for <q, dinlink>.
            // f16: Term overlap score for <q, dinlink>.
            getScoreFeatures(queryTerms, docId, "inlink", bm25Model, indriModel, scores, 13);
            // f17 f18 imagination
            scores[16] = 0.0;
            scores[17] = 0.0;
            /* relevance is 0 for test docs */
            if (relevanceList != null)
                docFeatureList.add(new DocFeatures(relevanceList.get(i), qid, externalId, scores));
            else
                docFeatureList.add(new DocFeatures(0, qid, externalId, scores));
        }
    }

    public void normalize() {
        if (docFeatureList.size() == 0)
            return;
        double[] minScores = docFeatureList.get(0).scores.clone();
        double[] maxScores = docFeatureList.get(0).scores.clone();
        /* get min and max */
        for (DocFeatures doc : docFeatureList) {
            for (int i = 0; i <  18; i++) {
                minScores[i] = Math.min(minScores[i], doc.scores[i]);
                maxScores[i] = Math.min(maxScores[i], doc.scores[i]);
            }
        }
        double[] ranges = new double[18];
        for (int i = 0; i <  18; i++) {
            ranges[i] = maxScores[i] - minScores[i];
        }
        /* normalize all scores */
        for (DocFeatures doc : docFeatureList) {
            for (int i = 0; i <  18; i++) {
                doc.scores[i] = ranges[i] == 0. ? 0. :
                        (doc.scores[i] - minScores[i]) / ranges[i];
            }
        }
    }

    public void printToFile(String filename) {
        /* first normalize the scores */
        normalize();
        /* construct string */
        StringBuilder builder = new StringBuilder();
        for (DocFeatures docFeatures: docFeatureList) {
            builder.append(docFeatures.toString());
            builder.append("\n");
        }
        // write to file
        try (FileWriter fw = new FileWriter(filename, false)) {
            fw.write(builder.toString());
        } catch (IOException ioe) {
            System.err.println("IOException: " + ioe.getMessage());
        }
    }

    private void getScoreFeatures(String[] queryStems, int docId, String field,
                                  RetrievalModelBM25 bm25Model,
                                  RetrievalModelIndri indriModel,
                                  double [] scores, int offset) throws IOException {
        TermVector termVector = new TermVector(docId, field);
        if (termVector.stemsLength() == 0)
            return;

        double sumDocLen = Idx.getSumOfFieldLengths(field);
        double docLen = Idx.getFieldLength(field, docId);
        double avgDocLen = sumDocLen / (double)Idx.getDocCount(field);

        double mu = indriModel.getMu();
        double lambda = indriModel.getLambda();

        double k1 = bm25Model.getK1();
        double k3 = bm25Model.getK3();
        double b = bm25Model.getB();
        double N = Idx.getNumDocs();
        double qtf = 1;

        /* count tf and ctf for each query term */
        int querySize = queryStems.length;
        double indriScore = 1.0;
        double bm25Score = 0.0;
        int matchCount = 0;
        for (int i = 0; i < querySize; i++) {
            boolean matched = false;
            String queryTerm = queryStems[i];
            for (int j = 1; j < termVector.stemsLength(); j++) {
                if (termVector.stemString(j).equals(queryTerm)) {
                    double tf = termVector.stemFreq(j);
                    double ctf = termVector.totalStemFreq(j);
                    double df = termVector.stemDf(j);
                    /* indri */
                    double qiUnderC = ctf / sumDocLen;
                    double indriTermScore = (1- lambda) * ((tf + mu * qiUnderC)/(docLen + mu))
                            + lambda * qiUnderC;
                    indriScore *= Math.pow(indriTermScore, 1.0/(double)querySize);
                    /* BM25 */
                    double floorRSJWeight = Math.max(0, Math.log((N-df+0.5)/(df+0.5)));
                    double tfWeight = tf / (tf + k1 * ((1-b)+b*docLen/avgDocLen));
                    double userWeight = (k3 + 1) * qtf/ (k3 + qtf);
                    double bm25TermScore = floorRSJWeight * tfWeight * userWeight;
                    // sum
                    bm25Score += bm25TermScore;
                    /* overlap */
                    matchCount++;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                /* Indri default score */
                InvList invList = new InvList(queryTerm, field);
                double ctf = invList.ctf;
                double qiUnderC = ctf / sumDocLen;
                double indriTermScore = (1- lambda) * ((mu * qiUnderC)/(docLen + mu))
                        + lambda * qiUnderC;
                indriScore *= Math.pow(indriTermScore, 1.0/(double)querySize);
            }
        }
        double overlapScore = (double)matchCount / (double)querySize;
        scores[offset] = bm25Score;
        scores[offset+1] = indriScore;
        scores[offset+2] = overlapScore;
    }

    private int countMatches(String stack, char needle) {
        int count = 0;
        for (int i = 0; i < stack.length(); i++) {
            if (stack.charAt(i) == needle)
                count++;
        }
        return count;
    }
}
