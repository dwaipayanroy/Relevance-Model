/*
TODO: To add an option:
    If Rerank (i.e. KLD), then add an option whether to precompute the 
        collection statistics (e.g. col-probab. that is needed) for all the terms
        of collection into hashmap to reduce time consumption during reranking.
 */
package RelevanceFeedback;

import static common.CommonVariables.FIELD_ID;
import common.DocumentVector;
import common.PerTermStat;
import common.TRECQuery;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;

/**
 *
 * @author dwaipayan
 */
public class RLM {

    IndexReader     indexReader;
    IndexSearcher   indexSearcher;
    String          fieldForFeedback;   // the field of the index which will be used for feedback
    Analyzer        analyzer;
    
    int             numFeedbackTerms;// number of feedback terms
    int             numFeedbackDocs; // number of feedback documents
    float           mixingLambda;    // mixing weight, used for doc-col weight adjustment
    float           QMIX;           // query mixing parameter; to be used for RM3, RM4 (not done)

    RelevanceBasedLanguageModel rblm;   // main class from which the call is done; used for setting the variables.

    /**
     * Hashmap of Vectors of all feedback documents, keyed by luceneDocId.
     */
    HashMap<Integer, DocumentVector>    feedbackDocumentVectors;
    /**
     * HashMap of PerTermStat of all feedback terms, keyed by the term.
     */
    HashMap<String, PerTermStat>        feedbackTermStats;
    /**
     * HashMap of P(Q|D) for all feedback documents, keyed by luceneDocId.
     */
    HashMap<Integer, Float> hash_P_Q_Given_D;

    TopDocs         topDocs;

    long            vocSize;        // vocabulary size
    long            docCount;       // number of documents in the collection

    /**
     * List, for sorting the words in non-increasing order of probability.
     */
    List<WordProbability> list_PwGivenR;
    /**
     * HashMap of P(w|R) for 'numFeedbackTerms' terms with top P(w|R) among each w in R,
     * keyed by the term with P(w|R) as the value.
     */
    HashMap<String, WordProbability> hashmap_PwGivenR;

    /**
     * HashMap<DocId, DocumentVector> to contain all topdocs for reranking.
     * Only used if reranking, reading top docs from file.
     */
    HashMap<String, DocumentVector> topDocsDV = new HashMap<>();

    public RLM(RelevanceBasedLanguageModel rblm) throws IOException {

        this.rblm = rblm;
        this.indexReader = rblm.indexReader;
        this.indexSearcher = rblm.indexSearcher;
        this.analyzer = rblm.analyzer;
        this.fieldForFeedback = rblm.fieldForFeedback;
        this.numFeedbackDocs = rblm.numFeedbackDocs;
        this.numFeedbackTerms = rblm.numFeedbackTerms;
        this.mixingLambda = rblm.mixingLambda;
        this.QMIX = rblm.QMIX;
        vocSize = getVocabularySize();
        docCount = indexReader.maxDoc();      // total number of documents in the index

    }

