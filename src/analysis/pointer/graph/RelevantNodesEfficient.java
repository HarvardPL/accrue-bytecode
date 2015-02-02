package analysis.pointer.graph;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import util.OrderedPair;
import util.intmap.ConcurrentIntMap;
import util.intmap.IntMap;
import util.intmap.SparseIntMap;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.graph.AddToSetOriginMaker.AddToSetOrigin;
import analysis.pointer.graph.RelevantNodesIncremental.CGEdgeType;
import analysis.pointer.graph.RelevantNodesIncremental.RelevantNodesQuery;
import analysis.pointer.graph.RelevantNodesIncremental.SourceRelevantNodesQuery;
import analysis.pointer.graph.RelevantNodesIncremental.WorkItem;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

public final class RelevantNodesEfficient {

    /**
     * Print a ton for each run, such as the elements pulled off the queue
     */
    public static boolean DEBUG = false;
    /**
     * Print diagnostic information about timing and numbers intermittently, this shouldn't affect performance too much
     */
    private boolean PRINT_DIAGNOSTICS = false;
    private static boolean CHECK_OLD = false;

    private final PointsToGraph g;
    private final ProgramPointReachability programPointReachability;
    static final AtomicInteger calleesProcessed = new AtomicInteger(0);
    private final RelevantNodesIncremental oldRelevantComputation;

    /**
     * A reference to allow us to submit a query for reprocessing
     */
    private final PointsToAnalysisHandle analysisHandle;

    /**
     * Cache containing the results of computing the relevant nodes for a given source and destination
     */
    private ConcurrentMap<RelevantNodesQuery, /*Set<OrderedPair<IMethod, Context>>*/MutableIntSet> relevantCache = AnalysisUtil.createConcurrentHashMap();

    /**
     * Cache of results containing the results of finding dependencies starting at a particular call graph node
     */
    private ConcurrentMap<SourceRelevantNodesQuery, SourceQueryResults> sourceQueryCache = AnalysisUtil.createConcurrentHashMap();

    /**
     * Map from a source node query CGNode to the items to use as initial workqueue items the next time that source
     * query is run
     */
    private final ConcurrentMap<SourceRelevantNodesQuery, Set<WorkItem>> startItems = AnalysisUtil.createConcurrentHashMap();

    /**
     * Algorithm for computing which nodes are relevant to a given program point reachability query. Relevant nodes must
     * be searched deeply as a path from the source to destination may enter or leave in the middle of a relevant node
     * (via a call or return).
     *
     * @param g points-to graph
     * @param analysisHandle handle for submitting queries to the points-to analysis
     * @param programPointReachability reachability query from one program point to another
     */
    RelevantNodesEfficient(PointsToGraph g, final PointsToAnalysisHandle analysisHandle,
                             ProgramPointReachability programPointReachability) {
        this.g = g;
        this.analysisHandle = analysisHandle;
        this.programPointReachability = programPointReachability;
        PointsToAnalysisHandle nonHandle = new PointsToAnalysisHandle() {

            @Override
            public void submitStmtAndContext(StmtAndContext sac, GraphDelta delta) {
                // Auto-generated method stub
            }

            @Override
            public void submitStmtAndContext(StmtAndContext sac) {
                // Auto-generated method stub
            }

            @Override
            public void submitSourceRelevantNodesQuery(SourceRelevantNodesQuery source) {
                // Auto-generated method stub
            }

            @Override
            public void submitRelevantNodesQuery(RelevantNodesQuery rq) {
                // Auto-generated method stub
            }

            @Override
            public void submitReachabilityQuery(ProgramPointSubQuery mr) {
                // Auto-generated method stub
            }

            @Override
            public void submitAddToSetTask(AddToSetOrigin task) {
                // Auto-generated method stub
            }

            @Override
            public void submitAddNonMostRecentTask(AddNonMostRecentOrigin task) {
                // Auto-generated method stub
            }

            @Override
            public PointsToGraph pointsToGraph() {
                // Auto-generated method stub
                return null;
            }

            @Override
            public int numThreads() {
                // Auto-generated method stub
                return analysisHandle.numThreads();
            }

            @Override
            public void handleChanges(GraphDelta delta) {
                // Auto-generated method stub
            }
        };
        this.oldRelevantComputation = new RelevantNodesIncremental(g, nonHandle, programPointReachability);
        //        oldRelevantComputation.DEBUG = true;
    }

    /**
     * Get the relevant nodes for a given source and destination pair. This is being requested for the given program
     * point query.
     *
     * @param source source call graph node
     * @param dest destination call graph node
     * @param query query requesting the relevant nodes
     *
     * @return set of nodes that must be searched deeply when executing the program point query
     */
    /*Set<OrderedPair<IMethod, Context>>*/IntSet relevantNodes(/*OrderedPair<IMethod, Context>*/int source,
    /*OrderedPair<IMethod, Context>*/int dest, /*ProgramPointSubQuery*/int query) {
        RelevantNodesQuery relevantQuery = new RelevantNodesQuery(source, dest);

        // add a dependency
        addRelevantNodesDependency(query, relevantQuery);

        // check the cache.
        IntSet relevantNodes = relevantCache.get(relevantQuery);

        if (relevantNodes == null) {
            if (DEBUG) {
                System.err.println("RNI%% COMPUTE RELEVANT needed");
            }

            relevantNodes = computeRelevantNodes(relevantQuery);
        }
        else {
            this.totalRequests.incrementAndGet();
            this.cachedResponses.incrementAndGet();
        }
        assert relevantNodes != null;
        return relevantNodes;
    }

