package analysis.pointer.graph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import util.OrderedPair;
import util.intmap.ConcurrentIntMap;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

public final class RelevantNodes {

    /**
     * Print a ton for each run, such as the elements pulled off the queue
     */
    public static boolean DEBUG = false;
    /**
     * Print diagnostic information about timing and numbers intermittently, this shouldn't affect performance too much
     */
    private boolean PRINT_DIAGNOSTICS = false;

    private final PointsToGraph g;
    private final ProgramPointReachability programPointReachability;
    static final AtomicInteger calleesProcessed = new AtomicInteger(0);

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
    RelevantNodes(PointsToGraph g, final PointsToAnalysisHandle analysisHandle,
                               ProgramPointReachability programPointReachability) {
        this.g = g;
        this.analysisHandle = analysisHandle;
        this.programPointReachability = programPointReachability;
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
    void computeSourceDependencies(SourceRelevantNodesQuery query) {
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
        SourceQueryResults previous = getOrCreateSourceQueryResults(query);
        boolean resultsChanged = false;

        /*
         * We maintain dependencies, so that if cg node a is in the set relevanceDependencies.get(b),
         * then if b becomes a relevant node, then a is also a relevant node. We can think of this
         * relevanceDependencies as describing the edges in the CFG that are reachable on a (valid)
         * path from the source cg node.
         */

        /*Map<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>>*/
        ConcurrentIntMap<MutableIntSet> relevanceDependencies = previous.relevanceDependencies;
        /*Set<ProgramPointReplica>*/MutableIntSet visitedCallSites = previous.visitedCallSites;
        /*Set<OrderedPair<IMethod, Context>>*/MutableIntSet visitedCalleesNoCallSite = previous.visitedCalleesNoCallSite;
        /*Set<OrderedPair<IMethod, Context>>*/MutableIntSet visitedCallers = previous.visitedCallers;

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
        // Add initial items to the visited sets
        for (WorkItem wi : initial) {
            if (wi.callSite >= 0) {
                visitedCallSites.add(wi.callSite);
            }
            else if (wi.cgNode >= 0) {
                visitedCallers.add(wi.cgNode);
            }
            else {
                assert false : "Invalid start item: " + wi;
            }
        }

        if (q.isEmpty()) {
            // There are no new items to process the previous results will suffice
            cachedSourceResponses.incrementAndGet();
            if (DEBUG) {
                System.err.println("RNI%%\tUSING PREVIOUS");
            }
            return;
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
            if (p.cgNode >= 0) { // case CALLERS:
                // explore the callers of this cg node
                int cgNode = p.cgNode;
                // This dependency ensures that the source query is rerun if the callers of the given CG node change
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
                    if (visitedCallers.add(callerCGNode)) {
                        // Don't need to mark results changed here since the relevant node query
                        //       does not rely on the CALLER items in the visited set
                        q.add(WorkItem.createCallerWorkItem(callerCGNode));
                    }

                    // Only add the callees in the caller that are after the return site
                    ProgramPointReplica returnSite = g.lookupCallSiteReplicaDictionary(callerInt);
                    Set<CallSiteProgramPoint> after = getCallsitesAfter((CallSiteProgramPoint) returnSite.getPP());
                    Context context = returnSite.getContext();
                    for (CallSiteProgramPoint cspp : after) {
                        int callSiteAfter = g.lookupCallSiteReplicaDictionary(cspp.getReplica(context));
                        if (visitedCallSites.add(callSiteAfter)) {
                            // Relevant node queries depend on the visited set of callees so this is a change that affects dependencies
                            resultsChanged = true;
                            q.add(WorkItem.createCallSiteWorkItem(callSiteAfter));
                        }
                    }
                } // End of loop through caller return sites
            } // End of handling CALLERS
            else { // case CALL_SITE:
                   // handle callees from a particular call site

                /*ProgramPointReplica*/int callSiteReplica = p.callSite;

                // This dependency ensures that the source query is rerun if callees from the given call site change
                this.addSourceQueryCalleeDependency(query, callSiteReplica);
                /*Iterator<OrderedPair<IMethod, Context>>*/IntIterator calleeIter = g.getCalleesOf(callSiteReplica)
                                                                                      .intIterator();
                while (calleeIter.hasNext()) {
                    /*OrderedPair<IMethod, Context>*/int callee = calleeIter.next();
                    calleesProcessed.incrementAndGet();

                    // We are exploring only the callees of the call-site, so when we explore callee
                    // we only need to explore its callees (not its callers).
                    /*Iterator<ProgramPointReplica*/IntIterator calleeCallSites = g.getCallSitesWithinMethod(callee)
                                                                                    .intIterator();

                    if (!calleeCallSites.hasNext()) {
                        // Add the callee to the visited set if there are no call-sites that will be added
                        // This records that the callee is reachable, which will be used in computeRelevantNodes
                        resultsChanged |= visitedCalleesNoCallSite.add(callee);
                    }

                    while (calleeCallSites.hasNext()) {
                        /*ProgramPointReplica*/int cs = calleeCallSites.next();
                        if (visitedCallSites.add(cs)) {
                            // Relevant node queries depend on the visited set of callees so this is a change that affects dependencies
                            resultsChanged = true;
                            q.add(WorkItem.createCallSiteWorkItem(cs));
                        }
                    }
                } // End of loop through
            } // End handling call sites
        } // Queue is empty

        // Record the results and rerun any dependencies
        if (resultsChanged) {
            recordSourceQueryResultsChanged(query);
        }
        totalSourceTime.addAndGet(System.currentTimeMillis() - start);
    }

