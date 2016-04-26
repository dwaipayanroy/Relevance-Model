/**
 * RM3: Complete;
 * Relevance Based Language Model with query mix.
 * @References:
 *      1. Relevance Based Language Model - Victor Lavrenko - SIGIR-2001
 *      2. UMass at TREC 2004: Novelty and HARD - Nasreen Abdul-Jaleel - TREC-2004
 */
package RelevanceFeedback;

import common.CollectionStatistics;
import common.DocumentVector;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.DefaultSimilarity;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import common.EnglishAnalyzerWithSmartStopword;
import common.PerTermStat;
import common.TRECQuery;
import common.TRECQueryParser;

/**
 *
 * @author dwaipayan
 */

public class RelevanceBasedLanguageModel {

    Properties      prop;
    CollectionStatistics    collStat;
    String          indexPath;
    String          queryPath;      // path of the query file
    File            queryFile;      // the query file
    String          stopFilePath;
    IndexReader     reader;
    IndexSearcher   searcher;
    String          resPath;        // path of the res file
    FileWriter      resFileWriter;  // the res file writer
    int             numHits;      // number of document to retrieveWithExpansionTermsFromFile
    String          runName;        // name of the run
    List<TRECQuery> queries;
    File            indexFile;          // place where the index is stored
    Analyzer        analyzer;           // the analyzer
    boolean         boolIndexExists;    // boolean flag to indicate whether the index exists or not
    String          fieldToSearch;  // the field in the index to be searched
    TRECQueryParser trecQueryparser;
    int             simFuncChoice;
    float           param1, param2;

    float           mixingLambda;    // mixing weight, used for doc-col weight distribution
    int             numFeedbackTerms;// number of feedback terms
    int             numFeedbackDocs; // number of feedback documents

    public RelevanceBasedLanguageModel(Properties prop) throws IOException, Exception {

        this.prop = prop;
        /* property file loaded */

        // +++++ setting the analyzer with English Analyzer with Smart stopword list
        stopFilePath = prop.getProperty("stopFilePath");
        EnglishAnalyzerWithSmartStopword engAnalyzer = new EnglishAnalyzerWithSmartStopword(stopFilePath);
        analyzer = engAnalyzer.setAndGetEnglishAnalyzerWithSmartStopword();
        // ----- analyzer set: analyzer

        /* index path setting */
        indexPath = prop.getProperty("indexPath");
        System.out.println("Using index at: " + indexPath);
        indexFile = new File(prop.getProperty("indexPath"));
        Directory indexDir = FSDirectory.open(indexFile);

        if (!DirectoryReader.indexExists(indexDir)) {
            System.err.println("Index doesn't exists in "+indexPath);
            boolIndexExists = false;
            System.exit(1);
        }
        fieldToSearch = prop.getProperty("fieldToSearch","content");
        /* index path set */

        System.out.println("Building collection statistics");
        collStat = new CollectionStatistics(indexPath, "content");
        collStat.buildCollectionStat();
        System.out.println("Collection Statistics building completed");

        simFuncChoice = Integer.parseInt(prop.getProperty("similarityFunction"));
        if (null != prop.getProperty("param1"))
            param1 = Float.parseFloat(prop.getProperty("param1"));
        if (null != prop.getProperty("param2"))
            param2 = Float.parseFloat(prop.getProperty("param2"));

        /* setting reader and searcher */
        reader = DirectoryReader.open(FSDirectory.open(indexFile));
        searcher = new IndexSearcher(reader);
        setSimilarityFunction(simFuncChoice, param1, param2);
        /* reader and searher set */

        /* setting query path */
        queryPath = prop.getProperty("queryPath");
        queryFile = new File(queryPath);
        /* query path set */

        /* constructing the query */
        trecQueryparser = new TRECQueryParser(queryPath, analyzer);
        queries = constructQueries();
        /* constructed the query */

        // numFeedbackTerms = number of top terms to select
        numFeedbackTerms = Integer.parseInt(prop.getProperty("numFeedbackTerms"));
        // numFeedbackDocs = number of top documents to select
        numFeedbackDocs = Integer.parseInt(prop.getProperty("numFeedbackDocs"));

        if(param1>0.99)
            mixingLambda = 0.8f;
        else
            mixingLambda = param1;

        /* setting res path */
        setRunName_ResFileName();
        resFileWriter = new FileWriter(resPath);
        System.out.println("Result will be stored in: "+resPath);

        /* res path set */
        numHits = Integer.parseInt(prop.getProperty("numHits","1000"));
    }