    /**
     * For a query starting at a given call graph node, compute the relevant node dependencies. These dependencies
     * indicate which call graph nodes become relevant whan another node becomes relevant
     *
     * @param sourceCGNode call graph node containing the source program point
     * @return Dependencies, so that if cg node a is in the set relevanceDependencies.get(b), then if b becomes a
     *         relevant node, then a is also a relevant node. We can think of this relevanceDependencies as describing
     *         the edges in the CFG that are reachable on a (valid) path from the source cg node.
     */
    SourceQueryResults computeSourceDependencies(SourceRelevantNodesQuery query) {
        /*OrderedPair<IMethod, Context>*/int sourceCGNode = query.sourceCGNode;
        if (DEBUG) {
            System.err.println("RNI%% SOURCE QUERY for " + g.lookupCallGraphNodeDictionary(sourceCGNode));
        }

        totalSourceRequests.incrementAndGet();
        computedSourceResponses.incrementAndGet();
        if (sourceQueryCache.get(query) != null) {
            // this is a recomputation due to a dependency trigger
            recomputedSourceResponses.incrementAndGet();
        }

        // Start from the previous results
        SourceQueryResults previous = sourceQueryCache.get(query);

        if (previous == null) {
            // No previous results initialize the visited and relevant sets
            previous = new SourceQueryResults(AnalysisUtil.<MutableIntSet> createConcurrentIntMap(),
                                              AnalysisUtil.<WorkItem> createConcurrentSet(),
                                              AnalysisUtil.createConcurrentIntSet());
            SourceQueryResults existing = sourceQueryCache.putIfAbsent(query, previous);
            if (existing != null) {
                // someone beat us!
                previous = existing;
            }
        }
        boolean resultsChanged = false;

        /*
         * We maintain dependencies, so that if cg node a is in the set relevanceDependencies.get(b),
         * then if b becomes a relevant node, then a is also a relevant node. We can think of this
         * relevanceDependencies as describing the edges in the CFG that are reachable on a (valid)
         * path from the source cg node.
         */

        /*Map<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>>*/
        ConcurrentIntMap<MutableIntSet> relevanceDependencies = previous.relevanceDependencies;
        Set<WorkItem> allVisited = previous.alreadyVisited;
        MutableIntSet callees = previous.callees;

        Deque<WorkItem> q = new ArrayDeque<>();
        // Atomically pull out the set of new items to be processed and replace it with an empty set
        Set<WorkItem> initial = this.startItems.replace(query, AnalysisUtil.<WorkItem> createConcurrentSet());
        assert initial != null : "Null start items for " + query;
        if (DEBUG) {
            System.err.println("RNI%% INITIAL");
            for (WorkItem wi : initial) {
                System.err.println("RNI%%\t" + wi.toString(g));
            }
        }

        // Add all the initial items to the work queue
        q.addAll(initial);

        if (q.isEmpty()) {
            // There are no new items to process the previous results will suffice
            cachedSourceResponses.incrementAndGet();
            if (DEBUG) {
                System.err.println("RNI%%\tUSING PREVIOUS");
            }
            return previous;
        }
        long start = System.currentTimeMillis();
        int count = 0;

        while (!q.isEmpty()) {
            WorkItem p = q.poll();
            if (DEBUG) {
                System.err.println("RNI%%\tQ " + p.toString(g));
            }
            count++;
            if (count % 100000 == 0) {
                // Detect possible infinite loop
                System.err.println("Searching relevant from " + sourceCGNode);
                System.err.println("Processed " + count + " work items latest: " + p);
            }
            /*OrderedPair<IMethod, Context>*/int cgNode = p.cgNode;
            switch (p.type) {
            case CALLERS:
                // explore the callers of this cg node
                this.addSourceQueryCallerDependency(query, cgNode);
                /*Iterator<ProgramPointReplica>*/IntIterator callers = g.getCallersOf(cgNode).intIterator();
                while (callers.hasNext()) {
                    /*ProgramPointReplica*/int callerInt = callers.next();
                    /*OrderedPair<IMethod, Context>*/int callerCGNode = getCGNodeForCallSiteReplica(callerInt);

                    // if callerCGNode becomes relevant in the future, then cgNode will also be relevant.
                    resultsChanged |= addToMapSet(relevanceDependencies, callerCGNode, cgNode);
                    if (DEBUG) {
                        System.err.println("RNI%%\t\t" + g.lookupCallGraphNodeDictionary(cgNode));
                        System.err.println("RNI%%\t\tDEPENDS ON " + g.lookupCallGraphNodeDictionary(callerCGNode));
                    }

                    // since we are exploring the callers of cgNode, for each caller of cgNode, callerCGNode,
                    // we want to visit both the callers and the callees of callerCGNode.
                    WorkItem callersWorkItem = new WorkItem(callerCGNode, CGEdgeType.CALLERS);
                    if (allVisited.add(callersWorkItem)) {
                        q.add(callersWorkItem);
                    }

                    // Only add the callees in the caller that are after the return site
                    ProgramPointReplica caller = g.lookupCallSiteReplicaDictionary(callerInt);
                    Set<CallSiteProgramPoint> after = getCallsitesAfter((CallSiteProgramPoint) caller.getPP());
                    for (CallSiteProgramPoint cspp : after) {
                        WorkItem singleCalleeWorkItem = new WorkItem(cspp, callerCGNode);
                        if (allVisited.add(singleCalleeWorkItem)) {
                            q.add(singleCalleeWorkItem);
                        }
                    }
                    //                    WorkItem calleesWorkItem = new WorkItem(callerCGNode, CGEdgeType.CALLEES);
                    //                    if (allVisited.add(calleesWorkItem)) {
                    //                        q.add(calleesWorkItem);
                    //                    }
                }
                break;
            case CALLEES:
                resultsChanged |= callees.add(cgNode);
                //                // explore the callees of this cg node
                //                /*Iterator<ProgramPointReplica>*/IntIterator callSites = g.getCallSitesWithinMethod(cgNode)
                //                                                                           .intIterator();
                //                while (callSites.hasNext()) {
                //                    /*ProgramPointReplica*/int callSiteInt = callSites.next();
                //                    this.addSourceQueryCalleeDependency(query, callSiteInt);
                //
                //                    /*Iterator<OrderedPair<IMethod, Context>>*/IntIterator calleeIter = g.getCalleesOf(callSiteInt)
                //                                                                                          .intIterator();
                //                    while (calleeIter.hasNext()) {
                //                        /*OrderedPair<IMethod, Context>*/int callee = calleeIter.next();
                //                        // if callee becomes relevant in the future, then cgNode will also be relevant.
                //                        resultsChanged |= addToMapSet(relevanceDependencies, callee, cgNode);
                //                        calleesProcessed.incrementAndGet();
                //                        if (DEBUG) {
                //                            System.err.println("RNI%%\t\t" + g.lookupCallGraphNodeDictionary(cgNode));
                //                            System.err.println("RNI%%\t\tDEPENDS ON " + g.lookupCallGraphNodeDictionary(callee));
                //                        }
                //
                //                        // We are exploring only the callees of cgNode, so when we explore callee
                //                        // we only need to explore its callees (not its callers).
                //                        WorkItem calleeWorkItem = new WorkItem(callee, CGEdgeType.CALLEES);
                //                        if (allVisited.add(calleeWorkItem)) {
                //                            q.add(calleeWorkItem);
                //                        }
                //                    }
                //                }
                break;
            case PRECISE_CALLEE:
                // This is a precise call site add callees for any possible targets to the queue
                assert p.callSite != null;
                Context c = g.lookupCallGraphNodeDictionary(p.cgNode).snd();
                /*ProgramPointReplica*/int callSiteReplica = g.lookupCallSiteReplicaDictionary(p.callSite.getReplica(c));
                this.addSourceQueryCalleeDependency(query, callSiteReplica);
                /*Iterator<OrderedPair<IMethod, Context>>*/IntIterator calleeIter = g.getCalleesOf(callSiteReplica)
                                                                                      .intIterator();
                while (calleeIter.hasNext()) {
                    int callee = calleeIter.next();
                    // if callee becomes relevant in the future, then cgNode will also be relevant.
                    resultsChanged |= addToMapSet(relevanceDependencies, callee, cgNode);
                    calleesProcessed.incrementAndGet();
                    if (DEBUG) {
                        System.err.println("RNI%%\t\t" + g.lookupCallGraphNodeDictionary(cgNode));
                        System.err.println("RNI%%\t\tDEPENDS ON " + g.lookupCallGraphNodeDictionary(callee));
                    }

                    // We are exploring only the callees of the call-site, so when we explore callee
                    // we only need to explore its callees (not its callers).
                    if (allVisited.add(new WorkItem(callee, CGEdgeType.CALLEES))) {
                        q.add(new WorkItem(callee, CGEdgeType.CALLEES));
                    }
                }
                break;
            } // end handling a single work item
        } // Queue is empty

        // Record the results and rerun any dependencies
        SourceQueryResults sqr = new SourceQueryResults(relevanceDependencies, allVisited, callees);
        if (resultsChanged) {
            recordSourceQueryResultsChanged(query);
        }
        totalSourceTime.addAndGet(System.currentTimeMillis() - start);

        return sqr;
    }

