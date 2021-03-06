#!/bin/bash
# Generate the properties file and consequently execute the rblm program

stopFilePath="/home/dwaipayan/smart-stopwords"
#indexPath="/store/collections/indexed/wt10g.lucene.full-text/"
indexPath="/store/collections/indexed/trec678/"
#indexPath="/home/dwaipayan/wordvecsim/trec678.index/"
#indexPath="/home/dwaipayan/trec678-full.index/"
queryPath="/home/dwaipayan/Dropbox/ir/corpora-stats/topics_xml/trec8.xml"
resPath="/home/dwaipayan/"
#feedbackFromFile=true
feedbackFromFile=false
feedbackFilePath="/home/dwaipayan/res-files/baseline-lm/trec8/trec8.0.6.res"

fieldToSearch="content"
fieldForFeedback="content"

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

feedbackFromFile=$feedbackFromFile

feedbackFilePath=$feedbackFilePath

EOL
# .properties file made

java -Xms512M -Xmx1G -cp dist/RelevanceFeedback.jar RelevanceFeedback.RelevanceBasedLanguageModel $prop_name