    /**
     * Sets searcher.setSimilarity() with parameter(s)
     * @param choice similarity function selection flag
     * @param param1 similarity function parameter 1
     * @param param2 similarity function parameter 2
     */
    private void setSimilarityFunction(int choice, float param1, float param2) {

        switch(choice) {
            case 0:
                searcher.setSimilarity(new DefaultSimilarity());
                break;
            case 1:
                searcher.setSimilarity(new BM25Similarity(param1, param2));
                break;
            case 2:
                searcher.setSimilarity(new LMJelinekMercerSimilarity(param1));
                break;
            case 3:
                searcher.setSimilarity(new LMDirichletSimilarity(param1));
                break;
        }
    } // ends setSimilarityFunction()

    /**
     * Sets runName and resPath variables depending on similarity functions.
     */
    private void setRunName_ResFileName() {

        Similarity s = searcher.getSimilarity();
        runName = queryFile.getName()+"-"+s.toString()+"-D"+numFeedbackDocs+"-T"+numFeedbackTerms;
        runName += "-rm3-"+Float.parseFloat(prop.getProperty("rm3.queryMix", "0.98"));
        runName = runName.replace(" ", "").replace("(", "").replace(")", "").replace("00000", "");
        if(Boolean.parseBoolean(prop.getProperty("rm3.rerank")) == true)
            runName += "-rerank";
        if(null == prop.getProperty("resPath"))
            resPath = "/home/dwaipayan/";
        else
            resPath = prop.getProperty("resPath");
        resPath = resPath+runName + ".res";
    } // ends setRunName_ResFileName()

    /**
     * Parses the query from the file and makes a List<TRECQuery> 
     *  containing all the queries (RAW query read)
     * @return A list with the all the queries
     * @throws Exception 
     */
    private List<TRECQuery> constructQueries() throws Exception {

        trecQueryparser.queryFileParse();
        return trecQueryparser.queries;
    } // ends constructQueries()

