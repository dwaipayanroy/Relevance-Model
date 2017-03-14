
package RelevanceFeedback;

/**
 *
 * @author dwaipayan
 */

public class NewScore {
    public double score;
    public String docid;
    public int luceneDocid;

    public NewScore() {
    }

    public NewScore(double score, String docid) {
        this.score = score;
        this.docid = docid;
    }

    public NewScore(int luceneDocid, double score) {
        this.luceneDocid = luceneDocid;
        this.score = score;
    }
    
}
