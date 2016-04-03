import java.util.ArrayList;

/**
 *  This class implements the document and its relevance judgement pair
 */
public class RelevantDocList {

    private ArrayList<String> externalIds = new ArrayList<>();
    private ArrayList<Integer> relevanceList = new ArrayList<>();

    public ArrayList<String> getIds() {
        return externalIds;
    }

    public ArrayList<Integer> getRelevance() {
        return relevanceList;
    }

    public void add(String externalId, Integer relevance) {
        externalIds.add(externalId);
        relevanceList.add(relevance);
    }
}