    /**
     * Sets the following variables with feedback statistics: to be used consequently.<p>
     * {@link #feedbackDocumentVectors},<p> 
     * {@link #feedbackTermStats}, <p>
     * {@link #hash_P_Q_Given_D}
     * @param topDocs
     * @param analyzedQuery
     * @param rblm
     * @throws IOException 
     */
    public void setFeedbackStats(TopDocs topDocs, String[] analyzedQuery, RelevanceBasedLanguageModel rblm) throws IOException {

        feedbackDocumentVectors = new HashMap<>();
        feedbackTermStats = new HashMap<>();
        hash_P_Q_Given_D = new HashMap<>();

        ScoreDoc[] hits;
        int hits_length;
        hits = topDocs.scoreDocs;
        hits_length = hits.length;               // number of documents retrieved in the first retrieval

        for (int i = 0; i < Math.min(numFeedbackDocs, hits_length); i++) {
            // for each feedback document
            int luceneDocId = hits[i].doc;
            Document d = indexSearcher.doc(luceneDocId);
            DocumentVector docV = new DocumentVector();
            docV = docV.getDocumentVector(luceneDocId, indexReader);
            if(docV == null)
                continue;
            feedbackDocumentVectors.put(luceneDocId, docV);                // the document vector is added in the list

            for (Map.Entry<String, PerTermStat> entrySet : docV.docPerTermStat.entrySet()) {
            // for each term of that feedback document
                String key = entrySet.getKey();
                PerTermStat value = entrySet.getValue();
                if(null == feedbackTermStats.get(key))
                    feedbackTermStats.put(key, new PerTermStat(key, value.getCF(), value.getDF()));
                else {
                    value.setCF(value.getCF() + feedbackTermStats.get(key).getCF());
                    value.setDF(value.getDF() + feedbackTermStats.get(key).getDF());
                    feedbackTermStats.put(key, value);
                }
            } // ends for each term of that feedback document
        } // ends for each feedback document

        // Calculating P(Q|d) for each feedback documents
        for (Map.Entry<Integer, DocumentVector> entrySet : feedbackDocumentVectors.entrySet()) {
            // for each feedback document
            int luceneDocId = entrySet.getKey();
            DocumentVector docV = entrySet.getValue();

            float p_Q_GivenD = 1;
            for (String qTerm : analyzedQuery)
                p_Q_GivenD *= return_Smoothed_MLE(qTerm, docV);
            if(null == hash_P_Q_Given_D.get(luceneDocId))
                hash_P_Q_Given_D.put(luceneDocId, p_Q_GivenD);
            else {
                System.err.println("Error while pre-calculating P(Q|d). "
                + "For luceneDocId: " + luceneDocId + ", P(Q|d) already existed.");
            }
        }

    }

    /**
     * mixingLambda*tf(t,d)/d-size + (1-mixingLambda)*cf(t)/col-size
     * @param t The term under consideration
     * @param dv The document vector under consideration
     * @return MLE of t in a document dv, smoothed with collection statistics
     */
    public float return_Smoothed_MLE(String t, DocumentVector dv) throws IOException {

        float smoothedMLEofTerm = 1;
        PerTermStat docPTS;

//        HashMap<String, PerTermStat>     docPerTermStat = dv.getDocPerTermStat();
//        docPTS = docPerTermStat.get(t);
        docPTS = dv.docPerTermStat.get(t);
//        colPTS = collStat.perTermStat.get(t);
        PerTermStat colPTS = feedbackTermStats.get(t);

        if (colPTS != null) {
            smoothedMLEofTerm = 
                ((docPTS!=null)?(mixingLambda * (float)docPTS.getCF() / (float)dv.getDocSize()):(0)) +
                ((feedbackTermStats.get(t)!=null)?((1.0f-mixingLambda)*(float)feedbackTermStats.get(t).getCF()/(float)vocSize):0);
        }
        return smoothedMLEofTerm;
    } // ends return_Smoothed_MLE()

    /**
     * Returns the vocabulary size of the collection for 'fieldToSearch'.
     * @return vocSize Total number of terms in the vocabulary
     * @throws IOException IOException
     */
    private long getVocabularySize() throws IOException {

        Fields fields = MultiFields.getFields(indexReader);
        Terms terms = fields.terms(fieldForFeedback);
        if(null == terms) {
            System.err.println("Field: "+fieldForFeedback);
            System.err.println("Error buildCollectionStat(): terms Null found");
        }
        vocSize = terms.getSumTotalTermFreq();  // total number of terms in the index in that field

        return vocSize;  // total number of terms in the index in that field
    }

    public float getCollectionProbability(String term, IndexReader reader, String fieldName) throws IOException {

        Term termInstance = new Term(fieldName, term);
        long termFreq = reader.totalTermFreq(termInstance); // CF: Returns the total number of occurrences of term across all documents (the sum of the freq() for each doc that has this term).

        return (float) termFreq / (float) vocSize;
    }

