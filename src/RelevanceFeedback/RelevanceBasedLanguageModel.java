/**
 * RM3: Complete;
 * Relevance Based Language Model with query mix.
 * References:
 *      1. Relevance Based Language Model - Victor Lavrenko - SIGIR-2001
 *      2. UMass at TREC 2004: Novelty and HARD - Nasreen Abdul-Jaleel - TREC-2004
 */
package RelevanceFeedback;

import static common.CommonVariables.FIELD_BOW;
import static common.CommonVariables.FIELD_ID;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
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
import common.TRECQuery;
import common.TRECQueryParser;

/**
 *
 * @author dwaipayan
 */

public class RelevanceBasedLanguageModel {

    Properties      prop;
    String          indexPath;
    String          queryPath;      // path of the query file
    File            queryFile;      // the query file
    String          stopFilePath;
    IndexReader     indexReader;
    IndexSearcher   indexSearcher;
    String          resPath;        // path of the res file
    FileWriter      resFileWriter;  // the res file writer
    int             numHits;      // number of document to retrieveWithExpansionTermsFromFile
    String          runName;        // name of the run
    List<TRECQuery> queries;
    File            indexFile;          // place where the index is stored
    Analyzer        analyzer;           // the analyzer
    boolean         boolIndexExists;    // boolean flag to indicate whether the index exists or not
    String          fieldToSearch;      // the field in the index to be searched
    String          fieldForFeedback;   // field, to be used for feedback
    TRECQueryParser trecQueryparser;
    int             simFuncChoice;
    float           param1, param2;
    long            vocSize;            // vocabulary size
    RLM             rlm;
    Boolean         feedbackFromFile;

    HashMap<String, TopDocs> allTopDocsFromFileHashMap;    // to contain all topdocs from file
    
    float           mixingLambda;    // mixing weight, used for doc-col weight distribution
    int             numFeedbackTerms;// number of feedback terms
    int             numFeedbackDocs; // number of feedback documents
    float QMIX;

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
        fieldToSearch = prop.getProperty("fieldToSearch", FIELD_BOW);
        fieldForFeedback = fieldToSearch;
        /* index path set */

        simFuncChoice = Integer.parseInt(prop.getProperty("similarityFunction"));
        if (null != prop.getProperty("param1"))
            param1 = Float.parseFloat(prop.getProperty("param1"));
        if (null != prop.getProperty("param2"))
            param2 = Float.parseFloat(prop.getProperty("param2"));

        /* setting indexReader and indexSearcher */
        indexReader = DirectoryReader.open(FSDirectory.open(indexFile));
        indexSearcher = new IndexSearcher(indexReader);
        setSimilarityFunction(simFuncChoice, param1, param2);
        /* indexReader and searher set */

        /* setting query path */
        queryPath = prop.getProperty("queryPath");
        queryFile = new File(queryPath);
        /* query path set */

        /* constructing the query */
        trecQueryparser = new TRECQueryParser(queryPath, analyzer);
        queries = constructQueries();
        /* constructed the query */

        feedbackFromFile = Boolean.parseBoolean(prop.getProperty("feedbackFromFile"));
        if(feedbackFromFile == true) {
            String feedbackFilePath = prop.getProperty("feedbackFilePath");
            System.out.println("Using feedback information from file: " + feedbackFilePath);
            allTopDocsFromFileHashMap = common.CommonMethods.readTopDocsFromFile(feedbackFilePath, queries, indexReader);
        }

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
        QMIX = Float.parseFloat(prop.getProperty("rm3.queryMix"));