    private SourceQueryResults getOrCreateSourceQueryResults(SourceRelevantNodesQuery query) {
        SourceQueryResults previous = sourceQueryCache.get(query);
        if (previous == null) {
            // No previous results initialize the visited and relevant sets
            previous = new SourceQueryResults();
            SourceQueryResults existing = sourceQueryCache.putIfAbsent(query, previous);
            if (existing != null) {
                // someone beat us!
                previous = existing;
            }
        }
        return previous;
    }

    /**
     * Get the call graph node (method and context) containing a given call site replica (program point and context)
     *
     * @param callSite call site replica
     * @return call graph node
     */
    /*OrderedPair<IMethod, Context>*/int getCGNodeForCallSiteReplica(/*ProgramPointReplica*/int callSite) {
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
            WorkItem newItem = WorkItem.createCallSiteWorkItem(callSite);
            for (SourceRelevantNodesQuery query : queries) {
                addStartItem(query, newItem);

                // Make sure the query gets rerun by the analysis if it doesn't get triggered earlier
                this.analysisHandle.submitSourceRelevantNodesQuery(query);
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
            WorkItem newItem = WorkItem.createCallerWorkItem(calleeCallGraphNode);
            for (SourceRelevantNodesQuery query : queries) {
                addStartItem(query, newItem);

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
    }

    /**
     * Add work item to the initial queue for a source query
     *
     * @param query start node of the source query
     * @param startItem item to add to the initial queue
     */
    private void addStartItem(SourceRelevantNodesQuery query, WorkItem newItem) {
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
            s.add(newItem);
        } while (s != startItems.get(query));
        startItemTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * Work queue element of work, indicates which call graph edges to process next
     */
    private static class WorkItem {
        final/*OrderedPair<IMethod, Context>*/int cgNode;
        final/*ProgramPointReplica*/int callSite;
        private final int memoizedHashCode;

        /**
         * Create a work item to trigger the processing of all callees from a particular call site
         *
         * @param callSite call site program point replica
         * @return the new work item
         */
        public static WorkItem createCallSiteWorkItem(/*ProgramPointReplica*/int callSite) {
            return new WorkItem(-1, callSite);
        }

        /**
         * Create a work item to trigger the processing of all callers of a particular call graph node
         *
         * @param cgNode call graph node
         * @return new work item
         */
        public static WorkItem createCallerWorkItem(/*OrderedPair<IMethod, Context>*/int cgNode) {
            return new WorkItem(cgNode, -1);
        }

        /**
         * Create a work item that will process all edges leaving a call graph node or leaving a call site
         *
         * @param cgNode call graph node for which the callers should be processed, -1 if this is a CALLSITE work item
         * @param callSite call site for which the callees should be processed, -1 if this is a CALLER work item
         */
        private WorkItem(/*OrderedPair<IMethod, Context>*/int cgNode, /*ProgramPointReplica*/int callSite) {
            this.cgNode = cgNode;
            this.callSite = callSite;
            this.memoizedHashCode = computeHashCode();
        }

        @Override
        public int hashCode() {
            return memoizedHashCode;
        }

        public int computeHashCode() {
            return 13 * this.callSite + 17 * this.cgNode;
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
            WorkItem other = (WorkItem) obj;
            if (this.cgNode != other.cgNode) {
                return false;
            }
            if (this.callSite != other.callSite) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "cgNode=" + this.cgNode + " callSite=" + this.callSite;
        }

        /**
         * Lookup the call graph nodes for the source and destination and print them
         *
         * @param g points-to graph
         * @return more verbose string representation for "this"
         */
        public String toString(PointsToGraph g) {
            StringBuilder sb = new StringBuilder();
            if (cgNode != -1) {
                sb.append("cgNode=" + g.lookupCallGraphNodeDictionary(this.cgNode));
            }
            if (callSite != -1) {
                sb.append("callSite=" + g.lookupCallSiteReplicaDictionary(this.callSite));
            }
            return sb.toString();
        }
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
    private static boolean addToMapSet(ConcurrentIntMap<MutableIntSet> map, int key, int elem) {
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

        final/*Set<OrderedPair<IMethod, Context>>*/MutableIntSet visitedCallers;
        final/*Set<OrderedPair<IMethod, Context>>*/MutableIntSet visitedCalleesNoCallSite;
        final/*Set<ProgramPointReplica>*/MutableIntSet visitedCallSites;

        public SourceQueryResults() {
            this.relevanceDependencies = AnalysisUtil.createConcurrentIntMap();
            this.visitedCallers = AnalysisUtil.createConcurrentIntSet();
            this.visitedCalleesNoCallSite = AnalysisUtil.createConcurrentIntSet();
            this.visitedCallSites = AnalysisUtil.createConcurrentIntSet();
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }


        @Override
        public boolean equals(Object obj) {
            return this == obj;
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
            // This is the first time computing this query include the source node callers and call-sites
            // in the initial work queue
            Set<WorkItem> initialQ = new HashSet<>();
            initialQ.add(WorkItem.createCallerWorkItem(relevantQuery.sourceCGNode));
            IntIterator callSites = g.getCallSitesWithinMethod(relevantQuery.sourceCGNode).intIterator();
            while (callSites.hasNext()) {
                initialQ.add(WorkItem.createCallSiteWorkItem(callSites.next()));
            }
            addStartItems(sourceQuery, initialQ);
        }
        else {
            // Ignore the cached value here. We use it in computeSourceDependencies if the start items are empty
        }
        computeSourceDependencies(sourceQuery);
        sqr = sourceQueryCache.get(sourceQuery);

        // Map<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>>
        ConcurrentIntMap<MutableIntSet> deps = sqr.relevanceDependencies;

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

        if (deps.get(relevantQuery.destCGNode) != null
                || hasBeenVisited(relevantQuery.destCGNode, sqr.visitedCalleesNoCallSite, sqr.visitedCallSites)) {
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
                /*Iterator<OrderedPair<IMethod, Context>>*/IntIterator callers = getVisitedCallers2(cg,
                                                                                                sqr.visitedCallSites);
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
                    IntIterator callerIter2 = getVisitedCallers2(cg, sqr.visitedCallSites);
                    if (!callerIter2.hasNext()) {
                        System.err.println(("RNI%%\t\tNO CALLEE DEPS"));
                    }
                    while (callerIter2.hasNext()) {
                        System.err.println("RNI%%\t\tdep " + g.lookupCallGraphNodeDictionary(callerIter2.next()));
                    }
                }

                if (s != null) {
                    /*Iterator<OrderedPair<IMethod, Context>>*/IntIterator iter = s.intIterator();
                    while (iter.hasNext()) {
                        newlyRelevant.add(iter.next());
                    }
                }

                while (callers.hasNext()) {
                    newlyRelevant.add(callers.next());
                }
            }
        }
        assert relevant.isEmpty() || relevant.contains(relevantQuery.sourceCGNode) : "If there are any relevant nodes the source must be relevant.";

        // Record the results and rerun any dependencies if they changed
        recordRelevantNodesResults(relevantQuery, relevant);
        totalTime.addAndGet(System.currentTimeMillis() - start);
        totalSize.addAndGet(relevant.size());

        if (PRINT_DIAGNOSTICS && computedResponses.get() % 10000 == 0) {
            printDiagnostics();
        }
        return relevant;
    }

    /**
     * Check whether a given destination call graph node has been visited
     *
     * @param destCGNode destination call graph node
     * @param visitedCalleesNoCallSite set containing all call graph nodes with no call sites that have been visited
     * @param visitedCallSites set containing all call sites that have been visited
     *
     * @return true if the destination call graph node has been visited
     */
    private boolean hasBeenVisited(/*OrderedPair<IMethod, Context>*/int destCGNode, /*Set<OrderedPair<IMethod, Context>>*/
                                   IntSet visitedCalleesNoCallSite, /*Set<ProgramPointReplica>*/IntSet visitedCallSites) {
        IntIterator sites = g.getCallSitesWithinMethod(destCGNode).intIterator();
        while (sites.hasNext()) {
            if (visitedCallSites.contains(sites.next())) {
                // A call site of the destination has been visited
                return true;
            }
        }

        if (visitedCalleesNoCallSite.contains(destCGNode)) {
            return true;
        }
        return false;
    }

    /**
     * Iterator through all callers of the given call graph node that have been visited
     *
     * @param cg call graph node
     * @param visitedCallSites all visited call sites
     * @return new iterator
     */
    private IntIterator getVisitedCallers2(final/*OrderedPair<IMethod, Context>*/int cg, final/*Set<ProgramPointReplica>*/
                                           IntSet visitedCallSites) {
        long start = System.currentTimeMillis();
        final/*Iterator<ProgramPointReplica>*/IntIterator callers = g.getCallersOf(cg).intIterator();
        if (!callers.hasNext()) {
            // No callers
            return new com.ibm.wala.util.intset.EmptyIntSet().intIterator();
        }

        // The iterator we are returning
        IntIterator visitedCallers = new IntIterator() {

            private int next = -1;

            @Override
            public int next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                int temp = this.next;
                this.next = -1;
                return temp;
            }

            @Override
            public boolean hasNext() {
                if (this.next >= 0) {
                    // already computed the next item
                    return true;
                }

                while (callers.hasNext()) {
                    /*ProgramPointReplica*/int callSite = callers.next();
                    if (visitedCallSites.contains(callSite)) {
                        this.next = getCGNodeForCallSiteReplica(callSite);
                        // Found a caller that was in the visited set
                        return true;
                    }
                }

                // No remaining caller is in the visited set
                return false;
            }
        };
        calleeProcessingTime.addAndGet(System.currentTimeMillis() - start);
        return visitedCallers;
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
    private void addRelevantNodeSourceQueryDependency(RelevantNodesQuery relevantQuery,
                                                      SourceRelevantNodesQuery sourceQuery) {
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

    /**
     * Query to compute relevant nodes dependencies from a source call graph node
     */
    public static class SourceRelevantNodesQuery {
        /**
         * Node to start the query from
         */
        final/*OrderedPair<IMethod, Context>*/int sourceCGNode;

        /**
         * Query that computes the dependencies needed to compute relevant node queries from a source node.
         *
         * @param sourceCGNode source node to run the query from
         */
        public SourceRelevantNodesQuery(/*OrderedPair<IMethod, Context>*/int sourceCGNode) {
            this.sourceCGNode = sourceCGNode;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + this.sourceCGNode;
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
            SourceRelevantNodesQuery other = (SourceRelevantNodesQuery) obj;
            if (this.sourceCGNode != other.sourceCGNode) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "Source ID: " + sourceCGNode;
        }

        /**
         * Lookup the call graph nodes for the source and destination and print them
         *
         * @param g points-to graph
         * @return more verbose string representation for "this"
         */
        public String toString(PointsToGraph g) {
            return "Source ID: " + g.lookupCallGraphNodeDictionary(sourceCGNode);
        }
    }

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
        sb.append("    recordSourceQueryResults: " + recordSourceTime + "s; RATIO: "
                + (recordSourceTime / analysisTime) + ";" + "\n");
        sb.append("\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%" + "\n");
        System.err.println(sb.toString());
    }

    /**
     * Query to find nodes that are relevant for a query from a program point in the source call graph nodes to a
     * program point in the destination call graph node
     */
    public static class RelevantNodesQuery {
        /**
         * Call graph node containing the source program point
         */
        final/*OrderedPair<IMethod, Context>*/int sourceCGNode;
        /**
         * Call graph node containing the destination program point
         */
        final/*OrderedPair<IMethod, Context>*/int destCGNode;

        /**
         * Query to find nodes that are relevant for a query from a program point in the source call graph nodes to a
         * program point in the destination call graph node
         * 
         * @param sourceCGNode Call graph node containing the source program point
         * @param destCGNode Call graph node containing the destination program point
         */
        public RelevantNodesQuery(/*OrderedPair<IMethod, Context>*/int sourceCGNode,
        /*OrderedPair<IMethod, Context>*/int destCGNode) {
            this.sourceCGNode = sourceCGNode;
            this.destCGNode = destCGNode;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + this.destCGNode;
            result = prime * result + this.sourceCGNode;
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
            RelevantNodesQuery other = (RelevantNodesQuery) obj;
            if (this.destCGNode != other.destCGNode) {
                return false;
            }
            if (this.sourceCGNode != other.sourceCGNode) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "Source: " + this.sourceCGNode + " Dest: " + this.destCGNode;
        }

        /**
         * Lookup the call graph nodes for the source and destination and print them
         * 
         * @param g points-to graph
         * @return more verbose string representation for "this"
         */
        public String toString(PointsToGraph g) {
            return "Source: " + g.lookupCallGraphNodeDictionary(this.sourceCGNode) + " Dest: "
                    + g.lookupCallGraphNodeDictionary(this.destCGNode);
        }
    }

    /**
     * Clear query and relevant node caches
     */
    void clearCaches() {
        this.relevantCache = AnalysisUtil.createConcurrentHashMap();
        this.sourceQueryCache = AnalysisUtil.createConcurrentHashMap();
    }
}