    /**
     * mixingLambda*tf(t,d)/d-size + (1-mixingLambda)*cf(t)/col-size
     * @param pts
     * @param dv The document vector under consideration
     * @return MLE of t in a document dv, smoothed with collection statistics
     * @throws IOException IOException
     */
    public float return_Smoothed_MLE(PerTermStat pts, DocumentVector dv) throws IOException {

        float smoothedMLEofTerm;
        PerTermStat docPTS;
        PerTermStat colPTS;

//        HashMap<String, PerTermStat>     docPerTermStat = dv.getDocPerTermStat();
//        docPTS = docPerTermStat.get(t);
        docPTS = dv.docPerTermStat.get(pts.t);
//        colPTS = collStat.perTermStat.get(t);

        smoothedMLEofTerm = 
            ((docPTS!=null)?(mixingLambda * (float)docPTS.getCF() / (float)dv.getDocSize()):(0))
            + (1.0f-mixingLambda)*((float)pts.getCF()/(float)vocSize);
//            + ((colPTS!=null)?((1.0f-mixingLambda)*(float)colPTS.getCF() / (float)vocSize):(0));

        return smoothedMLEofTerm;
    } // ends return_Smoothed_MLE()

    /**
     * Returns MLE of a query term q in Q;<p>
     * P(w|Q) = tf(w,Q)/|Q|
     * @param qTerms all query terms
     * @param qTerm query term under consideration
     * @return MLE of qTerm in the query qTerms
     */
    public float returnMLE_of_q_in_Q(String[] qTerms, String qTerm) {

        int count=0;
        for (String queryTerm : qTerms)
            if (qTerm.equals(queryTerm))
                count++;
        return ( (float)count / (float)qTerms.length );
    } // ends returnMLE_of_w_in_Q()

    /**
     * RM1: IID Sampling <p>
     * Returns 'hashmap_PwGivenR' containing all terms of PR docs (PRD) with 
     * weights calculated using IID Sampling <p>
     * P(w|R) = \sum{d\in PRD} {smoothedMLE(w,d)*smoothedMLE(Q,d)}
     * Reference: Relevance Based Language Model - Victor Lavrenko (SIGIR-2001)
     * @param query The query
     * @param topDocs Initial retrieved document list
     * @return 'hashmap_PwGivenR' containing all terms of PR docs with weights
     * @throws Exception 
     */
    ///*
    public HashMap RM1(TRECQuery query, TopDocs topDocs) throws Exception {

        float p_W_GivenR_one_doc;

        list_PwGivenR = new ArrayList<>();

        hashmap_PwGivenR = new LinkedHashMap<>();

        // Calculating for each wi in R: P(wi|R)~P(wi, q1 ... qk)
        // P(wi, q1 ... qk) = \sum_{D \in initial-ret-docs} {P(w|D)*\prod_{i=1... k} {P(qi|D}}

        for (Map.Entry<String, PerTermStat> entrySet : feedbackTermStats.entrySet()) {
            // for each t in R:
            String t = entrySet.getKey();
            p_W_GivenR_one_doc = 0;

            for (Map.Entry<Integer, DocumentVector> docEntrySet : feedbackDocumentVectors.entrySet()) {
                int luceneDocId = docEntrySet.getKey();
                p_W_GivenR_one_doc += 
                    return_Smoothed_MLE(entrySet.getValue(), feedbackDocumentVectors.get(luceneDocId)) *
                    hash_P_Q_Given_D.get(luceneDocId);
            }
            list_PwGivenR.add(new WordProbability(t, p_W_GivenR_one_doc));
        }

        // ++ sorting list in descending order
        Collections.sort(list_PwGivenR, new Comparator<WordProbability>(){
            @Override
            public int compare(WordProbability t, WordProbability t1) {
                return t.p_w_given_R<t1.p_w_given_R?1:t.p_w_given_R==t1.p_w_given_R?0:-1;
            }});
        // -- sorted list in descending order

        for (WordProbability singleTerm : list_PwGivenR) {
            if (null == hashmap_PwGivenR.get(singleTerm.w)) {
                hashmap_PwGivenR.put(singleTerm.w, new WordProbability(singleTerm.w, singleTerm.p_w_given_R));
            }
            //* else: The t is already entered in the hash-map 
        }

        return hashmap_PwGivenR;
    }   // ends RM1()


