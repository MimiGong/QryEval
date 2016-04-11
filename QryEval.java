/*
 *  Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 *  Version 3.1.1.
 */

import java.io.*;
import java.util.*;

import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.Version;

/**
 * QryEval is a simple application that reads queries from a file,
 * evaluates them against an index, and writes the results to an
 * output file.  This class contains the main method, a method for
 * reading parameter and query files, initialization methods, a simple
 * query parser, a simple query processor, and methods for reporting
 * results.
 * <p>
 * This software illustrates the architecture for the portion of a
 * search engine that evaluates queries.  It is a guide for class
 * homework assignments, so it emphasizes simplicity over efficiency.
 * Everything could be done more efficiently and elegantly.
 * <p>
 * The {@link Qry} hierarchy implements query evaluation using a
 * 'document at a time' (DaaT) methodology.  Initially it contains an
 * #OR operator for the unranked Boolean retrieval model and a #SYN
 * (synonym) operator for any retrieval model.  It is easily extended
 * to support additional query operators and retrieval models.  See
 * the {@link Qry} class for details.
 * <p>
 * The {@link RetrievalModel} hierarchy stores parameters and
 * information required by different retrieval models.  Retrieval
 * models that need these parameters (e.g., BM25 and Indri) use them
 * very frequently, so the RetrievalModel class emphasizes fast access.
 * <p>
 * The {@link Idx} hierarchy provides access to information in the
 * Lucene index.  It is intended to be simpler than accessing the
 * Lucene index directly.
 * <p>
 * As the search engine becomes more complex, it becomes useful to
 * have a standard approach to representing documents and scores.
 * The {@link ScoreList} class provides this capability.
 */
public class QryEval {

    //  --------------- Constants and variables ---------------------

    private static final String USAGE =
            "Usage:  java QryEval paramFile\n\n";

    private static final EnglishAnalyzerConfigurable ANALYZER =
            new EnglishAnalyzerConfigurable(Version.LUCENE_43);
    private static final String[] TEXT_FIELDS =
            {"body", "title", "url", "inlink"};

    private static PageRankScoreMap pagerankScoreMap;

    //  --------------- Methods ---------------------------------------

