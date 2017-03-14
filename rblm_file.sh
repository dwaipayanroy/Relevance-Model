#!/bin/bash
# Generate the properties file and consequently execute the rblm program

# readlink must be installed

stopFilePath="/home/dwaipayan/smart-stopwords"
#indexPath="/store/collections/indexed/wt10g.lucene.full-text/"
indexPath="/store/collections/indexed/trec678/"
#indexPath="/home/dwaipayan/wordvecsim/trec678.index/"
#indexPath="/home/dwaipayan/trec678-full.index/"
queryPath="/home/dwaipayan/Dropbox/ir/corpora-stats/topics_xml/trec678-robust.xml"
resPath="/home/dwaipayan/"
feedbackFilePath="/home/dwaipayan/trec6..res"

echo "Using stopFilePath="$stopFilePath
echo "Using indexPath="$indexPath
echo "Using queryPath="$queryPath
echo "Using resPath="$resPath

if [ $# -le 2 ] 
then
    echo "Usage: " $0 " <no.-of-pseudo-rel-docs> <no.-of-expansion-terms> <query-mix (default-0.98)>";
    echo "1. Number of expansion documents";
    echo "2. Number of expansion terms";
    echo "3. RM3 - QueryMix:";
    echo "4. [Rerank]? - Yes-1  No-0 (default)"
    exit 1;
fi

prop_name="rblm.D-"$1".T-"$2".properties"
#echo $prop_name


if [ $# -eq 4 ] && [ $4 = "1" ]
then
    rerank="true"
    echo "Reranking"
else
    rerank="false"
    echo "Re-retrieving"
fi

# making the .properties file
cat > $prop_name << EOL

indexPath=$indexPath

queryPath=$queryPath

stopFilePath=$stopFilePath

resPath=$resPath

numHits= 1000

### Similarity functions:
#0 - DefaultSimilarity
#1 - BM25Similarity
#2 - LMJelinekMercerSimilarity
#3 - LMDirichletSimilarity
similarityFunction=2

param1=0.6
param2=0.0

# Number of documents
numFeedbackDocs=$1

# Number of terms
numFeedbackTerms=$2

rm3.queryMix=$3

rm3.rerank=$rerank

feedbackFromFile=true

feedbackFilePath=$feedbackFilePath

EOL
# .properties file made

java -Xmx6g -cp dist/RelevanceFeedback.jar RelevanceFeedback.RelevanceBasedLanguageModel $prop_name