    /**
     * Get the call graph node (method and context) containing a given call site replica (program point and context)
     *
     * @param callSite call site replica
     * @return call graph node
     */
    private/*OrderedPair<IMethod, Context>*/int getCGNodeForCallSiteReplica(/*ProgramPointReplica*/int callSite) {
        ProgramPointReplica callSiteRep = g.lookupCallSiteReplicaDictionary(callSite);
        OrderedPair<IMethod, Context> cgNode = new OrderedPair<>(callSiteRep.getPP().containingProcedure(),
                                                                 callSiteRep.getContext());
        return g.lookupCallGraphNodeDictionary(cgNode);
    }

    /**
     * Record the results of running a query to find to relevant nodes for queries from a source call graph node to a
     * destination call graph node
     *
     * @param relevantQuery query to find relevant nodes
     * @param results call graph nodes that are relevant to queries from a program point in the source to a program
     *            point in the destination
     */
    private void recordRelevantNodesResults(RelevantNodesQuery relevantQuery, /*Set<OrderedPair<IMethod, Context>>*/
                                            MutableIntSet results) {
        long start = System.currentTimeMillis();
        MutableIntSet s = relevantCache.get(relevantQuery);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = relevantCache.putIfAbsent(relevantQuery, s);
            if (existing != null) {
                // someone beat us to recording the result.
                s = existing;
            }
        }