    /**
     * RM3 <p>
     * P(w|R) = QueryMix*RM1 + (1-QueryMix)*P(w|Q) <p>
     * Reference: Nasreen Abdul Jaleel - TREC 2004 UMass Report <p>
     * @param query The query 
     * @param topDocs Initially retrieved document list
     * @return hashmap_PwGivenR: containing numFeedbackTerms expansion terms with normalized weights
     * @throws Exception 
     */
    public HashMap RM3(TRECQuery query, TopDocs topDocs) throws Exception {

        hashmap_PwGivenR = new LinkedHashMap<>();

        hashmap_PwGivenR = RM1(query, topDocs);
        // hashmap_PwGivenR has all terms of PRDs along with their probabilities 

        /*
        // +++ Insearting the idf factor
        for (Map.Entry<String, WordProbability> entrySet : hashmap_PwGivenR.entrySet()) {
            String key = entrySet.getKey();
            WordProbability value = entrySet.getValue();
            value.p_w_given_R *= feedbackTermStats.get(key).getIDF();
        }
        hashmap_PwGivenR = sortByValues(hashmap_PwGivenR);
        // ---
        //*/

        // +++ selecting top numFeedbackTerms terms and normalize
        int expansionTermCount = 0;
        float normFactor = 0;

        list_PwGivenR = new ArrayList<>(hashmap_PwGivenR.values());
        hashmap_PwGivenR = new LinkedHashMap<>();
        for (WordProbability singleTerm : list_PwGivenR) {
            if (null == hashmap_PwGivenR.get(singleTerm.w)) {
                hashmap_PwGivenR.put(singleTerm.w, new WordProbability(singleTerm.w, singleTerm.p_w_given_R));
                expansionTermCount++;
                normFactor += singleTerm.p_w_given_R;
                if(expansionTermCount>=numFeedbackTerms)
                    break;
            }
            //* else: The t is already there in the hash-map 
        }
        // ++ Normalizing 
        for (Map.Entry<String, WordProbability> entrySet : hashmap_PwGivenR.entrySet()) {
            WordProbability wp = entrySet.getValue();
            wp.p_w_given_R /= normFactor;
        }
        // -- Normalizing done

        String[] analyzedQuery = query.queryFieldAnalyze(analyzer, query.qtitle).split("\\s+");

        normFactor = 0;
        //* Each w of R: P(w|R) to be QMIX*P(w|R) 
        for (Map.Entry<String, WordProbability> entrySet : hashmap_PwGivenR.entrySet()) {
            String key = entrySet.getKey();
            WordProbability value = entrySet.getValue();
            value.p_w_given_R = value.p_w_given_R * QMIX;
            normFactor += value.p_w_given_R;
        }

        // Now P(w|R) = QMIX*P(w|R)
        //* Each w which are also query terms: P(w|R) += (1-QMIX)*P(w|Q)
        //      P(w|Q) = tf(w,Q)/|Q|
        for (String qTerm : analyzedQuery) {
            WordProbability oldProba = hashmap_PwGivenR.get(qTerm);
            float newProb = (1.0f-QMIX) * returnMLE_of_q_in_Q(analyzedQuery, qTerm);
            float oldProb;
            if (null != oldProba) { // qTerm is in R
                oldProba.p_w_given_R += newProb;
                normFactor += newProb;
                hashmap_PwGivenR.put(qTerm, oldProba);
            }
            else  // the qTerm is not in R
                hashmap_PwGivenR.put(qTerm, new WordProbability(qTerm, newProb));
        }

        // ++ Normalizing
        for (Map.Entry<String, WordProbability> entrySet : hashmap_PwGivenR.entrySet()) {
            WordProbability wp = entrySet.getValue();
            wp.p_w_given_R /= normFactor;
        }
        // -- Normalizing done

        return hashmap_PwGivenR;
    } // end RM3()