    /**
     * @param args The only argument is the parameter file name.
     * @throws Exception Error accessing the Lucene index.
     */
    public static void main(String[] args) throws Exception {

        //  This is a timer that you may find useful.  It is used here to
        //  time how long the entire program takes, but you can move it
        //  around to time specific parts of your code.

        Timer timer = new Timer();
        timer.start();

        //  Check that a parameter file is included, and that the required
        //  parameters are present.  Just store the parameters.  They get
        //  processed later during initialization of different system
        //  components.

        if (args.length < 1) {
            throw new IllegalArgumentException(USAGE);
        }

        Map<String, String> parameters = readParameterFile(args[0]);

        //  Configure query lexical processing to match index lexical
        //  processing.  Initialize the index and retrieval model.

        ANALYZER.setLowercase(true);
        ANALYZER.setStopwordRemoval(true);
        ANALYZER.setStemmer(EnglishAnalyzerConfigurable.StemmerType.KSTEM);

        Idx.initialize(parameters.get("indexPath"));
        RetrievalModel model = initializeRetrievalModel(parameters);

        // if exists initial ranking file, process it
        HashMap<String, ArrayList<WeightedDoc>> rankingResult = null;
        if(model instanceof RetrievalModelIndri &&
                parameters.containsKey("fb")) {
            boolean queryExpansion = Boolean.parseBoolean(parameters.get("fb"));
            if (queryExpansion) {
                if (parameters.containsKey("fbInitialRankingFile")) {
                    int fbDocs = Integer.parseInt(parameters.get("fbDocs"));
                    rankingResult = processRankingFile(parameters.get("fbInitialRankingFile"), fbDocs);
                }
            }
        }

        //  Perform experiments.
        if (model instanceof RetrievalModelLetor) {
            /* if use learning to rank, train on training data */
            /* get page rank map */
            pagerankScoreMap =
                    new PageRankScoreMap(parameters.get("letor:pageRankFile"));
            /* get disabled features */
            readDisableSet(parameters.get("letor:featureDisable"));
            /* get training queries */
            ArrayList<String> queryList = new ArrayList<>();
            ArrayList<Integer> qidList = new ArrayList<>();
            readTrainingQuery(parameters.get("letor:trainingQueryFile"), qidList, queryList);
            /* get relevance judge */
            HashMap<Integer, RelevantDocList> judgeMap = new HashMap<>();
            readTrainingJudgements(parameters.get("letor:trainingQrelsFile"), qidList, judgeMap);
            /* get features */
            FeatureExtractor extractor = new FeatureExtractor();
            RetrievalModelBM25 bm25Model = ((RetrievalModelLetor) model).getBM25Model();
            RetrievalModelIndri indriModel = ((RetrievalModelLetor) model).getIndriModel();
            for (int i = 0; i < queryList.size(); i++) {
                String[] queryTerms = tokenizeQuery(queryList.get(i));
                int qid = qidList.get(i);
                RelevantDocList docList = judgeMap.get(qid);
                extractor.extract(docList.getIds(), queryTerms, bm25Model, indriModel,
                        qid, pagerankScoreMap, docList.getRelevance());
            }
            String trainOutputPath = parameters.get("letor:trainingFeatureVectorsFile");
            extractor.printToFile(trainOutputPath);
            /* call svm rank to train the data */
            double letorC = Double.parseDouble(parameters.get("letor:svmRankParamC"));
            callSVMRankLearn(parameters.get("letor:svmRankLearnPath"), trainOutputPath,
                    letorC, parameters.get("letor:svmRankModelFile"));
            /* get initial ranking via BM25 model */
            String featureVectorOutput = parameters.get("letor:testingFeatureVectorsFile");
            FeatureExtractor testExtractor = new FeatureExtractor();
            processQueryFile(parameters.get("queryFilePath"),
                    featureVectorOutput, model, parameters, null, testExtractor);
            /* print to output */
            testExtractor.printToFile(featureVectorOutput);
            /* classify on initial ranking */
            String predictOutput = parameters.get("letor:testingDocumentScores");
            callSVMRankClassify(parameters.get("letor:svmRankClassifyPath"),
                    featureVectorOutput, parameters.get("letor:svmRankModelFile"),
                    predictOutput);
            /* read out SVM results */
            readPredictOutput(predictOutput, testExtractor);
            /* final output */
            for (int qid : testExtractor.qidList) {
                ArrayList<String> externalIds = new ArrayList<>();
                ArrayList<Double> scores = new ArrayList<>();
                testExtractor.getSortedDocsByQid(qid, externalIds, scores);
                printResults(qid, externalIds, scores, parameters.get("trecEvalOutputPath"));
            }
        }
        else {
            processQueryFile(parameters.get("queryFilePath"),
                    parameters.get("trecEvalOutputPath"), model,
                    parameters, rankingResult, null);
        }
        //  Clean up.
        timer.stop();
        System.out.println("Time:  " + timer);
    }

    private static void readDisableSet(String featureDisable) {
        if (featureDisable != null) {
            String[] features = featureDisable.split(",");
            Set<Integer> disableSet = new HashSet<>();
            for (String feature : features) {
                try{
                    Integer featureNum = Integer.parseInt(feature);
                    disableSet.add(featureNum);
                }
                catch (NumberFormatException e) {
                    e.printStackTrace();
                }
            }
            DocFeatures.setDisableSet(disableSet);
        }
    }

