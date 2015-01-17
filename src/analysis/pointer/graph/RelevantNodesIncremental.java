package analysis.pointer.graph;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import util.OrderedPair;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.graph.RelevantNodes.RelevantNodesQuery;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public final class RelevantNodesIncremental {

    /**
     * Print a ton for each run, such as the elements pulled off the queue
     */
    private boolean DEBUG = false;
    /**
     * Print diagnostic information about timing and numbers intermittently, this shouldn't affect performance too much
     */
    private boolean PRINT_DIAGNOSTICS = true;
    /**
     * Each relevant nodes query will also run the older (non-incremental) algorithm and compare the results with this
     * one, an assertion error if they are not identical
     */
    private boolean COMPARE_WITH_OLD_ALGORITHM = false;

    private final PointsToGraph g;
    private final ProgramPointReachability programPointReachability;

    /**
     * A reference to allow us to submit a query for reprocessing
     */
    private final PointsToAnalysisHandle analysisHandle;

    /**
     * Cache containing the results of computing the relevant nodes for a given source and destination
     */
    private final ConcurrentMap<RelevantNodesQuery, Set<OrderedPair<IMethod, Context>>> relevantCache = AnalysisUtil.createConcurrentHashMap();

    /**
     * Program point queries that depend on the findRelevantNodes queries
     */
    private final ConcurrentMap<RelevantNodesQuery, Set<ProgramPointSubQuery>> relevantNodesDependencies = AnalysisUtil.createConcurrentHashMap();
    /**
     * Cache of results containing the results of finding dependencies starting at a particular call graph node
     */
    private final ConcurrentMap<SourceRelevantNodesQuery, SourceQueryResults> sourceQueryCache = AnalysisUtil.createConcurrentHashMap();
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
    RelevantNodesIncremental(PointsToGraph g, PointsToAnalysisHandle analysisHandle,
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
    Set<OrderedPair<IMethod, Context>> relevantNodes(OrderedPair<IMethod, Context> source,
                                                     OrderedPair<IMethod, Context> dest, ProgramPointSubQuery query) {
        RelevantNodesQuery relevantQuery = new RelevantNodesQuery(source, dest);

        // add a dependency
        addRelevantNodesDependency(query, relevantQuery);

        // check the cache.
        Set<OrderedPair<IMethod, Context>> relevantNodes = relevantCache.get(relevantQuery);

        if (relevantNodes == null) {
            if (DEBUG) {
                System.err.println("COMPUTE RELEVANT needed");
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
        OrderedPair<IMethod, Context> sourceCGNode = query.sourceCGNode;
        if (DEBUG) {
            System.err.println("%% SOURCE QUERY for " + sourceCGNode);
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
            previous = new SourceQueryResults(AnalysisUtil.<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>> createConcurrentHashMap(),
                                              AnalysisUtil.<WorkItem> createConcurrentSet());
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
        ConcurrentMap<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>> relevanceDependencies = previous.relevanceDependencies;
        Set<WorkItem> allVisited = previous.alreadyVisited;

        Deque<WorkItem> q = new ArrayDeque<>();
        Set<WorkItem> initial = this.startItems.replace(query, AnalysisUtil.<WorkItem> createConcurrentSet());
        // XXX when is it ok to remove the start items?
        // They are added when a new callee/caller is added so they need to be processed at least once
        // We cannot remove them here:
        // The issue is that in the multi-threaded we might get a race where two threads
        //     get the same previous results, but different initial values
        // Maybe remove them when recording the results for this?
        // What if a new call gets added to the same callee/caller while processing this meaning that the start item
        //     needs to be reprocessed again
        // This needs some thought
        assert initial != null : "Null start items for " + query;
        if (DEBUG) {
            System.err.println("%% INITIAL");
            for (WorkItem wi : initial) {
                System.err.println("%%\t" + wi);
            }
        }

        // Add all the initial items to the work queue
        q.addAll(initial);

        if (q.isEmpty()) {
            // There are no new items to process the previous results will suffice
            cachedSourceResponses.incrementAndGet();
            if (DEBUG) {
                System.err.println("%%\tUSING PREVIOUS");
            }
            return previous;
        }
        long start = System.currentTimeMillis();
        int count = 0;

        while (!q.isEmpty()) {
            WorkItem p = q.poll();
            if (DEBUG) {
                System.err.println("%%\tQ " + p);
            }
            count++;
            if (count % 100000 == 0) {
                // Detect possible infinite loop
                System.err.println("Searching relevant from " + sourceCGNode);
                System.err.println("Processed " + count + " work items latest: " + p);
            }
            OrderedPair<IMethod, Context> cgNode = p.cgNode;
            switch (p.type) {
            case CALLERS:
                // explore the callers of this cg node
                this.addSourceQueryCallerDependency(query, cgNode);
                for (OrderedPair<CallSiteProgramPoint, Context> caller : g.getCallersOf(cgNode)) {
                    OrderedPair<IMethod, Context> callerCGNode = new OrderedPair<>(caller.fst().containingProcedure(),
                                                                                   caller.snd());

                    // if callerCGNode becomes relevant in the future, then cgNode will also be relevant.
                    resultsChanged |= addToMapSet(relevanceDependencies, callerCGNode, cgNode);
                    if (DEBUG) {
                        System.err.println("%%\t\t" + cgNode);
                        System.err.println("%%\t\tDEPENDS ON " + callerCGNode);
                    }

                    // since we are exploring the callers of cgNode, for each caller of cgNode, callerCGNode,
                    // we want to visit both the callers and the callees of callerCGNode.
                    WorkItem callersWorkItem = new WorkItem(callerCGNode, CGEdgeType.CALLERS);
                    if (allVisited.add(callersWorkItem)) {
                        q.add(callersWorkItem);
                    }

                    // Only add the callees in the caller that are after the return site
                    Set<CallSiteProgramPoint> after = getCallsitesAfter(caller.fst());
                    for (CallSiteProgramPoint cspp : after) {
                        WorkItem singleCalleeWorkItem = new WorkItem(cspp, caller.snd());
                        if (allVisited.add(singleCalleeWorkItem)) {
                            q.add(singleCalleeWorkItem);
                        }
                    }
                }
                break;
            case CALLEES:
                // explore the callees of this cg node
                for (ProgramPointReplica callSite : g.getCallSitesWithinMethod(cgNode)) {
                    assert callSite.getPP() instanceof CallSiteProgramPoint;
                    this.addSourceQueryCalleeDependency(query, callSite);
                    for (OrderedPair<IMethod, Context> callee : g.getCalleesOf(callSite)) {
                        // if callee becomes relevant in the future, then cgNode will also be relevant.
                        resultsChanged |= addToMapSet(relevanceDependencies, callee, cgNode);
                        if (DEBUG) {
                            System.err.println("%%\t\t" + cgNode);
                            System.err.println("%%\t\tDEPENDS ON " + callee);
                        }

                        // We are exploring only the callees of cgNode, so when we explore callee
                        // we only need to explore its callees (not its callers).
                        WorkItem calleeWorkItem = new WorkItem(callee, CGEdgeType.CALLEES);
                        if (allVisited.add(calleeWorkItem)) {
                            q.add(calleeWorkItem);
                        }
                    }
                }
                break;
            case PRECISE_CALLEE:
                // This is a precise call site add callees for any possible targets to the queue
                assert p.callSite != null;
                ProgramPointReplica callSiteReplica = p.callSite.getReplica(p.cgNode.snd());
                this.addSourceQueryCalleeDependency(query, callSiteReplica);
                for (OrderedPair<IMethod, Context> callee : g.getCalleesOf(callSiteReplica)) {
                    // if callee becomes relevant in the future, then cgNode will also be relevant.
                    resultsChanged |= addToMapSet(relevanceDependencies, callee, cgNode);
                    if (DEBUG) {
                        System.err.println("%%\t\t" + cgNode);
                        System.err.println("%%\t\tDEPENDS ON " + callee);
                    }

                    // We are exploring only the callees of the call-site, so when we explore callee
                    // we only need to explore its callees (not its callers).
                    if (allVisited.add(new WorkItem(callee, CGEdgeType.CALLEES))) {
                        q.add(new WorkItem(callee, CGEdgeType.CALLEES));
                    }
                }
                break;
            }
        }

        // Record the results and rerun any dependencies
        SourceQueryResults sqr = new SourceQueryResults(relevanceDependencies, allVisited);
        if (resultsChanged) {
            recordSourceQueryResultsChanged(query);
        }
        totalSourceTime.addAndGet(System.currentTimeMillis() - start);

        return sqr;
    }

    /**
     * Record the results of running a query to find to relevant nodes for queries from a source call graph node to a
     * destination call graph node
     *
     * @param relevantQuery query to find relevant nodes
     * @param results call graph nodes that are relevant to queries from a program point in the source to a program
     *            point in the destination
     */
    private void recordRelevantNodesResults(RelevantNodesQuery relevantQuery, Set<OrderedPair<IMethod, Context>> results) {
        long start = System.currentTimeMillis();
        Set<OrderedPair<IMethod, Context>> s = relevantCache.get(relevantQuery);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<OrderedPair<IMethod, Context>> existing = relevantCache.putIfAbsent(relevantQuery, s);
            if (existing != null) {
                // someone beat us to recording the result.
                s = existing;
            }
        }

        if (s.addAll(results)) {
            // rerun queries that depend on the results of the relevant nodes query
            Set<ProgramPointSubQuery> deps = relevantNodesDependencies.get(relevantQuery);
            if (deps == null) {
                return;
            }

            Iterator<ProgramPointSubQuery> iter = deps.iterator();
            while (iter.hasNext()) {
                ProgramPointSubQuery mr = iter.next();
                // need to re-run the query
                if (!this.programPointReachability.requestRerunQuery(mr)) {
                    // no need to rerun this anymore, it was true
                    iter.remove();
                }
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
    private void addRelevantNodesDependency(ProgramPointSubQuery query, RelevantNodesQuery relevantQuery) {
        long start = System.currentTimeMillis();

        Set<ProgramPointSubQuery> s = relevantNodesDependencies.get(relevantQuery);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<ProgramPointSubQuery> existing = relevantNodesDependencies.putIfAbsent(relevantQuery, s);
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
    void calleeAddedTo(ProgramPointReplica callSite) {
        assert callSite.getPP() instanceof CallSiteProgramPoint;
        Set<SourceRelevantNodesQuery> queries = sourceQueryCalleeDependencies.get(callSite);
        if (queries != null) {
            // New work item that needs to be processed when the query is rerun
            Set<WorkItem> newItem = Collections.singleton(new WorkItem((CallSiteProgramPoint) callSite.getPP(),
                                                                       callSite.getContext()));
            for (SourceRelevantNodesQuery query : queries) {
                addStartItems(query, newItem);

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
    void callerAddedTo(OrderedPair<IMethod, Context> callGraphNode) {
        Set<SourceRelevantNodesQuery> queries = sourceQueryCallerDependencies.get(callGraphNode);
        if (queries != null) {
            // New work item that needs to be proccessed when the query is rerun
            Set<WorkItem> newItem = Collections.singleton(new WorkItem(callGraphNode, CGEdgeType.CALLERS));
            for (SourceRelevantNodesQuery query : queries) {
                addStartItems(query, newItem);

                // Make sure the query gets rerun by the analysis if it doesn't get triggered earlier
                this.analysisHandle.submitSourceRelevantNodesQuery(query);
            }
        }
    }

    //    /**
    //     * Query to find nodes that are relevant for a query from a program point in the source call graph nodes to a
    //     * program point in the destination call graph node
    //     */
    //    public static class RelevantNodesQuery {
    //        /**
    //         * Call graph node containing the source program point
    //         */
    //        final OrderedPair<IMethod, Context> sourceCGNode;
    //        /**
    //         * Call graph node containing the destination program point
    //         */
    //        final OrderedPair<IMethod, Context> destCGNode;
    //
    //        /**
    //         * Query to find nodes that are relevant for a query from a program point in the source call graph nodes to a
    //         * program point in the destination call graph node
    //         *
    //         * @param sourceCGNode Call graph node containing the source program point
    //         * @param destCGNode Call graph node containing the destination program point
    //         */
    //        public RelevantNodesQuery(OrderedPair<IMethod, Context> sourceCGNode, OrderedPair<IMethod, Context> destCGNode) {
    //            this.sourceCGNode = sourceCGNode;
    //            this.destCGNode = destCGNode;
    //        }
    //
    //        @Override
    //        public int hashCode() {
    //            final int prime = 31;
    //            int result = 1;
    //            result = prime * result + ((this.destCGNode == null) ? 0 : this.destCGNode.hashCode());
    //            result = prime * result + ((this.sourceCGNode == null) ? 0 : this.sourceCGNode.hashCode());
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
    //            if (this.destCGNode == null) {
    //                if (other.destCGNode != null) {
    //                    return false;
    //                }
    //            }
    //            else if (!this.destCGNode.equals(other.destCGNode)) {
    //                return false;
    //            }
    //            if (this.sourceCGNode == null) {
    //                if (other.sourceCGNode != null) {
    //                    return false;
    //                }
    //            }
    //            else if (!this.sourceCGNode.equals(other.sourceCGNode)) {
    //                return false;
    //            }
    //            return true;
    //        }
    //
    //        @Override
    //        public String toString() {
    //            return "Source: " + this.sourceCGNode + " Dest: " + this.destCGNode;
    //        }
    //    }

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
     * Work queue element of work, indicates which call graph edges to process next
     */
    private static class WorkItem {
        final CGEdgeType type;
        final OrderedPair<IMethod, Context> cgNode;
        final CallSiteProgramPoint callSite;

        /**
         * Create a callers or callees work item that will process all edges entering or leaving a call graph node
         * respectively
         *
         * @param cgNode current call graph node (for which the callees or callers should be processed)
         * @param type Either CALLEES or CALLERS indicating which type of edges should be processed
         */
        WorkItem(OrderedPair<IMethod, Context> cgNode, CGEdgeType type) {
            assert type != CGEdgeType.PRECISE_CALLEE;
            this.type = type;
            this.cgNode = cgNode;
            this.callSite = null;
        }

        /**
         * Create a work item for a particular call-site rather than processing all callees within an entire method
         *
         * @param callSite precise call site
         * @param context context for call-graph nodes the call-site appears in
         */
        WorkItem(CallSiteProgramPoint callSite, Context context) {
            this.type = CGEdgeType.PRECISE_CALLEE;
            this.cgNode = new OrderedPair<>(callSite.containingProcedure(), context);
            this.callSite = callSite;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((this.callSite == null) ? 0 : this.callSite.hashCode());
            result = prime * result + ((this.cgNode == null) ? 0 : this.cgNode.hashCode());
            result = prime * result + ((this.type == null) ? 0 : this.type.hashCode());
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
            WorkItem other = (WorkItem) obj;
            if (this.callSite == null) {
                if (other.callSite != null) {
                    return false;
                }
            }
            else if (!this.callSite.equals(other.callSite)) {
                return false;
            }
            if (this.cgNode == null) {
                if (other.cgNode != null) {
                    return false;
                }
            }
            else if (!this.cgNode.equals(other.cgNode)) {
                return false;
            }
            if (this.type != other.type) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "WorkItem\n\t\ttype=" + this.type + "\n\t\tcgNode=" + this.cgNode + "\n\t\tcallSite="
                    + this.callSite;
        }

    }

    /**
     * Indication of which type of edges to inspect while finding relevant nodes
     */
    private static enum CGEdgeType {
        /**
         * Callee edges (i.e. edges in the call graph _from_ a given call graph node)
         */
        CALLEES,
        /**
         * Caller edges (i.e. edges in the call graph _to_ a given call graph node)
         */
        CALLERS,
        /**
         * Edge for a paricular call site
         */
        PRECISE_CALLEE;
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
    private static <K, V> boolean addToMapSet(Map<K, Set<V>> map, K key, V elem) {
        Set<V> s = map.get(key);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
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
        Set<RelevantNodesQuery> deps = relevantNodeSourceQueryDependency.get(query);
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
        final ConcurrentMap<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>> relevanceDependencies;
        final Set<WorkItem> alreadyVisited;

        public SourceQueryResults(ConcurrentMap<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>> relevanceDependencies,
                                  Set<WorkItem> allVisited) {
            this.relevanceDependencies = relevanceDependencies;
            this.alreadyVisited = allVisited;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((this.alreadyVisited == null) ? 0 : this.alreadyVisited.hashCode());
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
            if (this.alreadyVisited == null) {
                if (other.alreadyVisited != null) {
                    return false;
                }
            }
            else if (!this.alreadyVisited.equals(other.alreadyVisited)) {
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
    Set<OrderedPair<IMethod, Context>> computeRelevantNodes(RelevantNodesQuery relevantQuery) {
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
            // XXX Ignore the cached value for now. There may be a way to use it once we figure out how to clear the startItems.
        }
        sqr = computeSourceDependencies(sourceQuery);

        Map<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>> deps = sqr.relevanceDependencies;

        /*
         * The set of relevant cg nodes, ie., an overapproximation of nodes that are on
         * a (valid) path from the source to the destination. This method finds this set.
         */
        final Set<OrderedPair<IMethod, Context>> relevant = new HashSet<>();
        final Deque<OrderedPair<IMethod, Context>> newlyRelevant = new ArrayDeque<>();
        if (relevantQuery.destCGNode.equals(relevantQuery.sourceCGNode)) {
            // Special case when the source and destination nodes are the same
            newlyRelevant.add(relevantQuery.sourceCGNode);
        }

        if (deps.get(relevantQuery.destCGNode) != null) {
            // The destination is reachable from the source
            // The destination is relevant
            newlyRelevant.add(relevantQuery.destCGNode);
        }
        if (DEBUG && newlyRelevant.isEmpty()) {
            System.err.println("UNREACHABLE " + relevantQuery);
        }
        if (DEBUG) {
            System.err.println("%% PROCESSING DEPENDENCIES");
        }
        while (!newlyRelevant.isEmpty()) {
            OrderedPair<IMethod, Context> cg = newlyRelevant.poll();
            if (DEBUG) {
                System.err.println("%%\tnewly relevant " + cg);
            }
            if (relevant.add(cg)) {
                // cg has become relevant, so use relevanceDependencies to figure out
                // what other nodes are now relevant.
                Set<OrderedPair<IMethod, Context>> s = deps.get(cg);
                if (DEBUG) {
                    if (s != null) {
                        for (OrderedPair<IMethod, Context> dep : s) {
                            System.err.println("%%\t\tdep " + dep);
                        }
                    }
                    else {
                        System.err.println(("%%\t\tNO DEPS"));
                    }
                }
                if (s != null) {
                    newlyRelevant.addAll(s);
                }
            }
        }
        assert relevant.isEmpty() || relevant.contains(relevantQuery.sourceCGNode);

        // Record the results and rerun any dependencies if they changed
        recordRelevantNodesResults(relevantQuery, relevant);
        totalTime.addAndGet(System.currentTimeMillis() - start);
        totalSize.addAndGet(relevant.size());

        if (COMPARE_WITH_OLD_ALGORITHM) {
            compareWithOldAlgorithm(relevantQuery.sourceCGNode, relevantQuery.destCGNode, sqr, relevant);
        }

        if (PRINT_DIAGNOSTICS && computedResponses.get() % 1000 == 0) {
            printDiagnostics();
        }

        // Reset the DEBUG variable after each call to this method
        DEBUG = false;
        return relevant;
    }

    /////////////////////////
    // Dependencies
    ////////////////////////

    /**
     * Dependencies: if the callers of the key CGNode change then recompute the source queries in the set mapped to that
     * key
     */
    ConcurrentMap<OrderedPair<IMethod, Context>, Set<SourceRelevantNodesQuery>> sourceQueryCallerDependencies = AnalysisUtil.createConcurrentHashMap();
    /**
     * Dependencies: if the callees for the key call-site change then recompute the source queries in the set mapped to
     * that key
     */
    ConcurrentMap<ProgramPointReplica, Set<SourceRelevantNodesQuery>> sourceQueryCalleeDependencies = AnalysisUtil.createConcurrentHashMap();
    /**
     * Dependencies: if the results for the source query starting at a key changes then recompute the relevant nodes
     * queries mapped to that key
     */
    ConcurrentMap<SourceRelevantNodesQuery, Set<RelevantNodesQuery>> relevantNodeSourceQueryDependency = AnalysisUtil.createConcurrentHashMap();

    /**
     * Record a dependency of sourceCGNode on the callers of calleeCGNode. If the callers of calleeCGNode change then
     * recompute the dependencies starting at sourceCGNode
     *
     * @param query query starting at a particular source that computes dependencies
     * @param calleeCGNode CG node the source query dependends on
     */
    private void addSourceQueryCallerDependency(SourceRelevantNodesQuery query,
                                                OrderedPair<IMethod, Context> calleeCGNode) {
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
    private void addSourceQueryCalleeDependency(SourceRelevantNodesQuery query, ProgramPointReplica callSite) {
        assert callSite.getPP() instanceof CallSiteProgramPoint;
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

        Set<RelevantNodesQuery> s = relevantNodeSourceQueryDependency.get(sourceQuery);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<RelevantNodesQuery> existing = relevantNodeSourceQueryDependency.putIfAbsent(sourceQuery, s);
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
        final OrderedPair<IMethod, Context> sourceCGNode;

        /**
         * Query that computes the dependencies needed to compute relevant node queries from a source node.
         *
         * @param sourceCGNode source node to run the query from
         */
        public SourceRelevantNodesQuery(OrderedPair<IMethod, Context> sourceCGNode) {
            assert sourceCGNode != null;
            this.sourceCGNode = sourceCGNode;
        }

        @Override
        public int hashCode() {
            return 31 + this.sourceCGNode.hashCode();
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
            if (!this.sourceCGNode.equals(other.sourceCGNode)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return sourceCGNode.toString();
        }
    }

    ////////////////////////////////////////
    ///Diagnostic information can be removed without affecting algorithm
    ///////////////////////////////////////

    private final Set<OrderedPair<IMethod, Context>> sources = AnalysisUtil.createConcurrentSet();
    private final Set<OrderedPair<IMethod, Context>> targets = AnalysisUtil.createConcurrentSet();

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

    // Timing information for source queries
    private AtomicLong startItemTime = new AtomicLong(0);
    private AtomicLong calleeDepTime = new AtomicLong(0);
    private AtomicLong callerDepTime = new AtomicLong(0);
    private AtomicLong totalSourceTime = new AtomicLong(0);
    private AtomicLong recordSourceQueryTime = new AtomicLong(0);

    private void printDiagnostics() {
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

        double size = totalSize.get();
        System.err.println("Total: " + analysisTime + "s;");
        System.err.println("RELEVANT NODES QUERY EXECUTION");
        System.err.println("    computeRelevantNodes: " + relevantTime + "s; RATIO: " + (relevantTime / analysisTime));
        System.err.println("    recordRelevantNodesResults: " + recordRelTime + "s; RATIO: "
                + (recordRelTime / relevantTime));
        System.err.println("    size: " + size + " average: " + size / computedResponses.get());
        System.err.println("    sources: " + sources.size() + " sources/computed: " + ((double) sources.size())
                / computedResponses.get());
        System.err.println("    targets: " + targets.size() + " targets/computed: " + ((double) targets.size())
                / computedResponses.get());

        System.err.println("SOURCE QUERY EXECUTION");
        System.err.println("    computeSourceDependencies: " + sourceTime + "s; RATIO: " + (sourceTime / analysisTime));
        System.err.println("    addSourceQueryCalleeDependency: " + calleeTime + "s; RATIO: "
                + (calleeTime / sourceTime));
        System.err.println("    addSourceQueryCallerDependency: " + callerTime + "s; RATIO: "
                + (callerTime / sourceTime));
        System.err.println("    addStartItems: " + startItem + "s; RATIO: " + (startItem / sourceTime));
        System.err.println("    addRelevantNodesSourceQueryDependency: " + queryTime + "s; RATIO: "
                + (queryTime / sourceTime));
        System.err.println("    recordSourceQueryResults: " + recordSourceTime + "s; RATIO: "
                + (recordSourceTime / sourceTime) + ";");
        System.err.println("\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
    }

    /**
     * Compare the previously computed results for this algorithm with a non-incremental (simpler) version. Assert false
     * on error.
     *
     * @param source source node of the relevant node query
     * @param dest destination node for the relevant node query
     * @param sqr cached results after running the source query
     * @param relevantNodes relevant nodes computed by the incremental algorithm
     */
    private void compareWithOldAlgorithm(OrderedPair<IMethod, Context> source, OrderedPair<IMethod, Context> dest,
                                         SourceQueryResults sqr, Set<OrderedPair<IMethod, Context>> relevantNodes) {
        RelevantNodes normalRelevant = new RelevantNodes(g, analysisHandle, programPointReachability);
        Set<OrderedPair<IMethod, Context>> prevRelevantNodes = normalRelevant.computeRelevantNodes(new RelevantNodesQuery(source,
                                                                                                                          dest));
        if (!relevantNodes.equals(prevRelevantNodes)) {
            System.err.println("Computing relevant from " + source + " to " + dest);
            System.err.println("Incremental computed:");
            if (relevantNodes.isEmpty()) {
                System.err.println("\tEMPTY");
            }
            for (OrderedPair<IMethod, Context> rn : relevantNodes) {
                System.err.println("\t" + rn);
            }
            System.err.println("Old method computed:");
            if (prevRelevantNodes.isEmpty()) {
                System.err.println("\tEMPTY");
            }
            for (OrderedPair<IMethod, Context> rn : prevRelevantNodes) {
                System.err.println("\t" + rn);
            }

            System.err.println("Dependencies for incremental:");
            for (OrderedPair<IMethod, Context> rd : sqr.relevanceDependencies.keySet()) {
                System.err.println("\t" + rd);
                for (OrderedPair<IMethod, Context> dep : sqr.relevanceDependencies.get(rd)) {
                    System.err.println("\t\t" + dep);
                }
            }

            System.err.println("Visited by incremental:");
            for (WorkItem item : sqr.alreadyVisited) {
                System.err.println("\t" + item);
            }
            assert false;
        }
    }
}