    /**
     * Returns the expanded query in BooleanQuery form with P(w|R) as 
     * corresponding weights for the expanded terms
     * @param expandedQuery The expanded query
     * @param query The query
     * @return BooleanQuery to be used for consequent re-retrieval
     * @throws Exception 
     */
    public BooleanQuery getExpandedQuery(HashMap<String, WordProbability> expandedQuery, TRECQuery query) throws Exception {

        BooleanQuery booleanQuery = new BooleanQuery();
        
        for (Map.Entry<String, WordProbability> entrySet : expandedQuery.entrySet()) {
            String key = entrySet.getKey();
            if(key.contains(":"))
                continue;
            WordProbability wProba = entrySet.getValue();
            float value = wProba.p_w_given_R;

            Term t = new Term(rblm.fieldToSearch, key);
            Query tq = new TermQuery(t);
            tq.setBoost(value);
            BooleanQuery.setMaxClauseCount(4096);
            booleanQuery.add(tq, BooleanClause.Occur.SHOULD);
        }

        return booleanQuery;
    } // ends getExpandedQuery()

    /**
     * Rerank the result depending on the KL-Divergence between the estimated relevance model 
     *  and individual document model.
     * @param hashmap_topM_PwGivenR Top M terms with highest P(w|R)
     * @param query The raw query (unanalyzed)
     * @param topDocs Initial retrieved documents
     * @throws Exception 
     */
    public List<NewScore> rerankUsingRBLM(HashMap<String, WordProbability> hashmap_topM_PwGivenR, 
        TRECQuery query, TopDocs topDocs) throws Exception {

        List<NewScore> finalList = new ArrayList<>();
        ScoreDoc[] hits;

        int hits_length;
        String w;

        PerTermStat ptsFromDocument;

        hits = topDocs.scoreDocs;
        hits_length = hits.length;               // number of documents retrieved in the first retrieval

        double score;
        double preComputed_p_w_R;
        double singleTerm_p_w_d;

        for (int i = 0; i < hits_length; i++) {
            int luceneDocId = hits[i].doc;
            Document d = indexSearcher.doc(luceneDocId);
            DocumentVector dv = new DocumentVector();
            dv = dv.getDocumentVector(luceneDocId, indexReader);

            score = 0;

            for (Map.Entry<String, WordProbability> entrySet : hashmap_topM_PwGivenR.entrySet()) {
            // for each of the words in top numFeedbackTerms terms in R
                w = entrySet.getKey();
                ptsFromDocument     = dv.docPerTermStat.get(w);
//                ptsFromCollection   = collStat.perTermStat.get(w);
                preComputed_p_w_R = entrySet.getValue().p_w_given_R;

                singleTerm_p_w_d = ( ((ptsFromDocument!=null)?(mixingLambda * (double)ptsFromDocument.getCF() / (double)dv.getDocSize()):(0.0))// );
                    + (1.0f-mixingLambda)*(getCollectionProbability(w, indexReader, fieldForFeedback)));
//                     + ((ptsFromCollection!=null)?((1-mixingLambda)*(double)ptsFromCollection.getCF() / (double)vocSize):(0.0)) );
                score +=  (preComputed_p_w_R * (double)Math.log(preComputed_p_w_R/singleTerm_p_w_d));

            } // ends for each t in top numFeedbackTerms terms in R

            finalList.add(new NewScore(score, d.get(FIELD_ID)));
        } //ends for each initially retrieved documents

        Collections.sort(finalList, new Comparator<NewScore>(){
            @Override
            public int compare(NewScore t, NewScore t1) {
                return t.score>t1.score?1:t.score==t1.score?0:-1;
            }
        });

        return finalList;
    }

    private static HashMap sortByValues(HashMap map) {
        List<Map.Entry<String, WordProbability>> list = new ArrayList(map.entrySet());
        // Defined Custom Comparator here
        Collections.sort(list, new Comparator<Map.Entry<String, WordProbability>>() {
            @Override
            public int compare(Map.Entry<String, WordProbability> t1, Map.Entry<String, WordProbability> t2) {
                return t1.getValue().p_w_given_R<t2.getValue().p_w_given_R?1:t1.getValue().p_w_given_R==t2.getValue().p_w_given_R?0:-1;
            }
        });

        // Copying the sorted list in HashMap
        // using LinkedHashMap to preserve the insertion order
        HashMap sortedHashMap = new LinkedHashMap();
        for (Map.Entry entry : list) {
            sortedHashMap.put(entry.getKey(), entry.getValue());
        }
        return sortedHashMap;
    }
}