    /**
     * mixingLambda*tf(t,d)/d-size + (1-mixingLambda)*cf(t)/col-size
     * @param t The term under consideration
     * @param dv The document vector under consideration
     * @return MLE of t in a document dv, smoothed with collection statistics
     */
    public float return_Smoothed_MLE(String t, DocumentVector dv) {

        float smoothedMLEofTerm;
        PerTermStat docPTS;
        PerTermStat colPTS;

        docPTS = dv.docVec.get(t);
        colPTS = collStat.perTermStat.get(t);

        smoothedMLEofTerm = 
            ((docPTS!=null)?(mixingLambda * (float)docPTS.cf / (float)dv.size):(0))
            + ((colPTS!=null)?((1.0f-mixingLambda)*(float)colPTS.cf / (float)collStat.colSize):(0));

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
     * RM1 <p>
     * Returns 'hashmap_PwGivenR' containing all terms of PR docs with weights
     * IID Sampling <p>
     * P(w|R) = \sum{d\inD} {smoothedMLE(w,d)*smoothedMLE(Q,d)}
     * @see Relevance Based Language Model - Victor Lavrenko (SIGIR-2001)
     * @param query The query
     * @param topDocs Initial retrieved document list
     * @return 'hashmap_PwGivenR' containing all terms of PR docs with weights
     * @throws Exception 
     */
    ///*
    public HashMap IIDS(TRECQuery query, TopDocs topDocs) throws Exception {

        ScoreDoc[] hits;
        int hits_length;
        hits = topDocs.scoreDocs;
        hits_length = hits.length;               // number of documents retrieved in the first retrieval

        /**
         * Terms of the initially retrieved documents.
         */
        List<PerTermStat> list_retTerms;
        list_retTerms = new ArrayList<>();

        /**
         * Document vectors of the initially retrieved documents.
         */
        List<DocumentVector> list_retDocVecs;
        list_retDocVecs = new ArrayList<>();

        /**
         * List of all Docs D: probability of Q given a doc D.
         */
        List<Float> list_P_Q_GivenD;  
        list_P_Q_GivenD = new ArrayList<>();

        float p_Q_GivenD;
        float p_W_GivenR_one_doc;

        /**
         * List, for sorting the words in non-increasing order of probability.
         */
        List<WordProbability> list_PwGivenR;
        list_PwGivenR = new ArrayList<>();

        HashMap<String, WordProbability> hashmap_PwGivenR = new LinkedHashMap<>();  // to contain numFeedbackTerms terms with top P(w|R) among each w in R

        // +++++ computing P(Q|D) for each D initially retrieved 
        String[] analyzedQuery = query.queryFieldAnalyze(analyzer, query.qtitle).split("\\s+");
        for (int i = 0; i < Math.min(numFeedbackDocs, hits_length); i++) {
        // for each of the numFeedbackDocs initially retrieved documents:
            int luceneDocId = hits[i].doc;
            Document d = searcher.doc(luceneDocId);
            DocumentVector docV = new DocumentVector();
            docV = docV.getDocumentVector(luceneDocId, collStat);
            list_retDocVecs.add(docV);                // the document vector is added in the list

            for (Map.Entry<String, PerTermStat> entrySet : docV.docVec.entrySet()) {
            // for each of the terms of that initially retrived document
                String key = entrySet.getKey();
                PerTermStat value = entrySet.getValue();
                list_retTerms.add(value);
            } // ends for each t of the document

            p_Q_GivenD = 1;
            for (String qTerm : analyzedQuery)
                p_Q_GivenD *= return_Smoothed_MLE(qTerm, docV);
            list_P_Q_GivenD.add(p_Q_GivenD);
        }
        // ----- P(Q|D) for each D initially retrieved computed


        // Calculating for each wi in R: P(wi|R)~P(wi, q1 ... qk)
        // P(wi, q1 ... qk) = \sum_{D \in initial-ret-docs} {P(w|D)*\prod_{i=1... k} {P(qi|D}}
        for (PerTermStat retTerm : list_retTerms) {
            // for each t in R:
            String t = retTerm.t;
            p_W_GivenR_one_doc = 0;
            for (int i=0; i<list_retDocVecs.size(); i++) {
                p_W_GivenR_one_doc += 
                    return_Smoothed_MLE(t, list_retDocVecs.get(i)) 
                    * list_P_Q_GivenD.get(i);
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
    }   // ends IIDS()


    /**
     * RM3
     * P(w|R) = LAMBDA*RM1 + (1-LAMBDA)*P(w|Q)
     * @see Nasreen Abdul Jaleel - TREC 2004 UMass Report
     * @param query The query 
     * @param topDocs Initially retrieved document list
     * @return hashmap_PwGivenR: containing numFeedbackTerms expansion terms with weights
     * @throws Exception 
     */
    public HashMap RM3(TRECQuery query, TopDocs topDocs) throws Exception {

        HashMap<String, WordProbability> hashmap_PwGivenR = new LinkedHashMap<>();

        hashmap_PwGivenR = IIDS(query, topDocs);
        // hashmap_PwGivenR has all the terms of pseudo-relevant docs along with its probabilities 

        List<WordProbability> list_PwGivenR;

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
            //* else: The t is already entered in the hash-map 
        }
        // ++ Normalizing 
        for (Entry<String, WordProbability> entrySet : hashmap_PwGivenR.entrySet()) {
            WordProbability wp = entrySet.getValue();
            wp.p_w_given_R /= normFactor;
        }
        // -- Normalizing done

        String[] analyzedQuery = query.queryFieldAnalyze(analyzer, query.qtitle).split("\\s+");

        normFactor = 0;
        float QMIX = Float.parseFloat(prop.getProperty("rm3.queryMix"));
        //* Each w of R: P(w|R) to be QMIX*P(w|R) 
        for (Entry<String, WordProbability> entrySet : hashmap_PwGivenR.entrySet()) {
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
                oldProb = oldProba.p_w_given_R;
                normFactor += newProb;
                newProb += oldProb;
                hashmap_PwGivenR.put(qTerm, new WordProbability(qTerm, newProb));
            }
            else  // the qTerm is not in R
                hashmap_PwGivenR.put(qTerm, new WordProbability(qTerm, newProb));

        }

        // ++ Normalizing
        for (Entry<String, WordProbability> entrySet : hashmap_PwGivenR.entrySet()) {
            WordProbability wp = entrySet.getValue();
            wp.p_w_given_R /= normFactor;
        }
        // -- Normalizing done

        return hashmap_PwGivenR;
    } // end RM3()

    /**
     * 
     * @param expandedQuery The expanded query
     * @param query The query
     * @throws IOException
     * @throws Exception 
     */
    public void qeRBLM(HashMap<String, WordProbability> expandedQuery, TRECQuery query) throws IOException, Exception {

        ScoreDoc[] hits;
        TopDocs topDocs;
        TopScoreDocCollector collector;

        collector = TopScoreDocCollector.create(numHits, true);
        BooleanQuery booleanQuery = new BooleanQuery();

        
        for (Entry<String, WordProbability> entrySet : expandedQuery.entrySet()) {
            String key = entrySet.getKey();
            if(key.contains(":"))
                continue;
            WordProbability wProba = entrySet.getValue();
            float value = wProba.p_w_given_R;

            Term t = new Term(fieldToSearch, key);
            Query tq = new TermQuery(t);
            tq.setBoost(value);
            BooleanQuery.setMaxClauseCount(4096);
            booleanQuery.add(tq, BooleanClause.Occur.SHOULD);
        }

        System.out.println("Re-retrieving with QE");
        System.out.println(booleanQuery.toString(fieldToSearch));
        searcher.search(booleanQuery, collector);

        topDocs = collector.topDocs();
        hits = topDocs.scoreDocs;
        if(hits == null)
            System.out.println("Nothing found");

        int hits_length = hits.length;

        resFileWriter = new FileWriter(resPath, true);

        StringBuffer resBuffer = new StringBuffer();
        for (int i = 0; i < hits_length; ++i) {
            int docId = hits[i].doc;
            Document d = searcher.doc(docId);
            resBuffer.append(query.qid).append("\tQ0\t").
                append(d.get("docid")).append("\t").
                append((i)).append("\t").
                append(hits[i].score).append("\t").
                append(runName).append("\n");
        }
        resFileWriter.write(resBuffer.toString());

        resFileWriter.close();
    } // ends qeRBLM()

    /**
     * Rerank the result depending on the KL-Divergence between the estimated relevance model 
     *  and individual document model.
     * @param hashmap_topM_PwGivenR Top M terms with highest P(w|R)
     * @param query The raw query (unanalyzed)
     * @param topDocs Initial retrieved documents
     * @throws Exception 
     */
    public void rerankUsingRBLM(HashMap<String, WordProbability> hashmap_topM_PwGivenR, 
        TRECQuery query, TopDocs topDocs) throws Exception {

        List<NewScore> finalList = new ArrayList<>();
        ScoreDoc[] hits;

        int hits_length;
        String w;

        PerTermStat ptsFromDocument;
        PerTermStat ptsFromCollection;

        hits = topDocs.scoreDocs;
        hits_length = hits.length;               // number of documents retrieved in the first retrieval

        System.out.println("Reranking");
        double score;
        double preComputed_p_w_R;
        double singleTerm_p_w_d;

        for (int i = 0; i < hits_length; i++) {
        // for each initially retrieved documents
            int luceneDocId = hits[i].doc;
            Document d = searcher.doc(luceneDocId);
            DocumentVector dv = new DocumentVector();
            dv = dv.getDocumentVector(luceneDocId, collStat);
            score = 0;

            for (Entry<String, WordProbability> entrySet : hashmap_topM_PwGivenR.entrySet()) {
            // for each of the words in top numFeedbackTerms terms in R
                w = entrySet.getKey();
                ptsFromDocument     = dv.docVec.get(w);
                ptsFromCollection   = collStat.perTermStat.get(w);
                //preComputed_p_w_R = hashmap_top50_terms_p_w_given_R.get(w);
                preComputed_p_w_R = entrySet.getValue().p_w_given_R;

                singleTerm_p_w_d = ( ((ptsFromDocument!=null)?(mixingLambda * (double)ptsFromDocument.cf / (double)dv.size):(0.0))// );
                     + ((ptsFromCollection!=null)?((1-mixingLambda)*(double)ptsFromCollection.cf / (double)collStat.colSize):(0.0)) );
                score +=  (preComputed_p_w_R * (double)Math.log(preComputed_p_w_R/singleTerm_p_w_d));

            } // ends for each t in top numFeedbackTerms terms in R
            finalList.add(new NewScore(score, d.get("docid")));
        } //ends for each initially retrieved documents

        Collections.sort(finalList, new Comparator<NewScore>(){
            @Override
            public int compare(NewScore t, NewScore t1) {
                return t.score>t1.score?1:t.score==t1.score?0:-1;
            }
        });

        StringBuffer resBuffer = new StringBuffer();
        for (int i = 0; i < hits_length; ++i) {
            int docId = hits[i].doc;
            Document d = searcher.doc(docId);
            resBuffer.append(query.qid).append("\tQ0\t").
                append(finalList.get(i).docid).append("\t").
                append((i)).append("\t").
                append((-1)*finalList.get(i).score).append("\t").
                append(runName).append("\n");                
        }
        resFileWriter.write(resBuffer.toString());

    }

    /**
     * Relevance Based Language Model:
     * Input: Query Q, TopDocs
     * @param query The query 
     * @param topDocs Initial retrieved document list
     * @throws Exception 
     */
    public void blindFeedbackRBLM(TRECQuery query, TopDocs topDocs) throws Exception {

        HashMap<String, WordProbability> hashmap_topM_PwGivenR = new HashMap<>();  // to contain numFeedbackTerms terms with top P(w|R) among each w in R
        hashmap_topM_PwGivenR = RM3(query, topDocs);

        if(Boolean.parseBoolean(prop.getProperty("rm3.rerank"))==false)
            qeRBLM(hashmap_topM_PwGivenR, query);

        else // reranking
            rerankUsingRBLM(hashmap_topM_PwGivenR, query, topDocs);
    }

    public void retrieveAll() throws Exception {

        ScoreDoc[] hits;
        TopDocs topDocs;
        TopScoreDocCollector collector;
//        FileWriter baselineRes = new FileWriter(resPath+".baseline");

        for (TRECQuery query : queries) {
            collector = TopScoreDocCollector.create(numHits, true);
            Query luceneQuery = trecQueryparser.getAnalyzedQuery(query);

            System.out.println(query.qid+": Initial query: " + luceneQuery.toString(fieldToSearch));
            searcher.search(luceneQuery, collector);
            topDocs = collector.topDocs();
            hits = topDocs.scoreDocs;

            /*
            // ++ Writing the baseline res
            baselineRes = new FileWriter(resPath+".baseline", true);

            StringBuffer resBuffer = new StringBuffer();
            resBuffer = CommonMethods.writeTrecResFileFormat(query.qid, hits, searcher, runName);
            baselineRes.write(resBuffer.toString());
            baselineRes.close();
            // -- baseline res written
            //*/

            blindFeedbackRBLM(query, topDocs);

        } // ends for each query
    } // ends retrieveAll


    public static void main(String[] args) throws IOException, Exception {

        String usage = "java RelevanceBasedLanguageModel <properties-file>\n"
            + "Properties file must contain the following fields:\n"
            + "1. stopFilePath: path of the stopword file\n"
            + "2. fieldToSearch: field of the index to be searched\n"
            + "3. indexPath: Path of the index\n"
            + "4. queryPath: path of the query file (in proper xml format)\n"
            + "5. numFeedbackTerms: number of feedback terms to use\n"
            + "6. numFeedbackDocs: number of feedback documents to use\n"
            + "7. [numHits]: default-1000 - number of documents to retrieve\n"
            + "8. rm3.queryMix (0.0-1.0): query mix to weight between P(w|R) and P(w|Q)\n"
            + "9. [rm3.rerank]: default-0 - 1-Yes, 0-No\n"
            + "10. resPath: path of the folder in which the res file will be created\n"
            + "11. similarityFunction: 0.DefaultSimilarity, 1.BM25Similarity, 2.LMJelinekMercerSimilarity, 3.LMDirichletSimilarity\n"
            + "12. param1: \n"
            + "13. [param2]: optional if using BM25\n";

        Properties prop = new Properties();

        if(1 != args.length) {
            System.out.println("Usage: " + usage);
            System.exit(1);
        }
        prop.load(new FileReader(args[0]));
        RelevanceBasedLanguageModel rblm = new RelevanceBasedLanguageModel(prop);

        rblm.retrieveAll();
    } // ends main()
}