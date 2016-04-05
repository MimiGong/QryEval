/**
 * Created by wudikuail on 4/2/16.
 */
public class DocFeatures {

    double predictRelevance;
    int relevance;
    int qid;
    double [] scores;
    String externalId;

    public String featureFormatter(double[] scores) {
        int i = 1;
        StringBuilder builder = new StringBuilder();
        for (double score : scores) {
            builder.append(String.format("%d:%f ", i, score));
            i++;
        }
        return builder.toString();
    }

    public DocFeatures(int relevance, int qid, String externalId, double[] scores) {
        this.relevance = relevance;
        this.qid = qid;
        this.externalId = externalId;

        this.scores = new double[scores.length];
        for (int i = 0; i < scores.length; i++)
            this.scores[i] = scores[i];
    }

    public String toString() {
        return String.format("%d qid:%d %s # %s", relevance, qid,
                featureFormatter(scores), externalId);
    }
}
