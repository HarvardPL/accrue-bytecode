package analysis.pointer.graph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
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
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public final class RelevantNodes {

    private final PointsToGraph g;
    private final ProgramPointReachability programPointReachability;

    /**
     * A reference to allow us to submit a query for reprocessing
     */
    private final PointsToAnalysisHandle analysisHandle;

    private final ConcurrentMap<RelevantNodesQuery, Set<OrderedPair<IMethod, Context>>> cache = AnalysisUtil.createConcurrentHashMap();

    // Dependencies for the findRelevantNodes queries
    private final ConcurrentMap<ProgramPointReplica, Set<RelevantNodesQuery>> findRelevantNodesCalleeDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<RelevantNodesQuery>> findRelevantNodesCallerDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<RelevantNodesQuery, Set<ProgramPointSubQuery>> relevantNodesDependencies = AnalysisUtil.createConcurrentHashMap();

    private AtomicInteger totalRequests = new AtomicInteger(0);
    private AtomicInteger cachedResponses = new AtomicInteger(0);
    private AtomicInteger computedResponses = new AtomicInteger(0);
    private AtomicLong totalTime = new AtomicLong(0);
    private AtomicLong calleeDepTime = new AtomicLong(0);
    private AtomicLong callerDepTime = new AtomicLong(0);
    private AtomicLong queryDepTime = new AtomicLong(0);
    private AtomicLong recordRelevantTime = new AtomicLong(0);
    private AtomicInteger totalSize = new AtomicInteger(0);
    private AtomicInteger recomputedResponses = new AtomicInteger(0);

    RelevantNodes(PointsToGraph g, PointsToAnalysisHandle analysisHandle,
                  ProgramPointReachability programPointReachability) {
        this.g = g;
        this.analysisHandle = analysisHandle;
        this.programPointReachability = programPointReachability;
    }

    public Set<OrderedPair<IMethod, Context>> relevantNodes(OrderedPair<IMethod, Context> source,
                                                            OrderedPair<IMethod, Context> dest,
                                                            ProgramPointSubQuery query) {
        RelevantNodesQuery relevantQuery = new RelevantNodesQuery(source, dest);

        // add a dependency
        addRelevantNodesDependency(query, relevantQuery);

        // check the cache.
        Set<OrderedPair<IMethod, Context>> relevantNodes = cache.get(relevantQuery);
        if (relevantNodes == null) {
            relevantNodes = computeRelevantNodes(relevantQuery);
        }
        else {
            this.totalRequests.incrementAndGet();
            this.cachedResponses.incrementAndGet();
        }

        return relevantNodes;
    }

    /**
     * Find call graph nodes that must be searched deeply for a path from the source to destination for the given query.
     * Irrelevant nodes can have their effects (kill and alloc sets) summarized.
     *
     * @param sourceCGNode call graph node containing the source program point
     * @param destinationCGNode call graph node containing the destination program point
     *
     * @return the set of call graph nodes that cannot be summarized
     */
    public Set<OrderedPair<IMethod, Context>> computeRelevantNodes(RelevantNodesQuery relevantQuery) {
        totalRequests.incrementAndGet();
        computedResponses.incrementAndGet();
        if (cache.get(relevantQuery) != null) {
            // this is a recomputation due to a dependency trigger
            recomputedResponses.incrementAndGet();
        }
        long start = System.currentTimeMillis();

        OrderedPair<IMethod, Context> sourceCGNode = relevantQuery.sourceCGNode;
        OrderedPair<IMethod, Context> destinationCGNode = relevantQuery.destCGNode;

        /*
         * The set of relevant cg nodes, ie., an overapproximation of nodes that are on
         * a (valid) path from the source to the destination. This method finds this set.
         */
        Set<OrderedPair<IMethod, Context>> relevant = new LinkedHashSet<>();

        /*
         * We maintain dependencies, so that if cg node a is in the set relevanceDependencies.get(b),
         * then if b becomes a relevant node, then a is also a relevant node. We can think of this
         * relevanceDependencies as describing the edges in the CFG that are reachable on a (valid)
         * path from the source cg node.
         */
        Map<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>> relevanceDependencies = new HashMap<>();

        // The queue the cg node we are currently considering. The boolean indicates whether
        // we should look at the caller edges or the callee edges. You can think of
        // the pair <m, CALLERS> as being short hand for the set of call graph edges going into m (callers of m),
        // and <m, CALLEES> as being short hand for the set of call graph edges going from m (callees from m).
        Deque<WorkItem> q = new ArrayDeque<>();

        // Initialize the workqueue
        q.add(new WorkItem(sourceCGNode, CGEdgeType.CALLEES));
        q.add(new WorkItem(sourceCGNode, CGEdgeType.CALLERS));

        Set<WorkItem> allVisited = new HashSet<>();
        Deque<OrderedPair<IMethod, Context>> newlyRelevant = new ArrayDeque<>();
        int count = 0;

        while (!q.isEmpty()) {
            WorkItem p = q.poll();
            count++;
            if (count % 100000 == 0) {
                // Detect infinite loop
                System.err.println("Searching relevant from " + sourceCGNode + " to " + destinationCGNode);
                System.err.println("Processed " + count + " work items latest: " + p);
            }
            OrderedPair<IMethod, Context> cgNode = p.cgNode;
            boolean isDestinationCGNode = false;
            if (cgNode.equals(destinationCGNode)) {
                newlyRelevant.add(cgNode);
                isDestinationCGNode = true;
            }
            switch (p.type) {
            case CALLERS :
                // explore the callers of this cg node
                this.addFindRelevantNodesCallerDependency(relevantQuery, cgNode);
                for (OrderedPair<CallSiteProgramPoint, Context> caller : g.getCallersOf(cgNode)) {
                    OrderedPair<IMethod, Context> callerCGNode = new OrderedPair<>(caller.fst().containingProcedure(),
                                                                                   caller.snd());

                    if (relevant.contains(callerCGNode)) {
                        // since callerCGNode is relevant, so is cgNode.
                        newlyRelevant.add(cgNode);
                    }
                    else {
                        // if callerCGNode becomes relevant in the future, then cgNode will also be relevant.
                        addToMapSet(relevanceDependencies, callerCGNode, cgNode);
                    }

                    // since we are exploring the callers of cgNode, for each caller of cgNode, callerCGNode,
                    // we want to visit both the callers and the callees of callerCGNode.
                    WorkItem callersWorkItem = new WorkItem (callerCGNode, CGEdgeType.CALLERS);
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
            case CALLEES :
                if (isDestinationCGNode) {
                    // Note that as an optimization, if this is the destination node, we do not need to explore the callees,
                    // since if there is a path from the source to the destination using the callees of the destination,
                    // then there is a path from the source to the destination without using the callees of the destination.
                    // (Note that we should *not* apply this optimization for CALLERS, since this would be unsound e.g.,
                    // if the source and destination nodes are the same.)
                    break;
                }
                // explore the callees of this cg node
                for (ProgramPointReplica callSite : g.getCallSitesWithinMethod(cgNode)) {
                    assert callSite.getPP() instanceof CallSiteProgramPoint;
                    this.addFindRelevantNodesCalleeDependency(relevantQuery, callSite);
                    for (OrderedPair<IMethod, Context> callee : g.getCalleesOf(callSite)) {
                        if (relevant.contains(callee)) {
                            // since callee is relevant, so is cgNode.
                            newlyRelevant.add(cgNode);
                        }
                        else {
                            // if callee becomes relevant in the future, then cgNode will also be relevant.
                            addToMapSet(relevanceDependencies, callee, cgNode);
                        }
                        // We are exploring only the callees of cgNode, so when we explore callee
                        // we only need to explore its callees (not its callers).
                        if (allVisited.add(new WorkItem(callee, CGEdgeType.CALLEES))) {
                            q.add(new WorkItem(callee, CGEdgeType.CALLEES));
                        }
                    }
                }
                break;
            case PRECISE_CALLEE:
                // This is a precise call site add callees for any possible targets to the queue
                assert p.callSite != null;
                ProgramPointReplica callSiteReplica = p.callSite.getReplica(p.cgNode.snd());
                this.addFindRelevantNodesCalleeDependency(relevantQuery, callSiteReplica);
                for (OrderedPair<IMethod, Context> callee : g.getCalleesOf(callSiteReplica)) {
                    if (relevant.contains(callee)) {
                        // since callee is relevant, so is cgNode.
                        newlyRelevant.add(cgNode);
                    }
                    else {
                        // if callee becomes relevant in the future, then cgNode will also be relevant.
                        addToMapSet(relevanceDependencies, callee, cgNode);
                    }
                    // We are exploring only the callees of the call-site, so when we explore callee
                    // we only need to explore its callees (not its callers).
                    if (allVisited.add(new WorkItem(callee, CGEdgeType.CALLEES))) {
                        q.add(new WorkItem(callee, CGEdgeType.CALLEES));
                    }
                }
                break;
            }

            while (!newlyRelevant.isEmpty()) {
                OrderedPair<IMethod, Context> cg = newlyRelevant.poll();
                if (relevant.add(cg)) {
                    // cg has become relevant, so use relevanceDependencies to figure out
                    // what other nodes are now relevant.
                    Set<OrderedPair<IMethod, Context>> s = relevanceDependencies.remove(cg);
                    if (s != null) {
                        newlyRelevant.addAll(s);
                    }
                }
            }

        }
        // Record the results and rerun any dependencies
        recordRelevantNodesResults(relevantQuery, relevant);
        totalTime.addAndGet(System.currentTimeMillis() - start);
        totalSize.addAndGet(relevant.size());
        if (computedResponses.get() % 1000 == 0) {
            printDiagnostics();
        }
        return relevant;
    }

    /**
     * Find call graph nodes that must be searched deeply for a path from the source to destination for the given query.
     * Irrelevant nodes can have their effects (kill and alloc sets) summarized.
     *
     * @param sourceCGNode call graph node containing the source program point
     * @param destinationCGNode call graph node containing the destination program point
     *
     * @return the set of call graph nodes that cannot be summarized
     */
    @Deprecated
    public Set<OrderedPair<IMethod, Context>> oldComputeRelevantNodes(RelevantNodesQuery relevantQuery) {
        totalRequests.incrementAndGet();
        computedResponses.incrementAndGet();
        long start = System.currentTimeMillis();

        OrderedPair<IMethod, Context> sourceCGNode = relevantQuery.sourceCGNode;
        OrderedPair<IMethod, Context> destinationCGNode = relevantQuery.destCGNode;

        /*
         * The set of relevant cg nodes, ie., an overapproximation of nodes that are on
         * a (valid) path from the source to the destination. This method finds this set.
         */
        Set<OrderedPair<IMethod, Context>> relevant = new LinkedHashSet<>();

        /*
         * We maintain dependencies, so that if cg node a is in the set relevanceDependencies.get(b),
         * then if b becomes a relevant node, then a is also a relevant node. We can think of this
         * relevanceDependencies as describing the edges in the CFG that are reachable on a (valid)
         * path from the source cg node.
         */
        Map<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>> relevanceDependencies = new HashMap<>();

        // The queue the cg node we are currently considering. The boolean indicates whether
        // we should look at the caller edges or the callee edges. You can think of
        // the pair <m, CALLERS> as being short hand for the set of call graph edges going into m (callers of m),
        // and <m, CALLEES> as being short hand for the set of call graph edges going from m (callees from m).
        Deque<OrderedPair<OrderedPair<IMethod, Context>, CGEdgeType>> q = new ArrayDeque<>();

        // Initialize the workqueue
        q.add(new OrderedPair<>(sourceCGNode, CGEdgeType.CALLEES));
        q.add(new OrderedPair<>(sourceCGNode, CGEdgeType.CALLERS));

        Set<OrderedPair<OrderedPair<IMethod, Context>, CGEdgeType>> allVisited = new HashSet<>();
        Deque<OrderedPair<IMethod, Context>> newlyRelevant = new ArrayDeque<>();

        while (!q.isEmpty()) {

            OrderedPair<OrderedPair<IMethod, Context>, CGEdgeType> p = q.poll();
            OrderedPair<IMethod, Context> cgNode = p.fst();
            boolean isDestinationCGNode = false;
            if (cgNode.equals(destinationCGNode)) {
                newlyRelevant.add(cgNode);
                isDestinationCGNode = true;
            }

            if (p.snd() == CGEdgeType.CALLERS) {
                // explore the callers of this cg node
                this.addFindRelevantNodesCallerDependency(relevantQuery, cgNode);
                for (OrderedPair<CallSiteProgramPoint, Context> caller : g.getCallersOf(cgNode)) {
                    OrderedPair<IMethod, Context> callerCGNode = new OrderedPair<>(caller.fst().containingProcedure(),
                                                                                   caller.snd());

                    if (relevant.contains(callerCGNode)) {
                        // since callerCGNode is relevant, so is cgNode.
                        newlyRelevant.add(cgNode);
                    }
                    else {
                        // if callerCGNode becomes relevant in the future, then cgNode will also be relevant.
                        addToMapSet(relevanceDependencies, callerCGNode, cgNode);
                    }

                    // since we are exploring the callers of cgNode, for each caller of cgNode, callerCGNode,
                    // we want to visit both the callers and the callees of callerCGNode.
                    OrderedPair<OrderedPair<IMethod, Context>, CGEdgeType> callersWorkItem = new OrderedPair<>(callerCGNode,
                                                                                                               CGEdgeType.CALLERS);
                    if (allVisited.add(callersWorkItem)) {
                        q.add(callersWorkItem);
                    }
                    OrderedPair<OrderedPair<IMethod, Context>, CGEdgeType> calleesWorkItem = new OrderedPair<>(callerCGNode,
                                                                                                               CGEdgeType.CALLEES);
                    if (allVisited.add(calleesWorkItem)) {
                        q.add(calleesWorkItem);
                    }
                }
            }
            else if (!isDestinationCGNode) {
                // explore the callees of this cg node
                // Note that as an optimization, if this is the destination node, we do not need to explore the callees,
                // since if there is a path from the source to the destination using the callees of the destination,
                // then there is a path from the source to the destination without using the callees of the destination.
                // (Note that we should *not* apply this optimization for CALLERS, since this would be unsound e.g.,
                // if the source and destination nodes are the same.)
                for (ProgramPointReplica callSite : g.getCallSitesWithinMethod(cgNode)) {
                    assert callSite.getPP() instanceof CallSiteProgramPoint;
                    this.addFindRelevantNodesCalleeDependency(relevantQuery, callSite);
                    for (OrderedPair<IMethod, Context> callee : g.getCalleesOf(callSite)) {
                        if (relevant.contains(callee)) {
                            // since callee is relevant, so is cgNode.
                            newlyRelevant.add(cgNode);
                        }
                        else {
                            // if callee becomes relevant in the future, then cgNode will also be relevant.
                            addToMapSet(relevanceDependencies, callee, cgNode);
                        }
                        // We are exploring only the callees of cgNode, so when we explore callee
                        // we only need to explore its callees (not its callers).
                        if (allVisited.add(new OrderedPair<>(callee, CGEdgeType.CALLEES))) {
                            q.add(new OrderedPair<>(callee, CGEdgeType.CALLEES));
                        }
                    }
                }

            }

            while (!newlyRelevant.isEmpty()) {
                OrderedPair<IMethod, Context> cg = newlyRelevant.poll();
                if (relevant.add(cg)) {
                    // cg has become relevant, so use relevanceDependencies to figure out
                    // what other nodes are now relevant.
                    Set<OrderedPair<IMethod, Context>> s = relevanceDependencies.remove(cg);
                    if (s != null) {
                        newlyRelevant.addAll(s);
                    }
                }
            }

        }
        // Record the results and rerun any dependencies
        recordRelevantNodesResults(relevantQuery, relevant);
        totalTime.addAndGet(System.currentTimeMillis() - start);
        totalSize.addAndGet(relevant.size());
        if (computedResponses.get() % 1000 == 0) {
            printDiagnostics();
        }
        return relevant;
    }

    private void printDiagnostics() {
        System.err.println("\nTotal requests: " + totalRequests + "  ;  " + cachedResponses + "  cached "
                + computedResponses + " computed ("
                + (int) (100 * (cachedResponses.floatValue() / totalRequests.floatValue())) + "% hit rate)");
        System.err.println("\tRecomputed" + recomputedResponses.get() + " " + recomputedResponses.doubleValue()
                / computedResponses.doubleValue() + "% of all computed");
        double analysisTime = (System.currentTimeMillis() - PointsToAnalysis.startTime) / 1000.0;
        double relevantTime = totalTime.get() / 1000.0;
        double calleeTime = calleeDepTime.get() / 1000.0;
        double callerTime = callerDepTime.get() / 1000.0;
        double queryTime = queryDepTime.get() / 1000.0;
        double recordTime = recordRelevantTime.get() / 1000.0;
        double size = totalSize.get();
        System.err.println("Total: " + analysisTime + "s;");
        System.err.println("    size: " + size + " average: " + size / computedResponses.get());
        System.err.println("    computeRelevantNodes: " + relevantTime + "s; RATIO: " + (relevantTime / analysisTime)
                + ";");
        System.err.println("    addFindRelevantNodesCalleeDependency: " + calleeTime + "s; RATIO: "
                + (calleeTime / relevantTime));
        System.err.println("    addFindRelevantNodesCallerDependency: " + callerTime + "s; RATIO: "
                + (callerTime / relevantTime));
        System.err.println("    addRelevantNodesDependency: " + queryTime + "s; RATIO: " + (queryTime / relevantTime));
        System.err.println("    recordRelevantNodesResults: " + recordTime + "s; RATIO: " + (recordTime / relevantTime));
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
        Set<OrderedPair<IMethod, Context>> s = cache.get(relevantQuery);
        if (s == null) {
            s =  AnalysisUtil.createConcurrentSet();
            Set<OrderedPair<IMethod, Context>> existing = cache.putIfAbsent(relevantQuery, s);
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
     * Record the fact that the results of a findRelevantNodes call depends on the callees for the given call-site
     *
     * @param relevantQuery query to find relevant nodes from a source to a destination call graph node
     * @param callSite program point for the call-site
     */
    private void addFindRelevantNodesCalleeDependency(RelevantNodesQuery relevantQuery, ProgramPointReplica callSite) {
        assert callSite.getPP() instanceof CallSiteProgramPoint;
        long start = System.currentTimeMillis();

        Set<RelevantNodesQuery> s = findRelevantNodesCalleeDependencies.get(callSite);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<RelevantNodesQuery> existing = findRelevantNodesCalleeDependencies.putIfAbsent(callSite, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(relevantQuery);
        calleeDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * Record the fact that the results of a findRelevantNodes call depends on the callers of a given call graph node
     *
     * @param relevantQuery query to find relevant nodes from a source to a destination call graph node
     * @param caller call graph node for the caller
     */
    private void addFindRelevantNodesCallerDependency(RelevantNodesQuery relevantQuery,
                                                      OrderedPair<IMethod, Context> caller) {
        long start = System.currentTimeMillis();

        Set<RelevantNodesQuery> s = findRelevantNodesCallerDependencies.get(caller);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<RelevantNodesQuery> existing = findRelevantNodesCallerDependencies.putIfAbsent(caller, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(relevantQuery);
        callerDepTime.addAndGet(System.currentTimeMillis() - start);
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
     * We use this method to make sure that we re-run any method reachability results and (negative) queries depended on
     * the call site.
     *
     * @param callSite
     */
    void calleeAddedTo(ProgramPointReplica callSite) {
        assert callSite.getPP() instanceof CallSiteProgramPoint;
        Set<RelevantNodesQuery> queries = findRelevantNodesCalleeDependencies.get(callSite);
        if (queries != null) {
            for (RelevantNodesQuery q : queries) {
                // clear cache
                this.cache.remove(q);
                // recompute the query.
                // Could do something smarter and incrementally change the result
                this.analysisHandle.submitRelevantNodesQuery(q);
            }
        }
    }

    /**
     * This method is invoked to let us know that a new edge in the call graph has been added that goes to the
     * callGraphNode
     *
     * We use this method to make sure that we re-run any queries that depended on the call site.
     *
     * @param callSite
     */
    void callerAddedTo(OrderedPair<IMethod, Context> callGraphNode) {
        Set<RelevantNodesQuery> queries = findRelevantNodesCallerDependencies.get(callGraphNode);
        if (queries != null) {
            for (RelevantNodesQuery q : queries) {
                // clear cache
                this.cache.remove(q);
                // recompute the query.
                // Could do something smarter and incrementally change the result
                this.analysisHandle.submitRelevantNodesQuery(q);
            }
        }

    }

    /**
     * Query to find nodes that are relevant for a query from a program point in the source call graph nodes to a
     * program point in the destination call graph node
     */
    public static class RelevantNodesQuery {
        /**
         * Call graph node containing the source program point
         */
        final OrderedPair<IMethod, Context> sourceCGNode;
        /**
         * Call graph node containing the destination program point
         */
        final OrderedPair<IMethod, Context> destCGNode;

        /**
         * Query to find nodes that are relevant for a query from a program point in the source call graph nodes to a
         * program point in the destination call graph node
         *
         * @param sourceCGNode Call graph node containing the source program point
         * @param destCGNode Call graph node containing the destination program point
         */
        public RelevantNodesQuery(OrderedPair<IMethod, Context> sourceCGNode, OrderedPair<IMethod, Context> destCGNode) {
            this.sourceCGNode = sourceCGNode;
            this.destCGNode = destCGNode;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((this.destCGNode == null) ? 0 : this.destCGNode.hashCode());
            result = prime * result + ((this.sourceCGNode == null) ? 0 : this.sourceCGNode.hashCode());
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
            if (this.destCGNode == null) {
                if (other.destCGNode != null) {
                    return false;
                }
            }
            else if (!this.destCGNode.equals(other.destCGNode)) {
                return false;
            }
            if (this.sourceCGNode == null) {
                if (other.sourceCGNode != null) {
                    return false;
                }
            }
            else if (!this.sourceCGNode.equals(other.sourceCGNode)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "Source: " + this.sourceCGNode + " Dest: " + this.destCGNode;
        }
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
            return "WorkItem [type=" + this.type + ", cgNode=" + this.cgNode + ", callSite=" + this.callSite + "]";
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
            s = new HashSet<>();
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
        Set<CallSiteProgramPoint> after = g.getRegistrar().getCallSiteOrdering(callSite.getContainingProcedure()).get(callSite);
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

}
