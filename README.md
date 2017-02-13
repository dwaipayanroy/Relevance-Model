# Relevance Based Language Model with Query mixing (RM3)
========

Build using Ant:

```
ant
```

Two ways to execute the program:

### 1. Using `rblm.sh`

Set the following in `rblm.sh` file:
```
stopFilePath="The path of the stopword file"
indexPath="The path of the Lucene index on which the retrieval will be performed"
queryPath="The path of the query file in complete XML format"
resPath="Directory path in which the .res file will be saved in TREC 6-column .res format"
```

The shell script `rblm.sh` takes the following command line inputs:
```
1. Number of expansion documents
2. Number of expansion terms
3. RM3 QueryMix ([img]http://bit.ly/18aJOnT[/img]) for calculating [img]http://www.sciweavers.org/tex2img.php?eq=%5Clambda%20P%28w%7CR%29%20%2B%20%281-%5Clambda%29P%28w%7CQ%29&bc=White&fc=Black&im=jpg&fs=12&ff=arev&edit=0[/img]
```
Run:
```
./rblm.sh
```