        rlm = new RLM(this);
    }

    /**
     * Sets indexSearcher.setSimilarity() with parameter(s)
     * @param choice similarity function selection flag
     * @param param1 similarity function parameter 1
     * @param param2 similarity function parameter 2
     */
    private void setSimilarityFunction(int choice, float param1, float param2) {

        switch(choice) {
            case 0:
                indexSearcher.setSimilarity(new DefaultSimilarity());
                break;
            case 1:
                indexSearcher.setSimilarity(new BM25Similarity(param1, param2));
                break;
            case 2:
                indexSearcher.setSimilarity(new LMJelinekMercerSimilarity(param1));
                break;
            case 3:
                indexSearcher.setSimilarity(new LMDirichletSimilarity(param1));
                break;
        }
    } // ends setSimilarityFunction()

    /**
     * Sets runName and resPath variables depending on similarity functions.
     */
    private void setRunName_ResFileName() {

        Similarity s = indexSearcher.getSimilarity();
        runName = s.toString()+"-D"+numFeedbackDocs+"-T"+numFeedbackTerms;
        runName += "-rm3-"+Float.parseFloat(prop.getProperty("rm3.queryMix", "0.98"));
        runName = runName.replace(" ", "").replace("(", "").replace(")", "").replace("00000", "");
        if(Boolean.parseBoolean(prop.getProperty("rm3.rerank")) == true)
            runName += "-rerank";
        if(null == prop.getProperty("resPath"))
            resPath = "/home/dwaipayan/";
        else
            resPath = prop.getProperty("resPath");
        resPath = resPath+queryFile.getName()+"-"+runName + ".res";
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

    public void retrieveAll() throws Exception {

        ScoreDoc[] hits;
        TopDocs topDocs;
        TopScoreDocCollector collector;
//        FileWriter baselineRes = new FileWriter(resPath+".baseline");

        for (TRECQuery query : queries) {
            collector = TopScoreDocCollector.create(numHits, true);
            Query luceneQuery = trecQueryparser.getAnalyzedQuery(query);

            System.out.println(query.qid+": Initial query: " + luceneQuery.toString(fieldToSearch));

            if(feedbackFromFile == true) {
                System.out.println("Feedback from file");
                if(null == (topDocs = allTopDocsFromFileHashMap.get(query.qid))) {
                    System.err.println("Error: Query id: "+query.qid+
                        " not present in res file");
                    continue;
                }
            }
            else {
                indexSearcher.search(luceneQuery, collector);
                topDocs = collector.topDocs();
            }

            /*
            // ++ Writing the baseline res
            baselineRes = new FileWriter(resPath+".baseline", true);

            StringBuffer resBuffer = new StringBuffer();
            resBuffer = CommonMethods.writeTrecResFileFormat(query.qid, hits, indexSearcher, runName);
            baselineRes.write(resBuffer.toString());
            baselineRes.close();
            // -- baseline res written
            //*/

            if(true) {

                rlm.setFeedbackStats(topDocs, luceneQuery.toString(fieldToSearch).split(" "), this);
                /**
                 * HashMap of P(w|R) for 'numFeedbackTerms' terms with top P(w|R) among each w in R,
                 * keyed by the term with P(w|R) as the value.
                 */
                HashMap<String, WordProbability> hashmap_PwGivenR;
                //hashmap_PwGivenR = rlm.RM1(query, topDocs);
                hashmap_PwGivenR = rlm.RM3(query, topDocs);
                BooleanQuery booleanQuery;

                if(Boolean.parseBoolean(prop.getProperty("rm3.rerank"))==false) {

                    booleanQuery = rlm.getExpandedQuery(hashmap_PwGivenR, query);
                    System.out.println("Re-retrieving with QE");
                    System.out.println(booleanQuery.toString(fieldToSearch));
                    collector = TopScoreDocCollector.create(numHits, true);
                    indexSearcher.search(booleanQuery, collector);

                    topDocs = collector.topDocs();
                    hits = topDocs.scoreDocs;
                    if(hits == null)
                        System.out.println("Nothing found");

                    int hits_length = hits.length;

                    resFileWriter = new FileWriter(resPath, true);

                    StringBuffer resBuffer = new StringBuffer();
                    for (int i = 0; i < hits_length; ++i) {
                        int docId = hits[i].doc;
                        Document d = indexSearcher.doc(docId);
                        resBuffer.append(query.qid).append("\tQ0\t").
                            append(d.get(FIELD_ID)).append("\t").
                            append((i)).append("\t").
                            append(hits[i].score).append("\t").
                            append(runName).append("\n");
                    }
                    resFileWriter.write(resBuffer.toString());

                    resFileWriter.close();
                }
                else {

                    System.out.println("Reranking");
                    List<NewScore> rerankedDocList = rlm.rerankUsingRBLM(hashmap_PwGivenR, query, topDocs);

                    int rerankedSize = rerankedDocList.size();
                    StringBuffer resBuffer = new StringBuffer();
                    for (int i = 0; i < rerankedSize; ++i) {
                        int docId = rerankedDocList.get(i).luceneDocid;
                        Document d = indexSearcher.doc(docId);
                        resBuffer.append(query.qid).append("\tQ0\t").
                            append(rerankedDocList.get(i).docid).append("\t").
                            append((i)).append("\t").
                            append((-1)*rerankedDocList.get(i).score).append("\t").
                            append(runName).append("\n");                
                    }
                    resFileWriter.write(resBuffer.toString());
                }
            }
        } // ends for each query
    } // ends retrieveAll

    public static void main(String[] args) throws IOException, Exception {

        args = new String[1];
        args[0] = "/home/dwaipayan/Dropbox/programs/RelevanceFeedback/rblm.D-20.T-70.properties";

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
//            args = new String[1];
//            args[0] = "rblm.D-20.T-70.properties";
            System.exit(1);
        }
        prop.load(new FileReader(args[0]));
        RelevanceBasedLanguageModel rblm = new RelevanceBasedLanguageModel(prop);

        rblm.retrieveAll();
    } // ends main()

}