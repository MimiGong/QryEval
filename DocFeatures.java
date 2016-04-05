import java.util.Set;

/**
 * this class associates document with its features
 */
public class DocFeatures {

    double predictRelevance;
    int relevance;
    int qid;
    Double [] scores;
    String externalId;

    static Set<Integer> disableSet;

    static void setDisableSet(Set<Integer> set) {
        disableSet = set;
    }

    static boolean notDisableItem(Set<Integer> set, int item) {
        return set == null || !set.contains(item);
    }

    public String featureFormatter(Double[] scores) {
        int i = 1;
        StringBuilder builder = new StringBuilder();
        for (Double score : scores) {
            if (score != null && notDisableItem(disableSet, i))
                builder.append(String.format("%d:%f ", i, score));
            i++;
        }
        return builder.toString();
    }

    public DocFeatures(int relevance, int qid, String externalId, Double[] scores) {
        this.relevance = relevance;
        this.qid = qid;
        this.externalId = externalId;

        this.scores = new Double[scores.length];
        for (int i = 0; i < scores.length; i++)
            this.scores[i] = scores[i];
    }

    public String toString() {
        return String.format("%d qid:%d %s # %s", relevance, qid,
                featureFormatter(scores), externalId);
    }
}