        if (s.addAll(results)) {
            // rerun queries that depend on the results of the relevant nodes query
            MutableIntSet deps = relevantNodesDependencies.get(relevantQuery);
            if (deps == null) {
                return;
            }

            IntIterator iter = deps.intIterator();
            MutableIntSet toRemove = MutableSparseIntSet.createMutableSparseIntSet(2);
            while (iter.hasNext()) {
                int mr = iter.next();
                this.programPointReachability.relevantRequests.incrementAndGet();
                // need to re-run the query
                if (!this.programPointReachability.requestRerunQuery(mr)) {
                    // no need to rerun this anymore, it was true
                    toRemove.add(mr);
                }
            }

            // Now remove all the unneeded queries
            IntIterator removeIter = toRemove.intIterator();
            while (removeIter.hasNext()) {
                deps.remove(removeIter.next());
            }
        }
        recordRelevantTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * Record the fact that the results of the query depends on the relevant nodes between the source and dest
     *
     * @param query query that depends on the relevant nodes
     * @param relevantQuery query for relevant nodes from a source to a destination
     */
    private void addRelevantNodesDependency(/*ProgramPointSubQuery*/int query, RelevantNodesQuery relevantQuery) {
        long start = System.currentTimeMillis();

        MutableIntSet s = relevantNodesDependencies.get(relevantQuery);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = relevantNodesDependencies.putIfAbsent(relevantQuery, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
        queryDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * This method is invoked to let us know that a new edge in the call graph has been added from the callSite.
     *
     * We use this method to make sure that we re-run any source queries that depended on the call-site
     *
     * @param callSite call-site there was a callee added to
     */
    void calleeAddedTo(/*ProgramPointReplica*/int callSite) {
        Set<SourceRelevantNodesQuery> queries = sourceQueryCalleeDependencies.get(callSite);
        if (queries != null) {
            // New work item that needs to be processed when the query is rerun
            CallSiteProgramPoint cspp = (CallSiteProgramPoint) g.lookupCallSiteReplicaDictionary(callSite).getPP();
            Set<WorkItem> newItem = Collections.singleton(new WorkItem(cspp, getCGNodeForCallSiteReplica(callSite)));
            for (SourceRelevantNodesQuery query : queries) {
                addStartItems(query, newItem);

                // Make sure the query gets rerun by the analysis if it doesn't get triggered earlier
                this.analysisHandle.submitSourceRelevantNodesQuery(query);
            }
        }

        // Rerun source queries that depend on the callees of the call graph node containing this call site
        /*OrderedPair<IMethod, Context>*/int cgNode = getCGNodeForCallSiteReplica(callSite);
        Set<RelevantNodesQuery> calleeQueries = relevantQueryCallGraphNodeCalleeDependencies.get(cgNode);
        if (calleeQueries != null) {
            for (RelevantNodesQuery calleeQuery : calleeQueries) {
                // Make sure the query gets rerun since one of the callees changed
                this.analysisHandle.submitRelevantNodesQuery(calleeQuery);
            }
        }
    }

    /**
     * This method is invoked to let us know that a new edge in the call graph has been added that goes to callGraphNode
     * (an added caller of that node)
     *
     * We use this method to make sure that we re-run any source queries that depended on the call graph node.
     *
     * @param callGraphNode call graph node that has another caller
     */
    void callerAddedTo(/*OrderedPair<IMethod, Context>*/int calleeCallGraphNode) {
        Set<SourceRelevantNodesQuery> queries = sourceQueryCallerDependencies.get(calleeCallGraphNode);
        if (queries != null) {
            // New work item that needs to be proccessed when the query is rerun
            Set<WorkItem> newItem = Collections.singleton(new WorkItem(calleeCallGraphNode, CGEdgeType.CALLERS));
            for (SourceRelevantNodesQuery query : queries) {
                addStartItems(query, newItem);

                // Make sure the query gets rerun by the analysis if it doesn't get triggered earlier
                this.analysisHandle.submitSourceRelevantNodesQuery(query);
            }
        }
    }

    /**
     * Add work items to the initial queue for a source query
     *
     * @param query start node of the source query
     * @param startItems items to add to the initial queue
     */
    private void addStartItems(SourceRelevantNodesQuery query, Set<WorkItem> newItems) {
        long start = System.currentTimeMillis();

        Set<WorkItem> s;
        do {
            s = startItems.get(query);
            if (s == null) {
                s = AnalysisUtil.createConcurrentSet();
                Set<WorkItem> existing = startItems.putIfAbsent(query, s);
                if (existing != null) {
                    s = existing;
                }
            }
            s.addAll(newItems);
        } while (s != startItems.get(query));
        startItemTime.addAndGet(System.currentTimeMillis() - start);
        oldRelevantComputation.addStartItems(query, newItems);
    }

    //    /**
    //     * Work queue element of work, indicates which call graph edges to process next
    //     */
    //    private static class WorkItem {
    //        final CGEdgeType type;
    //        final/*OrderedPair<IMethod, Context>*/int cgNode;
    //        final CallSiteProgramPoint callSite;
    //
    //        /**
    //         * Create a callers or callees work item that will process all edges entering or leaving a call graph node
    //         * respectively
    //         *
    //         * @param cgNode current call graph node (for which the callees or callers should be processed)
    //         * @param type Either CALLEES or CALLERS indicating which type of edges should be processed
    //         */
    //        WorkItem(/*OrderedPair<IMethod, Context>*/int cgNode, CGEdgeType type) {
    //            assert type != CGEdgeType.PRECISE_CALLEE;
    //            this.type = type;
    //            this.cgNode = cgNode;
    //            this.callSite = null;
    //        }
    //
    //        /**
    //         * Create a work item for a particular call-site rather than processing all callees within an entire method
    //         *
    //         * @param callSite precise call site
    //         * @param context context for call-graph nodes the call-site appears in
    //         */
    //        WorkItem(CallSiteProgramPoint callSite, /*OrderedPair<IMethod, Context>*/int cgNode) {
    //            this.type = CGEdgeType.PRECISE_CALLEE;
    //            this.cgNode = cgNode;
    //            this.callSite = callSite;
    //        }
    //
    //        @Override
    //        public int hashCode() {
    //            final int prime = 31;
    //            int result = 1;
    //            result = prime * result + ((this.callSite == null) ? 0 : this.callSite.hashCode());
    //            result = prime * result + this.cgNode;
    //            result = prime * result + ((this.type == null) ? 0 : this.type.hashCode());
    //            return result;
    //        }
    //
    //        @Override
    //        public boolean equals(Object obj) {
    //            if (this == obj) {
    //                return true;
    //            }
    //            if (obj == null) {
    //                return false;
    //            }
    //            if (getClass() != obj.getClass()) {
    //                return false;
    //            }
    //            WorkItem other = (WorkItem) obj;
    //            if (this.cgNode != other.cgNode) {
    //                return false;
    //            }
    //            if (this.type != other.type) {
    //                return false;
    //            }
    //            if (this.callSite == null) {
    //                if (other.callSite != null) {
    //                    return false;
    //                }
    //            }
    //            else if (!this.callSite.equals(other.callSite)) {
    //                return false;
    //            }
    //            return true;
    //        }
    //
    //        @Override
    //        public String toString() {
    //            return "WorkItem\n\t\ttype=" + this.type + "\n\t\tcgNode=" + this.cgNode + "\n\t\tcallSite="
    //                    + this.callSite;
    //        }
    //
    //        /**
    //         * Lookup the call graph nodes for the source and destination and print them
    //         *
    //         * @param g points-to graph
    //         * @return more verbose string representation for "this"
    //         */
    //        public String toString(PointsToGraph g) {
    //            return "WorkItem\n\t\ttype=" + this.type + "\n\t\tcgNode=" + g.lookupCallGraphNodeDictionary(this.cgNode)
    //                    + "\n\t\tcallSite="
    //                    + this.callSite;
    //        }
    //    }

    //    /**
    //     * Indication of which type of edges to inspect while finding relevant nodes
    //     */
    //    private static enum CGEdgeType {
    //        /**
    //         * Callee edges (i.e. edges in the call graph _from_ a given call graph node)
    //         */
    //        CALLEES,
    //        /**
    //         * Caller edges (i.e. edges in the call graph _to_ a given call graph node)
    //         */
    //        CALLERS,
    //        /**
    //         * Edge for a paricular call site
    //         */
    //        PRECISE_CALLEE;
    //    }

    /**
     * Add an element, <code>elem</code>, to a set that is the value in a map, <code>map</code>, for a particular key,
     * <code>key</code>
     *
     * @param map map to add the element to
     * @param key key to add the element to the map for
     * @param elem element to add
     * @return true if the set at the key did not already contain the specified element
     */
    private static boolean addToMapSet(ConcurrentIntMap<MutableIntSet> map, int key, int elem) {
        MutableIntSet s = map.get(key);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            map.put(key, s);
        }
        return s.add(elem);
    }

    /**
     * Add an element, <code>elem</code>, to a set that is the value in a map, <code>map</code>, for a particular key,
     * <code>key</code>
     *
     * @param map map to add the element to
     * @param key key to add the element to the map for
     * @param elem element to add
     * @return true if the set at the key did not already contain the specified element
     */
    private static boolean addToMapSet(IntMap<MutableIntSet> map, int key, int elem) {
        MutableIntSet s = map.get(key);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            map.put(key, s);
        }
        return s.add(elem);
    }

    /**
     * Get all callSites that come after the given one in the same method. After means that they occur on some path from
     * the given call-site to the exit.
     *
     * @param callSite call site program point
     * @return set of call sites after the given call-site in the same method
     */
    private Set<CallSiteProgramPoint> getCallsitesAfter(CallSiteProgramPoint callSite) {
        Set<CallSiteProgramPoint> after = g.getRegistrar()
                                           .getCallSiteOrdering(callSite.getContainingProcedure())
                                           .get(callSite);
        if (after == null) {
            Map<CallSiteProgramPoint, Set<CallSiteProgramPoint>> ordering = g.getRegistrar()
                                                                             .getCallSiteOrdering(callSite.getContainingProcedure());
            CFGWriter.writeToFile(callSite.getContainingProcedure());
            System.err.println("ORDERING FOR " + PrettyPrinter.methodString(callSite.getContainingProcedure()));
            for (CallSiteProgramPoint first : ordering.keySet()) {
                System.err.println("\t" + first.getID() + "-" + PrettyPrinter.methodString(first.getCallee()));
                for (CallSiteProgramPoint cspp2 : ordering.get(first)) {
                    System.err.println("\t\t" + cspp2.getID() + "-" + PrettyPrinter.methodString(cspp2.getCallee()));
                }
            }
        }
        assert after != null : "null call sites after " + callSite;
        return after;
    }

