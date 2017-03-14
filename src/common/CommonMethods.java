
package common;

import RelevanceFeedback.NewScore;
import static common.CommonVariables.FIELD_ID;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;

/**
 *
 * @author dwaipayan
 */
public class CommonMethods {

    /**
     * Returns a string-buffer in the TREC-res format for the passed queryId
     * @param queryId
     * @param hits
     * @param searcher
     * @param runName
     * @return
     * @throws IOException 
     */
    static final public StringBuffer writeTrecResFileFormat(String queryId, ScoreDoc[] hits, 
        IndexSearcher searcher, String runName) throws IOException {

        StringBuffer resBuffer = new StringBuffer();
        int hits_length = hits.length;
        for (int i = 0; i < hits_length; ++i) {
            int luceneDocId = hits[i].doc;
            Document d = searcher.doc(luceneDocId);
            resBuffer.append(queryId).append("\tQ0\t").
                append(d.get("docid")).append("\t").
                append((i)).append("\t").
                append(hits[i].score).append("\t").
                append(runName).append("\n");                
        }

        return resBuffer;
    }
    /**
     * Read 6 column TREC-res file to use for Relevance feedback 
     * @param resFile The path of the result file
     * @return A hashmap, keyed by the query-id with value, containing the topDocs read from file
     * @throws Exception 
     */
    public static HashMap<String, TopDocs> readTopDocsFromFile(String resFile, List<TRECQuery> queries,
        IndexReader indexReader) throws Exception {

        HashMap<String, TRECQuery> hm_Query = new HashMap();
        for (TRECQuery query : queries) {
            hm_Query.put(query.qid, query);
        }

        HashMap<String, TopDocs> allTopDocsHashMap = new HashMap<>();

        IndexSearcher docidSearcher;
        docidSearcher = new IndexSearcher(indexReader);

        ScoreDoc[] docidHits = null;
        TopDocs docidTopDocs = null;
        Query docidQuery;
        TopScoreDocCollector collector;

        QueryParser docidSearchParser = new QueryParser(FIELD_ID, new WhitespaceAnalyzer());
        BufferedReader br = new BufferedReader(new FileReader(resFile));

        String line;
        String presentQueryId;
        String presentDocId;
        float presentScore;
        int presentLuceneDocId;

        String lastQid = null;

        String tokens[];
        List<NewScore> listLuceneDocId;
        listLuceneDocId = new ArrayList<>();

        do {
            line = br.readLine();

            if(line == null && listLuceneDocId != null) {  // end of file is reached and there are entires in listLuceneDocId to be put in hashmap
                
                ScoreDoc scoreDoc[] = new ScoreDoc[listLuceneDocId.size()];
                for (int i=0; i<listLuceneDocId.size(); i++) {
                    scoreDoc[i] = new ScoreDoc(listLuceneDocId.get(i).luceneDocid, (float) listLuceneDocId.get(i).score);
                }
                TopDocs topDocs;
                topDocs = new TopDocs(listLuceneDocId.size(), scoreDoc, (float) listLuceneDocId.get(0).score);
                TRECQuery trecQuery = hm_Query.get(lastQid);
                System.out.println(lastQid+": "+trecQuery.qtitle);

                allTopDocsHashMap.put(lastQid, topDocs);

                break;
            }

            tokens = line.split("\\t");
            presentQueryId = tokens[0];
            presentDocId = tokens[2];
            presentScore = Float.parseFloat(tokens[4]);

            docidQuery = docidSearchParser.parse(presentDocId);
            collector = TopScoreDocCollector.create(1, true);
            docidSearcher.search(docidQuery, collector);
            docidTopDocs = collector.topDocs();
            docidHits = docidTopDocs.scoreDocs;
            if(docidHits == null) {
                System.err.println("Lucene docid not found for: "+tokens[2]);
                continue;
            }
            presentLuceneDocId = docidHits[0].doc;

            if(null != lastQid && !lastQid.equals(presentQueryId)) {

                ScoreDoc scoreDoc[] = new ScoreDoc[listLuceneDocId.size()];
                for (int i=0; i<listLuceneDocId.size(); i++) {
                    scoreDoc[i] = new ScoreDoc(listLuceneDocId.get(i).luceneDocid, (float) listLuceneDocId.get(i).score);
                }
                ScoreDoc[] hits;
                TopDocs topDocs;
                topDocs = new TopDocs(listLuceneDocId.size(), scoreDoc, (float) listLuceneDocId.get(0).score);
                TRECQuery trecQuery = hm_Query.get(lastQid);
                System.out.println(lastQid+": "+trecQuery.qtitle);

                allTopDocsHashMap.put(lastQid, topDocs);

                listLuceneDocId = new ArrayList<>();
            }

            listLuceneDocId.add(new NewScore(presentLuceneDocId, presentScore));
            lastQid = presentQueryId;

        } while (true);

        return allTopDocsHashMap;
    } // ends readTopDocsFromFile()

}
