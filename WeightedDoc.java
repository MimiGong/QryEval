/**
 *  This class implements the document and its score pair
 */
public class WeightedDoc {
    int docId;
    double score;
    public WeightedDoc(int docId, double score) {
        this.docId = docId;
        this.score = score;
    }
}