    /**
     * Record the data structures computed for a given source
     *
     * @param query call graph node the computes to record results for
     * @param sqr query results to cache
     * @return true if the cache changed
     */
    private void recordSourceQueryResultsChanged(SourceRelevantNodesQuery query) {
        long start = System.currentTimeMillis();


        // results changed! rerun relevant queries that depend on the results of the relevant nodes query
        Set<RelevantNodesQuery> deps = relevantNodeSourceQueryDependencies.get(query);
        if (deps == null) {
            return;
        }

        for (RelevantNodesQuery rq : deps) {
            analysisHandle.submitRelevantNodesQuery(rq);
        }
        recordSourceQueryTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * Data structures computed for a given source that are incrementally updatable and used to evaluate queries from
     * that source.
     */
    private static class SourceQueryResults {
        // ConcurrentMap<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>>
        final ConcurrentIntMap<MutableIntSet> relevanceDependencies;

        final Set<WorkItem> alreadyVisited;

        final MutableIntSet callees;

        private final int hashcode;

        public SourceQueryResults(/*ConcurrentMap<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>>*/ConcurrentIntMap<MutableIntSet> relevanceDependencies,
                                  Set<WorkItem> allVisited, /*Set<OrderedPair<IMethod, Context>*/
                                  MutableIntSet callees) {
            this.relevanceDependencies = relevanceDependencies;
            this.alreadyVisited = allVisited;
            this.callees = callees;
            this.hashcode = computeHashCode();
        }

        @Override
        public int hashCode() {
            return hashcode;
        }

        public int computeHashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((this.alreadyVisited == null) ? 0 : this.alreadyVisited.hashCode());
            result = prime * result + ((this.callees == null) ? 0 : this.callees.hashCode());
            result = prime * result
                    + ((this.relevanceDependencies == null) ? 0 : this.relevanceDependencies.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            SourceQueryResults other = (SourceQueryResults) obj;
            if (this.hashcode != other.hashcode) {
                return false;
            }
            if (this.alreadyVisited == null) {
                if (other.alreadyVisited != null) {
                    return false;
                }
            }
            else if (!this.alreadyVisited.equals(other.alreadyVisited)) {
                return false;
            }
            if (this.callees == null) {
                if (other.callees != null) {
                    return false;
                }
            }
            else if (!this.callees.equals(other.callees)) {
                return false;
            }
            if (this.relevanceDependencies == null) {
                if (other.relevanceDependencies != null) {
                    return false;
                }
            }
            else if (!this.relevanceDependencies.equals(other.relevanceDependencies)) {
                return false;
            }
            return true;
        }
    }

    /**
     * Find call graph nodes that must be searched deeply for a path from the source to destination for the given query.
     * Irrelevant nodes can have their effects (kill and alloc sets) summarized.
     *
     * @param relevantQuery call graph node containing the source program point and call graph node containing the
     *            destination program point
     *
     * @return the set of call graph nodes that cannot be summarized
     */
    /*Set<OrderedPair<IMethod, Context>>*/IntSet computeRelevantNodes(RelevantNodesQuery relevantQuery) {
        //        if (%%%) {
        //            DEBUG = true;
        //            System.err.println("COMPUTING RELEVANT " + relevantQuery);
        //        }
        totalRequests.incrementAndGet();
        computedResponses.incrementAndGet();
        if (relevantCache.get(relevantQuery) != null) {
            // this is a recomputation due to a dependency trigger
            recomputedResponses.incrementAndGet();
        }
        long start = System.currentTimeMillis();
        sources.add(relevantQuery.sourceCGNode);
        targets.add(relevantQuery.destCGNode);

        // Use the source query results to compute the relevant nodes
        SourceRelevantNodesQuery sourceQuery = new SourceRelevantNodesQuery(relevantQuery.sourceCGNode);
        addRelevantNodeSourceQueryDependency(relevantQuery, sourceQuery);

        SourceQueryResults sqr = sourceQueryCache.get(sourceQuery);
        if (sqr == null) {
            // This is the first time computing this query include the source in the initial work queue
            Set<WorkItem> initialQ = new HashSet<>();
            initialQ.add(new WorkItem(relevantQuery.sourceCGNode, CGEdgeType.CALLEES));
            initialQ.add(new WorkItem(relevantQuery.sourceCGNode, CGEdgeType.CALLERS));
            addStartItems(sourceQuery, initialQ);
        }
        else {
            // Ignore the cached value here. We use it in computeSourceDependencies if the start items are empty
        }
        sqr = computeSourceDependencies(sourceQuery);

        // Map<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>>
        ConcurrentIntMap<MutableIntSet> deps = sqr.relevanceDependencies;
        // Map<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>>
        IntMap<? extends IntSet> calleeDeps = computeCalleeDeps(sqr.callees, relevantQuery);

        /*
         * The set of relevant cg nodes, ie., an overapproximation of nodes that are on
         * a (valid) path from the source to the destination. This method finds this set.
         */
        final/*Set<OrderedPair<IMethod, Context>>*/MutableIntSet relevant = MutableSparseIntSet.createMutableSparseIntSet(2);
        final Deque</*OrderedPair<IMethod, Context>*/Integer> newlyRelevant = new ArrayDeque<>();
        if (relevantQuery.destCGNode == relevantQuery.sourceCGNode) {
            // Special case when the source and destination nodes are the same
            newlyRelevant.add(relevantQuery.sourceCGNode);
        }

        if (deps.get(relevantQuery.destCGNode) != null || calleeDeps.get(relevantQuery.destCGNode) != null) {
            // The destination is reachable from the source
            // The destination is relevant
            newlyRelevant.add(relevantQuery.destCGNode);
        }
        if (DEBUG && newlyRelevant.isEmpty()) {
            System.err.println("UNREACHABLE " + relevantQuery.toString(g));
        }
        if (DEBUG) {
            System.err.println("RNI%% PROCESSING DEPENDENCIES");
        }
        while (!newlyRelevant.isEmpty()) {
            /*OrderedPair<IMethod, Context>*/int cg = newlyRelevant.poll();
            if (DEBUG) {
                System.err.println("RNI%%\tnewly relevant " + g.lookupCallGraphNodeDictionary(cg));
            }
            if (relevant.add(cg)) {
                // cg has become relevant, so use relevanceDependencies to figure out
                // what other nodes are now relevant.
                /*Set<OrderedPair<IMethod, Context>>*/IntSet s = deps.get(cg);
                /*Set<OrderedPair<IMethod, Context>>*/IntSet callees = calleeDeps.get(cg);
                if (DEBUG) {
                    if (s != null) {
                        /*Iterator<OrderedPair<IMethod, Context>>*/IntIterator iter = s.intIterator();
                        while (iter.hasNext()) {
                            System.err.println("RNI%%\t\tdep " + g.lookupCallGraphNodeDictionary(iter.next()));
                        }
                    }
                    else {
                        System.err.println(("RNI%%\t\tNO DEPS"));
                    }
                    if (callees != null) {
                        /*Iterator<OrderedPair<IMethod, Context>>*/IntIterator iter2 = callees.intIterator();
                        while (iter2.hasNext()) {
                            System.err.println("RNI%%\t\tdep " + g.lookupCallGraphNodeDictionary(iter2.next()));
                        }
                    }
                    else {
                        System.err.println(("RNI%%\t\tNO CALLEE DEPS"));
                    }
                }

                if (s != null) {
                    /*Iterator<OrderedPair<IMethod, Context>>*/IntIterator iter = s.intIterator();
                    while (iter.hasNext()) {
                        newlyRelevant.add(iter.next());
                    }
                }
                if (callees != null) {
                    /*Iterator<OrderedPair<IMethod, Context>>*/IntIterator iter2 = callees.intIterator();
                    while (iter2.hasNext()) {
                        newlyRelevant.add(iter2.next());
                    }
                }
            }
        }
        assert relevant.isEmpty() || relevant.contains(relevantQuery.sourceCGNode);

        // Record the results and rerun any dependencies if they changed
        recordRelevantNodesResults(relevantQuery, relevant);
        totalTime.addAndGet(System.currentTimeMillis() - start);
        totalSize.addAndGet(relevant.size());

        if (PRINT_DIAGNOSTICS && computedResponses.get() % 10000 == 0) {
            printDiagnostics();
        }

        // Reset the DEBUG variable after each call to this method
        if (CHECK_OLD) {
            //        System.err.println("!!!!!!!!!!!!! CHECKING");
            oldRelevantComputation.clearCaches();
            IntSet relevant2 = oldRelevantComputation.computeRelevantNodes(relevantQuery);

            //        System.err.println("!!!!!!!!!!!!! CHECKED");
            if (!relevant.sameValue(relevant2)) {
                System.err.println("Not the same :(");
                System.err.println(relevantQuery);
                System.err.println("OLD: " + relevant2);
                System.err.println("NEW: " + relevant);
                IntIterator iter = calleeDeps.keyIterator();
                System.err.println("DEPS: ");
                while (iter.hasNext()) {
                    int key = iter.next();
                    IntIterator s = calleeDeps.get(key).intIterator();
                    Set<Integer> ss = new LinkedHashSet<>();
                    while (s.hasNext()) {
                        ss.add(s.next());
                    }
                    System.err.println(key + " = " + ss);
                }
                IntIterator s = sqr.callees.intIterator();
                Set<Integer> ss = new LinkedHashSet<>();
                while (s.hasNext()) {
                    ss.add(s.next());
                }
                System.err.println("CALLEES: " + ss);

                IntIterator sss = sqr.callees.intIterator();
                Set<OrderedPair<IMethod, Context>> ssss = new LinkedHashSet<>();
                while (sss.hasNext()) {
                    ssss.add(g.lookupCallGraphNodeDictionary(sss.next()));
                }
                System.err.println("CALLEES: " + ssss);
                throw new RuntimeException("FAILED CHECK");
            }
        }
        return relevant;
    }

    /**
     * Compute the dependencies that arise due to callee relationships starting with the set of callees computed by
     * <code>computeSourceDependencies</code>
     *
     * @param callees callees to start with
     * @param relevantQuery query the callee dependencies are being computed for
     * @return map from call graph node to the set of call graph nodes that become relevant when that one does
     */
    // Map<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>>
    private IntMap<? extends IntSet> computeCalleeDeps(/*Set<OrderedPair<IMethod, Context>*/MutableIntSet callees,
                                                       RelevantNodesQuery relevantQuery) {
        long startCallee = System.currentTimeMillis();
        // Map<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>>
        IntMap<MutableIntSet> calleeDeps = new SparseIntMap<>();
        if (!callees.isEmpty()) {
            // Iterator<OrderedPair<IMethod, Context>>
            IntIterator calleeIter = callees.intIterator();
            final Deque</*OrderedPair<IMethod, Context>*/Integer> cgNodeQ = new ArrayDeque<>();
            final MutableIntSet visited = MutableSparseIntSet.createMutableSparseIntSet(callees.size());
            while (calleeIter.hasNext()) {
                int callee = calleeIter.next();
                visited.add(callee);
                cgNodeQ.add(callee);
            }

            while (!cgNodeQ.isEmpty()) {
                int cgNode = cgNodeQ.poll();
                addRelevantQueryCallGraphNodeCalleeDependency(relevantQuery, cgNode);
                IntIterator callSiteIter = g.getCallSitesWithinMethod(cgNode).intIterator();
                while (callSiteIter.hasNext()) {
                    IntIterator callSiteCallees = g.getCalleesOf(callSiteIter.next()).intIterator();
                    while (callSiteCallees.hasNext()) {
                        int callSiteCallee = callSiteCallees.next();
                        addToMapSet(calleeDeps, callSiteCallee, cgNode);
                        if (visited.add(callSiteCallee)) {
                            cgNodeQ.add(callSiteCallee);
                        }
                    }
                }
            }
        }
        this.calleeProcessingTime.addAndGet(System.currentTimeMillis() - startCallee);
        return calleeDeps;
    }

    /////////////////////////
    // Dependencies
    ////////////////////////

    /**
     * Dependencies: if the callers of the key CGNode change then recompute the source queries in the set mapped to that
     * key
     */
    // ConcurrentMap<OrderedPair<IMethod, Context>, Set<SourceRelevantNodesQuery>>
    private final ConcurrentIntMap<Set<SourceRelevantNodesQuery>> sourceQueryCallerDependencies = AnalysisUtil.createConcurrentIntMap();
    /**
     * Dependencies: if the callees for the key call-site change then recompute the source queries in the set mapped to
     * that key
     */
    // ConcurrentMap<ProgramPointReplica, Set<SourceRelevantNodesQuery>>
    private final ConcurrentIntMap<Set<SourceRelevantNodesQuery>> sourceQueryCalleeDependencies = AnalysisUtil.createConcurrentIntMap();
    /**
     * Dependencies: if the results for the source query starting at a key changes then recompute the relevant nodes
     * queries mapped to that key
     */
    private final ConcurrentMap<SourceRelevantNodesQuery, Set<RelevantNodesQuery>> relevantNodeSourceQueryDependencies = AnalysisUtil.createConcurrentHashMap();
    /**
     * Program point queries that depend on the findRelevantNodes queries
     */
    // ConcurrentMap<RelevantNodesQuery, Set<ProgramPointSubQuery>>
    private final ConcurrentMap<RelevantNodesQuery, MutableIntSet> relevantNodesDependencies = AnalysisUtil.createConcurrentHashMap();
    /**
     * If the callees from the key CGNode change then recompute the source queries in the set mapped to that key
     */
    // ConcurrentMap<OrderedPair<IMethod, Context>, Set<RelevantNodesQuery>>
    private final ConcurrentIntMap<Set<RelevantNodesQuery>> relevantQueryCallGraphNodeCalleeDependencies = AnalysisUtil.createConcurrentIntMap();

    /**
     * Record a dependency of relevant query on the callees from callerCGNode. If the callees of calleeCGNode change
     * then recompute the query
     *
     * @param query query computing relevant nodes from a source call graph node to a destination call graph node
     * @param callerCGNode CG node the query dependends on
     */
    private void addRelevantQueryCallGraphNodeCalleeDependency(RelevantNodesQuery query, /*OrderedPair<IMethod, Context>*/
                                                              int callerCGNode) {
        long start = System.currentTimeMillis();

        Set<RelevantNodesQuery> s = relevantQueryCallGraphNodeCalleeDependencies.get(callerCGNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<RelevantNodesQuery> existing = relevantQueryCallGraphNodeCalleeDependencies.putIfAbsent(callerCGNode,
                                                                                                             s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);

        calleeCGNodeDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * Record a dependency of sourceCGNode on the callers of calleeCGNode. If the callers of calleeCGNode change then
     * recompute the dependencies starting at sourceCGNode
     *
     * @param query query starting at a particular source that computes dependencies
     * @param calleeCGNode CG node the source query dependends on
     */
    private void addSourceQueryCallerDependency(SourceRelevantNodesQuery query, /*OrderedPair<IMethod, Context>*/
                                                int calleeCGNode) {
        long start = System.currentTimeMillis();

        Set<SourceRelevantNodesQuery> s = sourceQueryCallerDependencies.get(calleeCGNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<SourceRelevantNodesQuery> existing = sourceQueryCallerDependencies.putIfAbsent(calleeCGNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);

        callerDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * Record the fact that the results of a source query depend on the callees for the given call-site
     *
     * @param query query starting at a particular source that computes dependencies
     * @param callSite program point for the call-site
     */
    private void addSourceQueryCalleeDependency(SourceRelevantNodesQuery query, /*ProgramPointReplica*/int callSite) {
        long start = System.currentTimeMillis();

        Set<SourceRelevantNodesQuery> s = sourceQueryCalleeDependencies.get(callSite);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<SourceRelevantNodesQuery> existing = sourceQueryCalleeDependencies.putIfAbsent(callSite, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);

        calleeDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * Record a dependency need to recompute the relevant node query if the query starting at sourceQuery changes
     *
     * @param relevantQuery relevant node query that depends on the results of a source query
     * @param sourceQuery query that computes dependencies starting at a particular source
     */
    private void addRelevantNodeSourceQueryDependency(RelevantNodesQuery relevantQuery, SourceRelevantNodesQuery sourceQuery) {
        long start = System.currentTimeMillis();

        Set<RelevantNodesQuery> s = relevantNodeSourceQueryDependencies.get(sourceQuery);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<RelevantNodesQuery> existing = relevantNodeSourceQueryDependencies.putIfAbsent(sourceQuery, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(relevantQuery);

        queryDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    //    /**
    //     * Query to compute relevant nodes dependencies from a source call graph node
    //     */
    //    public static class SourceRelevantNodesQuery {
    //        /**
    //         * Node to start the query from
    //         */
    //        final/*OrderedPair<IMethod, Context>*/int sourceCGNode;
    //
    //        /**
    //         * Query that computes the dependencies needed to compute relevant node queries from a source node.
    //         *
    //         * @param sourceCGNode source node to run the query from
    //         */
    //        public SourceRelevantNodesQuery(/*OrderedPair<IMethod, Context>*/int sourceCGNode) {
    //            this.sourceCGNode = sourceCGNode;
    //        }
    //
    //        @Override
    //        public int hashCode() {
    //            final int prime = 31;
    //            int result = 1;
    //            result = prime * result + this.sourceCGNode;
    //            return result;
    //        }
    //
    //        @Override
    //        public boolean equals(Object obj) {
    //            if (this == obj) {
    //                return true;
    //            }
    //            if (obj == null) {
    //                return false;
    //            }
    //            if (getClass() != obj.getClass()) {
    //                return false;
    //            }
    //            SourceRelevantNodesQuery other = (SourceRelevantNodesQuery) obj;
    //            if (this.sourceCGNode != other.sourceCGNode) {
    //                return false;
    //            }
    //            return true;
    //        }
    //
    //        @Override
    //        public String toString() {
    //            return "Source ID: " + sourceCGNode;
    //        }
    //
    //        /**
    //         * Lookup the call graph nodes for the source and destination and print them
    //         *
    //         * @param g points-to graph
    //         * @return more verbose string representation for "this"
    //         */
    //        public String toString(PointsToGraph g) {
    //            return "Source ID: " + g.lookupCallGraphNodeDictionary(sourceCGNode);
    //        }
    //    }

    ////////////////////////////////////////
    ///Diagnostic information can be removed without affecting algorithm
    ///////////////////////////////////////

    private final/*Set<OrderedPair<IMethod, Context>>*/MutableIntSet sources = AnalysisUtil.createConcurrentIntSet();
    private final/*Set<OrderedPair<IMethod, Context>>*/MutableIntSet targets = AnalysisUtil.createConcurrentIntSet();

    // Counts for relevant node queries
    private AtomicInteger totalRequests = new AtomicInteger(0);
    private AtomicInteger cachedResponses = new AtomicInteger(0);
    private AtomicInteger computedResponses = new AtomicInteger(0);
    private AtomicInteger recomputedResponses = new AtomicInteger(0);
    private AtomicInteger totalSize = new AtomicInteger(0);

    // Counts for source queries
    private AtomicInteger totalSourceRequests = new AtomicInteger(0);
    private AtomicInteger cachedSourceResponses = new AtomicInteger(0);
    private AtomicInteger computedSourceResponses = new AtomicInteger(0);
    private AtomicInteger recomputedSourceResponses = new AtomicInteger(0);

    // Timing information for relevant node queries
    private AtomicLong totalTime = new AtomicLong(0);
    private AtomicLong queryDepTime = new AtomicLong(0);
    private AtomicLong recordRelevantTime = new AtomicLong(0);
    private AtomicLong calleeProcessingTime = new AtomicLong(0);

    // Timing information for source queries
    private AtomicLong startItemTime = new AtomicLong(0);
    private AtomicLong calleeDepTime = new AtomicLong(0);
    private AtomicLong callerDepTime = new AtomicLong(0);
    private AtomicLong calleeCGNodeDepTime = new AtomicLong(0);
    private AtomicLong totalSourceTime = new AtomicLong(0);
    private AtomicLong recordSourceQueryTime = new AtomicLong(0);

    void printDiagnostics() {
        System.err.println("\n%%%%%%%%%%%%%%%%% RELEVANT NODE STATISTICS %%%%%%%%%%%%%%%%%");
        System.err.println("\nTotal relevent requests: " + totalRequests + "  ;  " + cachedResponses + "  cached "
                + computedResponses + " computed ("
                + (int) (100 * (cachedResponses.floatValue() / totalRequests.floatValue())) + "% hit rate)");
        System.err.println("\tRecomputed " + recomputedResponses.get() + " " + recomputedResponses.doubleValue()
                / computedResponses.doubleValue() + "% of all computed");
        System.err.println("\nTotal source requests: " + totalSourceRequests + "  ;  " + cachedSourceResponses
                + "  cached " + computedSourceResponses + " computed ("
                + (int) (100 * (cachedSourceResponses.floatValue() / totalSourceRequests.floatValue())) + "% hit rate)");

        double analysisTime = (System.currentTimeMillis() - PointsToAnalysis.startTime) / 1000.0;
        double relevantTime = totalTime.get() / 1000.0;
        double calleeTime = calleeDepTime.get() / 1000.0;
        double callerTime = callerDepTime.get() / 1000.0;
        double calleeCGNodeTime = calleeCGNodeDepTime.get() / 1000.0;
        double queryTime = queryDepTime.get() / 1000.0;
        double startItem = startItemTime.get() / 1000.0;
        double recordRelTime = recordRelevantTime.get() / 1000.0;
        double recordSourceTime = recordSourceQueryTime.get() / 1000.0;
        double sourceTime = totalSourceTime.get() / 1000.0;
        double calleeProcessing = calleeProcessingTime.get() / 1000.0;

        double size = totalSize.get();
        StringBuffer sb = new StringBuffer();
        sb.append("Total: " + analysisTime + "s;" + "\n");
        analysisTime *= analysisHandle.numThreads();
        sb.append("RELEVANT NODES QUERY EXECUTION" + "\n");
        sb.append("    computeRelevantNodes: " + relevantTime + "s; RATIO: " + (relevantTime / analysisTime) + "\n");
        sb.append("    calleeProcessingTime: " + calleeProcessing + "s; RATIO: " + (calleeProcessing / analysisTime)
                + "\n");
        sb.append("    recordRelevantNodesResults: " + recordRelTime + "s; RATIO: " + (recordRelTime / analysisTime)
                + "\n");
        sb.append("    size: " + size + " average: " + size / computedResponses.get() + "\n");
        sb.append("    sources: " + sources.size() + " sources/computed: " + ((double) sources.size())
                / computedResponses.get() + "\n");
        sb.append("    targets: " + targets.size() + " targets/computed: " + ((double) targets.size())
                / computedResponses.get() + "\n");

        sb.append("SOURCE QUERY EXECUTION" + "\n");
        sb.append("    computeSourceDependencies: " + sourceTime + "s; RATIO: " + (sourceTime / (analysisTime)) + "\n");
        sb.append("    addSourceQueryCalleeDependency: " + calleeTime + "s; RATIO: " + (calleeTime / analysisTime)
                + "\n");
        sb.append("    addSourceQueryCallerDependency: " + callerTime + "s; RATIO: " + (callerTime / analysisTime)
                + "\n");
        sb.append("    addStartItems: " + startItem + "s; RATIO: " + (startItem / analysisTime) + "\n");
        sb.append("    addRelevantNodesSourceQueryDependency: " + queryTime + "s; RATIO: " + (queryTime / analysisTime)
                + "\n");
        sb.append("    addSourceQueryCallGraphNodeCalleeDependency: " + calleeCGNodeTime + "s; RATIO: "
                + (calleeCGNodeTime / analysisTime) + "\n");
        sb.append("    recordSourceQueryResults: " + recordSourceTime + "s; RATIO: "
                + (recordSourceTime / analysisTime) + ";" + "\n");
        sb.append("\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%" + "\n");
        System.err.println(sb.toString());
    }

    //    /**
    //     * Query to find nodes that are relevant for a query from a program point in the source call graph nodes to a
    //     * program point in the destination call graph node
    //     */
    //    public static class RelevantNodesQuery {
    //        /**
    //         * Call graph node containing the source program point
    //         */
    //        final/*OrderedPair<IMethod, Context>*/int sourceCGNode;
    //        /**
    //         * Call graph node containing the destination program point
    //         */
    //        final/*OrderedPair<IMethod, Context>*/int destCGNode;
    //
    //        /**
    //         * Query to find nodes that are relevant for a query from a program point in the source call graph nodes to a
    //         * program point in the destination call graph node
    //         *
    //         * @param sourceCGNode Call graph node containing the source program point
    //         * @param destCGNode Call graph node containing the destination program point
    //         */
    //        public RelevantNodesQuery(/*OrderedPair<IMethod, Context>*/int sourceCGNode,
    //        /*OrderedPair<IMethod, Context>*/int destCGNode) {
    //            this.sourceCGNode = sourceCGNode;
    //            this.destCGNode = destCGNode;
    //        }
    //
    //        @Override
    //        public int hashCode() {
    //            final int prime = 31;
    //            int result = 1;
    //            result = prime * result + this.destCGNode;
    //            result = prime * result + this.sourceCGNode;
    //            return result;
    //        }
    //
    //        @Override
    //        public boolean equals(Object obj) {
    //            if (this == obj) {
    //                return true;
    //            }
    //            if (obj == null) {
    //                return false;
    //            }
    //            if (getClass() != obj.getClass()) {
    //                return false;
    //            }
    //            RelevantNodesQuery other = (RelevantNodesQuery) obj;
    //            if (this.destCGNode != other.destCGNode) {
    //                return false;
    //            }
    //            if (this.sourceCGNode != other.sourceCGNode) {
    //                return false;
    //            }
    //            return true;
    //        }
    //
    //        @Override
    //        public String toString() {
    //            return "Source: " + this.sourceCGNode + " Dest: " + this.destCGNode;
    //        }
    //
    //        /**
    //         * Lookup the call graph nodes for the source and destination and print them
    //         *
    //         * @param g points-to graph
    //         * @return more verbose string representation for "this"
    //         */
    //        public String toString(PointsToGraph g) {
    //            return "Source: " + g.lookupCallGraphNodeDictionary(this.sourceCGNode) + " Dest: "
    //                    + g.lookupCallGraphNodeDictionary(this.destCGNode);
    //        }
    //    }

    /**
     * Clear query and relevant node caches
     */
    void clearCaches() {
        this.relevantCache = AnalysisUtil.createConcurrentHashMap();
        this.sourceQueryCache = AnalysisUtil.createConcurrentHashMap();
    }
}