    private static void readPredictOutput(String filename, FeatureExtractor extractor) {
        try (BufferedReader infile =
                     new BufferedReader(new FileReader(filename))) {
            String line;
            int i = 0;
            while ((line = infile.readLine()) != null && line.length() > 0) {
                Double score = Double.parseDouble(line);
                extractor.setScore(i, score);
                i++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void callSVMRankLearn(String execPath, String qrelsFeatureOutputFile,
                                         double letorC, String modelOutputFile)
            throws Exception {
        callSVMRank(new String[] { execPath, "-c", String.valueOf(letorC), qrelsFeatureOutputFile,
                modelOutputFile });
    }

    private static void callSVMRankClassify(String execPath, String qrelsFeatureOutputFile,
                                         String modelOutputFile, String predictOuput)
            throws Exception {
        /* svm_rank_classify test.dat model.dat predictions */
        callSVMRank(new String[] { execPath, qrelsFeatureOutputFile, modelOutputFile,
                predictOuput });
    }

    private static void callSVMRank(String [] cmdArgs)
            throws Exception {
        // runs svm_rank_learn from within Java to train the model
        // execPath is the location of the svm_rank_learn utility,
        // which is specified by letor:svmRankLearnPath in the parameter file.
        // FEAT_GEN.c is the value of the letor:c parameter.
        Process cmdProc = Runtime.getRuntime().exec(cmdArgs);

        // The stdout/stderr consuming code MUST be included.
        // It prevents the OS from running out of output buffer space and stalling.

        // consume stdout and print it out for debugging purposes
        BufferedReader stdoutReader = new BufferedReader(
                new InputStreamReader(cmdProc.getInputStream()));
        String line;
        while ((line = stdoutReader.readLine()) != null) {
            System.out.println(line);
        }
        // consume stderr and print it for debugging purposes
        BufferedReader stderrReader = new BufferedReader(
                new InputStreamReader(cmdProc.getErrorStream()));
        while ((line = stderrReader.readLine()) != null) {
            System.out.println(line);
        }

        // get the return value from the executable. 0 means success, non-zero
        // indicates a problem
        int retValue = cmdProc.waitFor();
        if (retValue != 0) {
            throw new Exception("SVM Rank crashed.");
        }
    }

    private static void readTrainingQuery(String filename,
                                          ArrayList<Integer> qidList, ArrayList<String> queryList) {
        try (BufferedReader infile =
                     new BufferedReader(new FileReader(filename))) {
            String qLine;
            while ((qLine = infile.readLine()) != null && qLine.length() > 0) {
                int d = qLine.indexOf(':');
                if (d < 0) {
                    continue;
                }
                int qid = Integer.parseInt(qLine.substring(0, d));
                String query = qLine.substring(d + 1);
                qidList.add(qid);
                queryList.add(query);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void readTrainingJudgements(String filename, ArrayList<Integer> qidList,
                                          HashMap<Integer, RelevantDocList> judgeMap) {
        for (Integer qid : qidList) {
            judgeMap.put(qid, new RelevantDocList());
        }
        // format: 151 0 clueweb09-en0000-00-03430 0
        try (BufferedReader infile =
                     new BufferedReader(new FileReader(filename))) {
            String doc;
            while ((doc = infile.readLine()) != null && doc.length() > 0) {
                String[] entries = doc.split("\\s+");
                Integer qid = Integer.parseInt(entries[0]);
                String externalId = entries[2];
                Integer relevance = Integer.parseInt(entries[3]);
                judgeMap.get(qid).add(externalId, relevance);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Allocate the retrieval model and initialize it using parameters
     * from the parameter file.
     *
     * @return The initialized retrieval model
     * @throws IOException Error accessing the Lucene index.
     */
    private static RetrievalModel initializeRetrievalModel(Map<String, String> parameters)
            throws IOException {

        RetrievalModel model = null;
        String modelString = parameters.get("retrievalAlgorithm").toLowerCase();

        if (modelString.equalsIgnoreCase("unrankedboolean")) {
            model = new RetrievalModelUnrankedBoolean();
        } else if (modelString.equalsIgnoreCase("rankedboolean")) {
            model = new RetrievalModelRankedBoolean();
        } else if (modelString.equalsIgnoreCase("BM25")) {
            // parse model parameters
            double k1, k3;
            double b;
            k1 = Double.parseDouble(parameters.get("BM25:k_1").trim());
            k3 = Double.parseDouble(parameters.get("BM25:k_3").trim());
            b = Double.parseDouble(parameters.get("BM25:b").trim());
            // create model
            model = new RetrievalModelBM25(k1, b, k3);
        } else if (modelString.equalsIgnoreCase("Indri")) {
            // parse model parameters
            double mu;
            double lambda;
            mu = Double.parseDouble(parameters.get("Indri:mu").trim());
            lambda = Double.parseDouble(parameters.get("Indri:lambda").trim());
            // create model
            model = new RetrievalModelIndri(mu, lambda);
        } else if (modelString.equalsIgnoreCase("letor")) {
            // get corresponding BM25 model
            double k1, k3;
            double b;
            k1 = Double.parseDouble(parameters.get("BM25:k_1").trim());
            k3 = Double.parseDouble(parameters.get("BM25:k_3").trim());
            b = Double.parseDouble(parameters.get("BM25:b").trim());
            RetrievalModelBM25 BM25Model = new RetrievalModelBM25(k1, b, k3);
            // get corresponding indri model
            double mu;
            double lambda;
            mu = Double.parseDouble(parameters.get("Indri:mu").trim());
            lambda = Double.parseDouble(parameters.get("Indri:lambda").trim());
            RetrievalModelIndri indriModel = new RetrievalModelIndri(mu, lambda);
            // create letor model
            model = new RetrievalModelLetor(BM25Model, indriModel);
        } else {
            throw new IllegalArgumentException
                    ("Unknown retrieval model " + parameters.get("retrievalAlgorithm"));
        }

        return model;
    }

    /**
     * Optimize the query by removing degenerate nodes produced during
     * query parsing, for example '#NEAR/1 (of the)' which turns into
     * '#NEAR/1 ()' after stopwords are removed; and unnecessary nodes
     * or subtrees, such as #AND (#AND (a)), which can be replaced by 'a'.
     */
    static Qry optimizeQuery(Qry q) {

        //  Term operators don't benefit from optimization.

        if (q instanceof QryIopTerm) {
            return q;
        }

        //  Optimization is a depth-first task, so recurse on query
        //  arguments.  This is done in reverse to simplify deleting
        //  query arguments that become null.

        for (int i = q.args.size() - 1; i >= 0; i--) {

            Qry q_i_before = q.args.get(i);
            Qry q_i_after = optimizeQuery(q_i_before);

            if (q_i_after == null) {
                q.removeArg(i);            // optimization deleted the argument
            } else {
                if (q_i_before != q_i_after) {
                    q.args.set(i, q_i_after);    // optimization changed the argument
                }
            }
        }

        //  If the operator now has no arguments, it is deleted.

        if (q.args.size() == 0) {
            return null;
        }

        //  Only SCORE operators can have a single argument.  Other
        //  query operators that have just one argument are deleted.

        if ((q.args.size() == 1) &&
                (!(q instanceof QrySopScore))) {
            q = q.args.get(0);
        }

        return q;

    }

    /**
     * Return a query tree that corresponds to the query.
     *
     * @param qString A string containing a query.
     * @throws IOException Error accessing the Lucene index.
     */
    static Qry parseQuery(String qString, RetrievalModel model) throws IOException {

        //  Add a default query operator to every query. This is a tiny
        //  bit of inefficiency, but it allows other code to assume
        //  that the query will return document ids and scores.

        String defaultOp = model.defaultQrySopName();
        qString = defaultOp + "(" + qString + ")";

        //  Simple query tokenization.  Terms like "near-death" are handled later.

        StringTokenizer tokens = new StringTokenizer(qString, "\t\n\r ,()", true);
        String token = null;

        //  This is a simple, stack-based parser.  These variables record
        //  the parser's state.

        Qry currentOp = null;
        Stack<Qry> opStack = new Stack<Qry>();
        boolean weightExpected = false;
        Stack<Double> weightStack = new Stack<Double>();

        //  Each pass of the loop processes one token. The query operator
        //  on the top of the opStack is also stored in currentOp to
        //  make the code more readable.

        while (tokens.hasMoreTokens()) {

            token = tokens.nextToken();

            if (token.matches("[ ,(\t\n\r]")) {
                continue;
            } else if (token.equals(")")) {    // Finish current query op.

                // If the current query operator is not an argument to another
                // query operator (i.e., the opStack is empty when the current
                // query operator is removed), we're done (assuming correct
                // syntax - see below).

                opStack.pop();

                if (opStack.empty())
                    break;

                // Not done yet.  Add the current operator as an argument to
                // the higher-level operator, and shift processing back to the
                // higher-level operator.

                Qry arg = currentOp;
                currentOp = opStack.peek();
                currentOp.appendArg(arg);

            } else if (token.equalsIgnoreCase("#or")) {
                currentOp = new QrySopOr();
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
            } else if (token.equalsIgnoreCase("#and")) {
                currentOp = new QrySopAnd();
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
            } else if (token.equalsIgnoreCase("#sum")) {
                currentOp = new QrySopSum();
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
            } else if (token.equalsIgnoreCase("#wsum")) {
                currentOp = new QrySopWSum();
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
                weightExpected = true;
            } else if (token.equalsIgnoreCase("#wand")) {
                currentOp = new QrySopWAnd();
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
                weightExpected = true;
            } else if (token.equalsIgnoreCase("#syn")) {
                currentOp = new QryIopSyn();
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
            } else if (token.indexOf('/') >= 0) {
                String pair[] = token.trim().split("/");
                if(pair.length == 2) {
                    int n = Integer.parseInt(pair[1]);
                    if (pair[0].equalsIgnoreCase("#near")){
                        currentOp = new QryIopNear(n);
                        currentOp.setDisplayName(token);
                        opStack.push(currentOp);
                    }
                    else if (pair[0].equalsIgnoreCase("#window")) {
                        currentOp = new QryIopWindow(n);
                        currentOp.setDisplayName(token);
                        opStack.push(currentOp);
                    }
                    else {
                        throw new IllegalArgumentException
                                ("Error:  Query syntax is incorrect.  " + qString);
                    }
                }
                else {
                    throw new IllegalArgumentException
                            ("Error:  Query syntax is incorrect.  " + qString);
                }
            } else {
                // if it is a numeric, then it may be the weight
                if(weightExpected && model instanceof RetrievalModelIndri && token.matches("\\d*\\.?\\d+")) {
                    // weights come before terms
                    if (currentOp instanceof QrySopWSum) {
                        ((QrySopWSum) currentOp).addWeight(token);
                        weightExpected = false;
                        continue;
                    } else if (currentOp instanceof QrySopWAnd) {
                        ((QrySopWAnd) currentOp).addWeight(token);
                        weightExpected = false;
                        continue;
                    }
                }
                weightExpected = true;

                //  Split the token into a term and a field.
                int delimiter = token.indexOf('.');
                String field = null;
                String term = null;

                if (delimiter < 0) {
                    field = "body";
                    term = token;
                } else {
                    field = token.substring(delimiter + 1).toLowerCase();
                    term = token.substring(0, delimiter);
                }

                if ((field.compareTo("url") != 0) &&
                        (field.compareTo("keywords") != 0) &&
                        (field.compareTo("title") != 0) &&
                        (field.compareTo("body") != 0) &&
                        (field.compareTo("inlink") != 0)) {
                    throw new IllegalArgumentException("Error: Unknown field " + token);
                }

                //  Lexical processing, stopwords, stemming.  A loop is used
                //  just in case a term (e.g., "near-death") gets tokenized into
                //  multiple terms (e.g., "near" and "death").

                String t[] = tokenizeQuery(term);

                if(t.length == 0) {
                    if (currentOp instanceof QrySopWSum) {
                        ((QrySopWSum) currentOp).popWeight();
                        continue;
                    } else if (currentOp instanceof QrySopWAnd) {
                        ((QrySopWAnd) currentOp).popWeight();
                        continue;
                    }
                }

                for (int j = 0; j < t.length; j++) {

                    Qry termOp = new QryIopTerm(t[j], field);

                    currentOp.appendArg(termOp);
                }
            }
        }


        //  A broken structured query can leave unprocessed tokens on the opStack,

        if (tokens.hasMoreTokens()) {
            throw new IllegalArgumentException
                    ("Error:  Query syntax is incorrect.  " + qString);
        }

        return currentOp;
    }

    /**
     * Print a message indicating the amount of memory used. The caller
     * can indicate whether garbage collection should be performed,
     * which slows the program but reduces memory usage.
     *
     * @param gc If true, run the garbage collector before reporting.
     */
    public static void printMemoryUsage(boolean gc) {

        Runtime runtime = Runtime.getRuntime();

        if (gc)
            runtime.gc();

        System.out.println("Memory used:  "
                + ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)) + " MB");
    }

    /**
     * Process one query.
     *
     * @param qString A string that contains a query.
     * @param model   The retrieval model determines how matching and scoring is done.
     * @return Search results
     * @throws IOException Error accessing the index
     */
    static ScoreList processQuery(String qString, RetrievalModel model)
            throws IOException {

        Qry q = parseQuery(qString, model);
        q = optimizeQuery(q);

        // Show the query that is evaluated

        System.out.println("    --> " + q);

        if (q != null) {

            ScoreList r = new ScoreList();

            if (q.args.size() > 0) {        // Ignore empty queries

                q.initialize(model);

                while (q.docIteratorHasMatch(model)) {
                    int docid = q.docIteratorGetMatch();
                    double score = ((QrySop) q).getScore(model);
                    r.add(docid, score);
                    q.docIteratorAdvancePast(docid);
                }
            }

            return r;
        } else
            return null;
    }

    /**
     * Load from input file and return internal doc id list
     *
     * @param fbInitialRankingFile initial ranking file path
     * @param fbDocs number of top docs
     */
    static HashMap<String, ArrayList<WeightedDoc>> processRankingFile(
            String fbInitialRankingFile, int fbDocs) throws IOException {
        HashMap<String, ArrayList<WeightedDoc>> weightedDocsList = new HashMap<>();
        try (BufferedReader infile
                     = new BufferedReader(new FileReader(fbInitialRankingFile))) {
            String doc;
            while ((doc = infile.readLine()) != null && doc.length() > 0) {
                // split on any whitespace chars
                String[] entries = doc.split("\\s+");
                String qid = entries[0];
                ArrayList<WeightedDoc> weightedDocs = weightedDocsList.get(qid);
                if(weightedDocs == null || weightedDocs.size() < fbDocs) {
                    String externalId = entries[2];
                    double score = Double.parseDouble(entries[4]);
                    int internalId = Idx.getInternalDocid(externalId);
                    WeightedDoc newDoc = new WeightedDoc(internalId, score);
                    if(weightedDocs == null) {
                        weightedDocs = new ArrayList<>();
                        weightedDocsList.put(qid, weightedDocs);
                    }
                    weightedDocs.add(newDoc);
                }
            }
            infile.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return weightedDocsList;
    }

    /**
     * Load from input file and return internal doc id list
     *
     * @param scoreList initial query score list
     * @param fbDocs number of top docs
     */
    static ArrayList<WeightedDoc> getTopDocs(ScoreList scoreList, int fbDocs) {
        ArrayList<WeightedDoc> weightedDocs = new ArrayList<>();
        for (int i = 0; i < fbDocs; i++) {
            int internalId = scoreList.getDocid(i);
            double score = scoreList.getDocidScore(i);
            WeightedDoc newDoc = new WeightedDoc(internalId, score);
            weightedDocs.add(newDoc);
        }
        return weightedDocs;
    }

    /**
     * expand query on top documents
     *
     * @param topDocs top documents
     * @param fbTerms number of terms retrieved
     * @param fbMu the amount of smoothing used to calculate p(r|d)
     */
    static String expandQuery(ArrayList<WeightedDoc> topDocs,
                              int fbTerms, double fbMu)
            throws IOException {
        CharSequence period = ".";
        CharSequence comma = ",";
        HashMap<String, Double> termScoreMap = new HashMap<>();

        double overallDocDependentScore = 0.0;
        for (WeightedDoc doc : topDocs) {
            double doclen = Idx.getFieldLength("body", doc.docId);
            overallDocDependentScore += 1.0 / (doclen + fbMu) * doc.score;
        }

        for (WeightedDoc doc : topDocs) {
            int docId = doc.docId;
            TermVector termVector = new TermVector(docId, "body");
            double score = doc.score;
            double sumDocLen = Idx.getSumOfFieldLengths("body");
            double doclen = Idx.getFieldLength("body", docId);
            // stems is the vocabulary. 0 indicates a stopword
            for (int i = 1; i < termVector.stemsLength(); i++) {
                String newTerm = termVector.stemString(i);
                // skip any term that contain . or , to avoid confusion
                if(newTerm.contains(period) || newTerm.contains(comma))
                    continue;
                double tf = termVector.stemFreq(i);
                double ctf = termVector.totalStemFreq(i);
                double tUnderC = ctf / sumDocLen;
                double termScore = (tf + fbMu * tUnderC) / (doclen + fbMu) * score
                        * Math.log(1.0 / tUnderC);
                double defaultScore = (fbMu * tUnderC) / (doclen + fbMu) * score
                        * Math.log(1.0 / tUnderC);
                if(!termScoreMap.containsKey(newTerm)) {
                    // base score is the score if all document don't contains a term
                    double baseScore = (fbMu * tUnderC) * overallDocDependentScore
                            * Math.log(1.0 / tUnderC);
                    termScoreMap.put(newTerm, termScore + baseScore - defaultScore);
                }
                else
                    termScoreMap.put(newTerm, termScore + termScoreMap.get(newTerm) - defaultScore);
            }
        }
        // sort terms by scores
        List<Map.Entry<String,Double>> list = new ArrayList<>(termScoreMap.entrySet());
        // sort by values descending order
        Collections.sort(list, (o1, o2) -> (o2.getValue()
                .compareTo(o1.getValue())));
        // construct expanedQuery
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("#wand(");
        for (int i = 0; i < fbTerms; i++) {
            Map.Entry<String,Double> entry = list.get(i);
            queryBuilder.append(String.format("%.4f %s ", entry.getValue(), entry.getKey()));
        }
        queryBuilder.append(")");
        return queryBuilder.toString();
    }

    /**
     * Process the query file and output results.
     *
     * @param queryFilePath
     * @param outputFilePath
     * @param model
     * @param parameters   Parameters specified in the parameters file
     * @param rankingResult preprocessed ranking results
     * @throws IOException Error accessing the Lucene index.
     */
    static void processQueryFile(String queryFilePath,
                                 String outputFilePath,
                                 RetrievalModel model,
                                 Map<String, String> parameters,
                                 HashMap<String, ArrayList<WeightedDoc>> rankingResult,
                                 FeatureExtractor extractor)
            throws IOException {
        try (BufferedReader input =
                     new BufferedReader(new FileReader(queryFilePath))) {
            String qLine;
            //  Each pass of the loop processes one query.
            while ((qLine = input.readLine()) != null) {
                int d = qLine.indexOf(':');

                if (d < 0) {
                    throw new IllegalArgumentException
                            ("Syntax error:  Missing ':' in query line.");
                }

                printMemoryUsage(false);

                String qid = qLine.substring(0, d);
                String query = qLine.substring(d + 1);

                System.out.println("Query " + qLine);

                // query expansion
                if (model instanceof RetrievalModelIndri &&
                        parameters.containsKey("fb")) {
                    boolean queryExpansion = Boolean.parseBoolean(parameters.get("fb"));
                    if (queryExpansion) {
                        ArrayList<WeightedDoc> topDocs;
                        int fbTerms = Integer.parseInt(parameters.get("fbTerms"));
                        double fbMu = Double.parseDouble(parameters.get("fbMu"));
                        double fbOriginWeight = Double.parseDouble(parameters.get("fbOrigWeight"));
                        String fbOutputPath = parameters.get("fbExpansionQueryFile");
                        if (rankingResult != null) {
                            topDocs = rankingResult.get(qid);
                        } else {
                            int fbDocs = Integer.parseInt(parameters.get("fbDocs"));
                            ScoreList initialResult = processQuery(query, model);
                            initialResult.sort();
                            topDocs = getTopDocs(initialResult, fbDocs);
                        }
                        // set the query to expanded query
                        String expandedQuery = expandQuery(topDocs, fbTerms, fbMu);
                        query = String.format("#wand(%.4f #and(%s) %.4f %s)", fbOriginWeight, query,
                                (1.0 - fbOriginWeight), expandedQuery);
                        System.out.println(query);
                        printQuery(qid, expandedQuery, fbOutputPath);
                    }
                }

                if (model instanceof RetrievalModelLetor) {
                    RetrievalModelBM25 bm25Model = ((RetrievalModelLetor) model).getBM25Model();
                    RetrievalModelIndri indriModel = ((RetrievalModelLetor) model).getIndriModel();
                    String[] queryTerms = tokenizeQuery(query);
                    ScoreList r = processQuery(query, bm25Model);
                    ArrayList<String> externalIds = getExternalIds(r, 100);
                    Integer qidInt = Integer.parseInt(qid);
                    extractor.extract(externalIds, queryTerms, bm25Model, indriModel,
                            qidInt, pagerankScoreMap, null);
                }
                else {
                    // process one query
                    ScoreList r = processQuery(query, model);
                    if (r != null) {
                        // output result to file
                        printResults(qid, r, outputFilePath);
                    }
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    static ArrayList<String> getExternalIds(ScoreList result, int num)
            throws IOException {
        ArrayList<String> externalIds = new ArrayList<>();
        // print best 100 results
        int endIndex = Math.min(num, result.size());
        result.sort();
        for (int i = 0; i < endIndex; i++) {
            externalIds.add(Idx.getExternalDocid(result.getDocid(i)));
        }
        return externalIds;
    }

    /**
     * Print the query results.
     *
     * @param queryName query id.
     * @param query    query to write
     * @param outputFilePath Output file's path
     * @throws IOException Error accessing the Lucene index.
     */
    static void printQuery(String queryName, String query, String outputFilePath)
            throws IOException {
        // write to file
        FileWriter fw = null;
        try {
            fw = new FileWriter(outputFilePath, true); //the true will append the new data
            fw.write(String.format("%s: %s\n", queryName, query)); //appends the string to the file
        } catch (IOException ioe) {
            System.err.println("IOException: " + ioe.getMessage());
        } finally {
            if (fw != null)
                fw.close();
        }
    }

    /**
     * Print the expanded query
     *
     * @param queryName Original query.
     * @param result    A list of document ids and scores
     * @param outputFilePath Output file's path
     * @throws IOException Error accessing the Lucene index.
     */
    static void printResults(String queryName, ScoreList result, String outputFilePath)
            throws IOException {
        // Debug info
        System.out.println(queryName + ":  ");
        StringBuilder stringBuilder = new StringBuilder();
        if (result.size() < 1) {
            // no results, print a dummy
            stringBuilder.append(String.format("%s\t%s\t%s\t%d\t%g\t%s\n",
                    queryName, "Q0", "dummy", 1, 0., "RunID"));
        } else {
            result.sort(); // sort first by score, then by doc id
            // print best 100 results
            int endIndex = Math.min(100, result.size());
            for (int i = 0; i < endIndex; i++) {
                stringBuilder.append(String.format("%s\t%s\t%s\t%d\t%g\t%s\n",
                        queryName, "Q0", Idx.getExternalDocid(result.getDocid(i)),
                        i + 1, result.getDocidScore(i), "RunID"));
            }
        }
        // write to file
        FileWriter fw = null;
        try {
            fw = new FileWriter(outputFilePath, true); //the true will append the new data
            fw.write(stringBuilder.toString());//appends the string to the file
        } catch (IOException ioe) {
            System.err.println("IOException: " + ioe.getMessage());
        } finally {
            if (fw != null)
                fw.close();
        }
    }

    static void printResults(int queryName, ArrayList<String> externalIds,
                             ArrayList<Double> results, String outputFilePath)
            throws IOException {
        // Debug info
        StringBuilder stringBuilder = new StringBuilder();
        if (results.size() < 1) {
            // no results, print a dummy
            stringBuilder.append(String.format("%d\t%s\t%s\t%d\t%g\t%s\n",
                    queryName, "Q0", "dummy", 1, 0., "RunID"));
        } else {
            int endIndex = Math.min(100, results.size());
            for (int i = 0; i < endIndex; i++) {
                stringBuilder.append(String.format("%d\t%s\t%s\t%d\t%.12f\t%s\n",
                        queryName, "Q0", externalIds.get(i),
                        i + 1, results.get(i), "RunID"));
            }
        }
        // write to file
        try (FileWriter fw = new FileWriter(outputFilePath, true)) {
             //the true will append the new data
            fw.write(stringBuilder.toString());//appends the string to the file
        } catch (IOException ioe) {
            System.err.println("IOException: " + ioe.getMessage());
        }
    }

    /**
     * Read the specified parameter file, and confirm that the required
     * parameters are present.  The parameters are returned in a
     * HashMap.  The caller (or its minions) are responsible for
     * processing them.
     *
     * @return The parameters, in <key, value> format.
     */
    private static Map<String, String> readParameterFile(String parameterFileName)
            throws IOException {

        Map<String, String> parameters = new HashMap<String, String>();

        File parameterFile = new File(parameterFileName);

        if (!parameterFile.canRead()) {
            throw new IllegalArgumentException
                    ("Can't read " + parameterFileName);
        }

        Scanner scan = new Scanner(parameterFile);
        String line = null;
        do {
            line = scan.nextLine();
            String[] pair = line.split("=");
            parameters.put(pair[0].trim(), pair[1].trim());
        } while (scan.hasNext());

        scan.close();

        if (!(parameters.containsKey("indexPath") &&
                parameters.containsKey("queryFilePath") &&
                parameters.containsKey("trecEvalOutputPath") &&
                parameters.containsKey("retrievalAlgorithm"))) {
            throw new IllegalArgumentException
                    ("Required parameters were missing from the parameter file.");
        }

        return parameters;
    }

    /**
     * Given a query string, returns the terms one at a time with stopwords
     * removed and the terms stemmed using the Krovetz stemmer.
     * <p>
     * Use this method to process raw query terms.
     *
     * @param query String containing query
     * @return Array of query tokens
     * @throws IOException Error accessing the Lucene index.
     */
    static String[] tokenizeQuery(String query) throws IOException {

        TokenStreamComponents comp =
                ANALYZER.createComponents("dummy", new StringReader(query));
        TokenStream tokenStream = comp.getTokenStream();

        CharTermAttribute charTermAttribute =
                tokenStream.addAttribute(CharTermAttribute.class);
        tokenStream.reset();

        List<String> tokens = new ArrayList<String>();

        while (tokenStream.incrementToken()) {
            String term = charTermAttribute.toString();
            tokens.add(term);
        }

        return tokens.toArray(new String[tokens.size()]);
    }

}
