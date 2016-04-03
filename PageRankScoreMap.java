import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;

/**
 *  This class creates a fast lookup map for pagerank score
 */
public class PageRankScoreMap {

    private HashMap<String, Float> scoreMap;

    public float get(String externalId) {
        return scoreMap.get(externalId);
    }

    public PageRankScoreMap(String pagerankScoreFile) {
        final int capacity = 731073;
        /* a magic number approximates (size / 0.75) + 1 */
        scoreMap = new HashMap<>(capacity);
        /* read all lines in the ranking file and add to the map */
        try (BufferedReader infile =
                     new BufferedReader(new FileReader(pagerankScoreFile))) {
            String doc;
            while ((doc = infile.readLine()) != null && doc.length() > 0) {
                // split on any whitespace chars
                String[] entries = doc.split("\\s+");
                if (entries.length == 2) {
                    String externalId = entries[0];
                    Float score = Float.parseFloat(entries[1]);
                    scoreMap.put(externalId, score);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}