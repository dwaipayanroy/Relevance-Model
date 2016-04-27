# Relevance Based Language Model with Query mixing (RM3)
========

Build using Ant:

```
ant
```

Two ways to execute the program:

### 1. Using `rblm.sh`

Set the following parameters in `rblm.sh` file:

```
stopFilePath="The path of the stopword file"
indexPath="The path of the Lucene index on which the retrieval will be performed"
queryPath="The path of the query file in complete XML format"
resPath="Directory path in which the .res file will be saved in TREC 6-column .res format"
```

The similarity function that will be used for the actual retrieval is set to `LMJelinekMercerSimilarity` with parameter set to `0.6`. (Modifiable)

`numHits` i.e. the maximum number of documents to retrieve is set to 1000. (Modifiable)

The shell script `rblm.sh` takes the following command line inputs:
```
1. Number of expansion documents
2. Number of expansion terms
3. RM3 QueryMix LAMBDA for calculating LAMBDA*P(w|R) + (1-LAMBDA)*P(w|Q)
4. [Rerank] YES-1, NO-0; Default is 0.
```
Run:
```
./rblm.sh 20 70 0.6 
```

The above parameter will apply the number of feedback documents and terms to 20 and 70 respectively and set the query-mix parameter to 0.6.
Finally, there will be a re-retrieval with query expansion.

### 2. Directly running the `RelevanceModel` class

Create a properties file (say, `init.properties`) and set the following parameters:

```
indexPath=<The path of the Lucene index on which the retrieval will be performed>

queryPath=<The path of the query file in complete XML format>

stopFilePath=<The path of the stopword file>

resPath=<Directory path in which the .res file will be saved in TREC 6-column .res format>

numHits=<The maximum number of documents to retrieve>

### Similarity functions:
similarityFunction=0 OR 1 OR 2 OR 3 # Respectively for DefaultSimilarity, BM25Similarity, LMJelinekMercerSimilarity and LMDirichletSimilarity

param1=<First parameter of the similarity function>
param2=<Second parameter of the similarity function>

numFeedbackDocs=<Number of feedback documents>

numFeedbackTerms=<Number of feedback terms>

rm3.queryMix=<RM3 QueryMix `LAMBDA` for calculating `LAMBDA*P(w|R) + (1-LAMBDA)*P(w|Q)>`

rm3.rerank=true

```
Run:
```
java -Xmx6g -cp dist/RelevanceFeedback.jar RelevanceFeedback.RelevanceBasedLanguageModel init.properties
```
