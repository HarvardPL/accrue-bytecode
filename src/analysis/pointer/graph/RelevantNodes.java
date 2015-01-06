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

import util.OrderedPair;
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

    private final Map<RelevantNodesQuery, Set<OrderedPair<IMethod, Context>>> cache = AnalysisUtil.createConcurrentHashMap();

    // Dependencies for the findRelevantNodes queries
    private final ConcurrentMap<ProgramPointReplica, Set<RelevantNodesQuery>> findRelevantNodesCalleeDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<RelevantNodesQuery>> findRelevantNodesCallerDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<RelevantNodesQuery, Set<ProgramPointSubQuery>> relevantNodesDependencies = AnalysisUtil.createConcurrentHashMap();

    private int totalRequests = 0;
    private int cachedResponses = 0;
    private int computedResponses = 0;
    private long totalTime = 0;
    private long calleeDepTime = 0;

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
            totalRequests++;
            cachedResponses++;
        }

        if ((totalRequests % 1000) == 0) {
            System.err.println("\nTotal requests: " + totalRequests + "  ;  " + cachedResponses + "  cached "
                    + computedResponses + " computed (" + (int) (100 * ((float) cachedResponses / totalRequests))
                    + "% hit rate)");
            double analysisTime = (System.currentTimeMillis() - PointsToAnalysis.startTime) / 1000.0;
            double relevantTime = totalTime / 1000.0;
            double calleeTime = calleeDepTime / 1000.0;
            System.err.println("Total: " + analysisTime + "s;  computeRelevantNodes: " + relevantTime
                    + "s; RATIO: " + (relevantTime / analysisTime) + "; addFindRelevantNodesCalleeDependency: "
                    + calleeTime + "s; RATIO: " + (calleeTime / analysisTime));
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
        totalRequests++;
        computedResponses++;
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
        totalTime += (System.currentTimeMillis() - start);
        return relevant;
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
        Set<OrderedPair<IMethod, Context>> s = cache.get(relevantQuery);
        if (s == null || !s.equals(results)) {
            cache.put(relevantQuery, results);
            // rerun queries that depend on the results of the relevant nodes query
            Set<ProgramPointSubQuery> deps = relevantNodesDependencies.get(relevantQuery);
            if (deps == null) {
                // no dependencies, this must be the first time we ran the find relevant query
                assert s == null;
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
        calleeDepTime += (System.currentTimeMillis() - start);
    }

    /**
     * Record the fact that the results of a findRelevantNodes call depends on the callers of a given call graph node
     *
     * @param relevantQuery query to find relevant nodes from a source to a destination call graph node
     * @param caller call graph node for the caller
     */
    private void addFindRelevantNodesCallerDependency(RelevantNodesQuery relevantQuery,
                                                      OrderedPair<IMethod, Context> caller) {
        Set<RelevantNodesQuery> s = findRelevantNodesCallerDependencies.get(caller);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<RelevantNodesQuery> existing = findRelevantNodesCallerDependencies.putIfAbsent(caller, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(relevantQuery);
    }

    /**
     * Record the fact that the results of the query depends on the relevant nodes between the source and dest
     *
     * @param query query that depends on the relevant nodes
     * @param relevantQuery query for relevant nodes from a source to a destination
     */
    private void addRelevantNodesDependency(ProgramPointSubQuery query, RelevantNodesQuery relevantQuery) {
        Set<ProgramPointSubQuery> s = relevantNodesDependencies.get(relevantQuery);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<ProgramPointSubQuery> existing = relevantNodesDependencies.putIfAbsent(relevantQuery, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
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
        CALLERS;
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

}
