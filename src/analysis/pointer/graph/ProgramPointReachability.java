package analysis.pointer.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import util.OrderedPair;
import util.WorkQueue;
import util.intmap.ConcurrentIntMap;
import util.intset.EmptyIntSet;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;
import analysis.pointer.statements.ProgramPoint.PostProgramPoint;
import analysis.pointer.statements.ProgramPoint.PreProgramPoint;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;

/**
 * This class answers questions about what programs are reachable from what other program points, and caches answers
 * smartly.
 */
public class ProgramPointReachability {
    /**
     * Singleton instance of this class
     */
    private static ProgramPointReachability SINGLETON;

    /**
     * Keep a reference to the PointsToGraph for convenience.
     */
    private final PointsToGraph g;

    /**
     * A reference to allow us to submit a StmtAndContext for reprocessing
     */
    private final PointsToAnalysisHandle analysisHandle;

    /**
     * Get the singleton {@link ProgramPointReachability} instance (create if necessary)
     *
     * @param g points-to graph
     * @param analysisHandle handle to the points-to analysis
     * @return
     */
    public static ProgramPointReachability getOrCreate(PointsToGraph g, PointsToAnalysisHandle analysisHandle) {
        if (SINGLETON == null) {
            SINGLETON = new ProgramPointReachability(g, analysisHandle);
        }
        assert g == SINGLETON.g && analysisHandle == SINGLETON.analysisHandle;
        return SINGLETON;
    }

    /**
     * Create a new reachability query engine
     *
     * @param g points to graph
     * @param analysisHandle interface for submitting jobs to the pointer analysis
     */
    private ProgramPointReachability(PointsToGraph g, PointsToAnalysisHandle analysisHandle) {
        assert g != null && analysisHandle != null;
        this.g = g;
        this.analysisHandle = analysisHandle;
    }

    /**
     * Given a map from InterProgramPoints to KilledAndAlloceds, either get the existing KilledAndAlloced for ipp, or
     * create one that represents all-killed-all-allocated and add it to the map for ipp.
     *
     * @param results map from program point to KilledAndAlloced sets that may contain <code>ipp</code>
     * @param ipp program point to get the sets for
     *
     * @return killed points-to graph nodes and allocated instance keys for the given key
     */
    private static KilledAndAlloced getOrCreate(Map<InterProgramPoint, KilledAndAlloced> results, InterProgramPoint ipp) {
        KilledAndAlloced res = results.get(ipp);
        if (res == null) {
            res = KilledAndAlloced.createUnreachable();
            results.put(ipp, res);
        }
        return res;
    }

    /**
     * Can destination be reached from any InterProgramPointReplica in <code>ppsc</code> without going through a program
     * point that kills any PointsToGraphNode in noKill, and without going through a program point that allocates any
     * InstanceKey in noAlloc?
     *
     * @return true if the destination is reachable from any source
     */
    public boolean reachable(ProgramPointSetClosure ppsc, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc, ReachabilityQueryOrigin origin) {
        return reachableImpl(ppsc.getSources(this.g, origin),
                             destination,
                             noKill,
                             noAlloc,
                             Collections.<InterProgramPointReplica> emptySet(),
                             origin);
    }

    /**
     * Can destination be reached from source without going through any of the forbidden program points? If forbidden is
     * non empty, then all of the forbidden IPPRs must be in the same method and context as one of the source or the
     * destination.
     *
     * @return true if the destination is reachable from the source
     */
    public boolean reachable(InterProgramPointReplica source, InterProgramPointReplica destination,
                             Set<InterProgramPointReplica> forbidden, ReachabilityQueryOrigin origin) {
        return reachableImpl(Collections.singleton(source),
                             destination,
                             EmptyIntSet.INSTANCE,
                             EmptyIntSet.INSTANCE,
                             forbidden,
                             origin);
    }

    /**
     * Can destination be reached from any InterProgramPointReplica in sources without going through a program point
     * that kills any PointsToGraphNode in noKill, and without going through a program point that allocates any
     * InstanceKey in noAlloc?
     *
     * @return true if the destination is reachable from anys source
     */
    public boolean reachable(Collection<InterProgramPointReplica> sources, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc, ReachabilityQueryOrigin origin) {
        return reachableImpl(sources,
                             destination,
                             noKill,
                             noAlloc,
                             Collections.<InterProgramPointReplica> emptySet(),
                             origin);
    }

    /**
     * Can destination be reached from any InterProgramPointReplica in sources without going through a program point
     * that kills any PointsToGraphNode in noKill, and without going through a program point that allocates any
     * InstanceKey in noAlloc, and without going through any of the forbidden IPPRs? If forbidden is non empty, then all
     * of the forbidden IPPRs must be in the same method and context as one of the source or the destination.
     *
     * @return true if the destination is reachable from anys source
     */
    private boolean reachableImpl(Collection<InterProgramPointReplica> sources, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc,
                                  Set<InterProgramPointReplica> forbidden, ReachabilityQueryOrigin origin) {
        assert allMostRecent(noAlloc);
        assert allInSameMethodAndContext(forbidden, sources, destination);

        // check the caches
        List<InterProgramPointReplica> unknown = new ArrayList<>(sources.size());
        for (InterProgramPointReplica src : sources) {
            ProgramPointSubQuery mr = new ProgramPointSubQuery(src, destination, noKill, noAlloc, forbidden);
            if (this.positiveCache.contains(mr)) {
                // We have already computed that the destination is reachable from src
                return true;
            }
            addDependency(mr, origin);
            if (this.negativeCache.contains(mr)) {
                // We know it's a negative result for this one, keep looking
            }
            else {
                unknown.add(src);
            }
        }

        if (unknown.isEmpty()) {
            // all were negative!
            return false;
        }
        // The cache didn't help. Try getting an answer for the unknown elements.
        return computeQuery(unknown, destination, noKill, noAlloc, forbidden);
    }

    /**
     * Check whether every element of the set of forbidden nodes is in the same method and context as the source or the
     * destination.
     *
     * @param forbidden set of nodes to check against the sources and destination
     * @param sources sources of a reachability query (must be a singleton if the forbidden set is non-empty)
     * @param destination destination of a reachability query
     * @return true if each of the forbidden nodes is valid
     */
    private static boolean allInSameMethodAndContext(Set<InterProgramPointReplica> forbidden,
                                                     Collection<InterProgramPointReplica> sources,
                                                     InterProgramPointReplica destination) {
        if (forbidden.isEmpty()) {
            return true;
        }
        assert sources.size() == 1;
        InterProgramPointReplica agreement = null;
        for (InterProgramPointReplica ippr : forbidden) {
            if (agreement == null) {
                if (destination.getContainingProcedure() == ippr.getContainingProcedure()
                        && destination.getContext().equals(ippr.getContext())) {
                    agreement = destination;
                }
                else if ((agreement = sources.iterator().next()).getContainingProcedure() == ippr.getContainingProcedure()
                        && agreement.getContext().equals(ippr.getContext())) {
                    // OK!
                }
                else {
                    return false;
                }
            }
            else {
                if (agreement.getContainingProcedure() != ippr.getContainingProcedure()
                        || !agreement.getContext().equals(ippr.getContext())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Can destination be reached from any InterProgramPointReplica in sources without going through a program point
     * that kills any PointsToGraphNode in noKill, and without going through a program point that allocates any
     * InstanceKey in noAlloc, and without going through any of the forbidden IPPRs? If forbidden is non empty, then all
     * of the forbidden IPPRs must be in the same method and context as one of the source or the destination.
     *
     * @param sources sources of the reachability query
     * @param destination destination node
     * @param noKill set of points-to graph nodes that must not be killed
     * @param noAlloc set of instance keys that must not be allocated
     * @param forbidden set of program points that must not be passed through
     *
     * @return true if the destination is reachable from anys source
     */
    private boolean computeQuery(Collection<InterProgramPointReplica> sources, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc,
                                 Set<InterProgramPointReplica> forbidden) {

        ProgramPointDestinationQuery prq = new ProgramPointDestinationQuery(destination,
                                                                            noKill,
                                                                            noAlloc,
                                                                            forbidden,
                                                                            g,
                                                                            this);

        // try to solve it for each source.
        for (InterProgramPointReplica src : sources) {
            ProgramPointSubQuery query = new ProgramPointSubQuery(src, destination, noKill, noAlloc, forbidden);
            if (positiveCache.contains(query)) {
                // The result was computed by another thread before this thread ran
                return true;
            }

            // First check the call graph to find the set of call graph nodes that must be searched directly
            // (i.e. the effects for these nodes cannot be summarized).
            Set<OrderedPair<IMethod, Context>> relevantNodes = findRelevantNodes(query);

            if (relevantNodes.isEmpty()) {
                // this path isn't possible.
                if (recordQueryResult(query, false)) {
                    // We computed false, but the cache already had true
                    return true;
                }
                continue;
            }


            // Now try a search starting at the source
            if (prq.executeSubQuery(src, relevantNodes)) {
                recordQueryResult(query, true);
                return true;
            }
            if (recordQueryResult(query, false)) {
                // We computed false, but the cache already had true
                return true;
            }
        }
        // we didn't find it.
        return false;
    }

    /**
     * Record in the caches that the result of query mr is b. If this is a change (from negative to positive), then some
     * StmtAndContexts may need to be rerun. If set sacsToReprocess is non-null then the StmtAndContexts will be added
     * to the set. Otherwise, they will be submitted to the PointsToEngine.
     *
     * @param query query to record
     * @param b new result, true if the destination was reachable from the source, false otherwise
     *
     * @return Whether the actual result is "true" or "false". When recording false, it is possible to return true if
     *         the cache already has a positive result.
     */
    private boolean recordQueryResult(ProgramPointSubQuery query, boolean b) {
        if (b) {
            positiveCache.add(query);
            if (negativeCache.remove(query)) {
                // we previously thought it was negative.
                queryResultChanged(query);
            }
            return true;
        }

        // Recording a false result
        negativeCache.add(query);
        if (positiveCache.contains(query)) {
            this.negativeCache.remove(query);
            // A positive result has already been computed return it
            return true;
        }
        return false;
    }

    /**
     * Check if all the elements of <code>s</code> are "most recent" instance keys
     *
     * @param s set of instance keys
     * @return true if all the elements are "most recent"
     */
    private boolean allMostRecent(/*Set<InstanceKey>*/IntSet s) {
        IntIterator iter = s.intIterator();
        while (iter.hasNext()) {
            int ik = iter.next();
            if (!g.isMostRecentObject(ik)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Find call graph nodes, (Method, Context) pairs, that are relevant for the given query. A relevant call graph node
     * is one which appears on some path (a sequence of call and return edges) from the node containing the source to
     * the node containing the destination that does not contain a call edge and subsequently contain the corresponding
     * return edge.
     * <p>
     * If a CG node only appears on paths containing a call edge followed by the corresponding return edge then it is
     * safe to summarize the kill and alloc results from the entry to the exit since the method will never be entered or
     * exited in the middle (via a call or return) on a path from the source program point to the destination program
     * point.
     *
     * @param query query to get the interesting nodes for
     * @return Set of call graph nodes
     */
    private Set<OrderedPair<IMethod, Context>> findRelevantNodes(ProgramPointSubQuery query) {
        OrderedPair<IMethod, Context> sourceCGNode = new OrderedPair<>(query.source.getContainingProcedure(),
                                                                       query.source.getContext());
        OrderedPair<IMethod, Context> destinationCGNode = new OrderedPair<>(query.destination.getContainingProcedure(),
                                                                            query.destination.getContext());

        Set<OrderedPair<IMethod, Context>> relevant = new LinkedHashSet<>();
        if (sourceCGNode.equals(destinationCGNode)) {
            // Special case when the source and destination are the same
            relevant.add(sourceCGNode);
        }

        // The queue contains the next call graph edge to process and the edges
        // that have been seen before processing that edge
        Deque<OrderedPair<CallGraphEdge, Set<CallGraphEdge>>> q = new ArrayDeque<>();

        // Initialize workqueue with the edges leaving the source and an empty path
        for (CallGraphEdge sourceEdge : getOutGoingEdges(sourceCGNode, query)) {
            q.addFirst(new OrderedPair<>(sourceEdge, Collections.<CallGraphEdge> emptySet()));
        }

        Set<CallGraphEdge> allVisited = new LinkedHashSet<>();
        while (!q.isEmpty()) {
            OrderedPair<CallGraphEdge, Set<CallGraphEdge>> p = q.poll();
            CallGraphEdge e = p.fst();
            Set<CallGraphEdge> path = p.snd();

            if (e.isReturnEdge && path.contains(CallGraphEdge.getCallForReturn(e))) {
                // This is a return edge and we have already seen the corresponding call.
                // Cut off the search here.
                continue;
            }

            if (!allVisited.add(e)) {
                // We have already seen this edge on a valid path
                continue;
            }

            path = new LinkedHashSet<>(path);
            path.add(e);
            if (relevant.contains(e.getTarget()) || e.getTarget().equals(destinationCGNode)) {
                // The target is in the relevant set or it is the node we are looking for
                // All nodes seen so far are relevant
                for (CallGraphEdge seenEdge : path) {
                    relevant.add(seenEdge.getSource());
                }
                // Also add the last node on the path
                relevant.add(e.getTarget());
            }

            for (CallGraphEdge out : getOutGoingEdges(e.getTarget(), query)) {
                // Add the successors with the new path
                q.addFirst(new OrderedPair<>(out, path));
            }
        }

        return relevant;
    }

    /**
     * Get the edges from cgNode, which includes any call sites within cgNode to callees, and also the return from
     * cgNode to callers of cgNode.
     *
     * @param cgNode call graph nodes to get the edges for
     * @param query query we are curently executing
     * @return the set of "call" and "return" edges leaving the given cg node
     */
    private Set<CallGraphEdge> getOutGoingEdges(OrderedPair<IMethod, Context> cgNode, ProgramPointSubQuery query) {
        Set<CallGraphEdge> out = new LinkedHashSet<>();
        for (ProgramPointReplica callSite : g.getCallSitesWithinMethod(cgNode)) {
            this.addCalleeDependency(query, callSite);
            for (OrderedPair<IMethod, Context> callee : g.getCalleesOf(callSite)) {
                out.add(CallGraphEdge.createCallEdge(cgNode, callee));
            }
        }

        this.addCallerDependency(query, cgNode);
        for (OrderedPair<CallSiteProgramPoint, Context> caller : g.getCallersOf(cgNode)) {
            OrderedPair<IMethod, Context> callerCGNode = new OrderedPair<>(caller.fst().containingProcedure(),
                                                                           caller.snd());
            out.add(CallGraphEdge.createReturnEdge(callerCGNode, cgNode));
        }
        return out;
    }

    /**
     * Edge in the call graph from one (Method, Context) pair to another. Such edges can either be call or return edges.
     */
    private static class CallGraphEdge {

        /**
         * (Method, Context) for the caller
         */
        final OrderedPair<IMethod, Context> caller;
        /**
         * (Method, Context) for the callee
         */
        final OrderedPair<IMethod, Context> callee;
        /**
         * whether this is a return edge from the callee to the caller (false if it is a "call" edge from the caller to
         * the callee)
         */
        final boolean isReturnEdge;

        /**
         * Create a call graph "call" edge from the caller to the callee
         *
         * @param caller (Method, Context) for the caller
         * @param callee (Method, Context) for the callee
         * @param isReturnEdge whether this is a return edge from the callee to the caller (false if it is a "call" edge
         *            from the caller to the callee)
         */
        private CallGraphEdge(OrderedPair<IMethod, Context> caller, OrderedPair<IMethod, Context> callee,
                              boolean isReturnEdge) {
            assert callee != null;
            assert caller != null;
            this.callee = callee;
            this.caller = caller;
            this.isReturnEdge = isReturnEdge;
        }

        /**
         * Create a call graph "call" edge from the caller to the callee
         *
         * @param caller (Method, Context) for the caller
         * @param callee (Method, Context) for the callee
         * @return The newly created edge
         */
        public static CallGraphEdge createCallEdge(OrderedPair<IMethod, Context> caller,
                                                   OrderedPair<IMethod, Context> callee) {
            return new CallGraphEdge(caller, callee, false);
        }

        /**
         * Create a call graph "return" edge from the callee to the caller
         *
         * @param caller (Method, Context) for the caller
         * @param callee (Method, Context) for the callee
         * @return The newly created edge
         */
        public static CallGraphEdge createReturnEdge(OrderedPair<IMethod, Context> caller,
                                                     OrderedPair<IMethod, Context> callee) {
            return new CallGraphEdge(caller, callee, true);
        }

        /**
         * Get the source of the edge the callee for a return edge, caller for a call edge
         *
         * @return source of this edge
         */
        public OrderedPair<IMethod, Context> getSource() {
            return isReturnEdge ? this.callee : this.caller;
        }

        /**
         * Get the target of the edge the caller for a return edge, callee for a call edge
         *
         * @return target of this edge
         */
        public OrderedPair<IMethod, Context> getTarget() {
            return isReturnEdge ? this.caller : this.callee;
        }

        /**
         * Given a "return" edge from a callee to a caller, get the corresonding "call" edge from the caller to the
         * callee.
         *
         * @param returnEdge return edge to get the "call" edge for
         * @return the "call" edge
         */
        public static CallGraphEdge getCallForReturn(CallGraphEdge returnEdge) {
            assert returnEdge.isReturnEdge;
            return createCallEdge(returnEdge.caller, returnEdge.callee);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + this.callee.hashCode();
            result = prime * result + this.caller.hashCode();
            result = prime * result + (this.isReturnEdge ? 1231 : 1237);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            CallGraphEdge other = (CallGraphEdge) obj;
            if (!this.callee.equals(other.callee)) {
                return false;
            }

            if (!this.caller.equals(other.caller)) {
                return false;
            }
            if (this.isReturnEdge != other.isReturnEdge) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            if (isReturnEdge) {
                return PrettyPrinter.methodString(callee.fst()) + " in " + callee.snd() + " -R-> "
                        + PrettyPrinter.methodString(caller.fst()) + " in " + caller.snd();
            }
            return PrettyPrinter.methodString(caller.fst()) + " in " + caller.snd() + " -C-> "
                    + PrettyPrinter.methodString(callee.fst()) + " in " + callee.snd();
        }
    }

    /* *****************************************************************************
    *
    * METHOD REACHABILITY CODE
    *
    * The following code is responsible for computing the reachability results of an
    * entire method.
    */

    /**
     * A ReachabilityResult records the reachability results of a single method in a context, that is, which for a
     * subset of the source and destination pairs of InterProgramPointReplica in the method and context, what are the
     * KilledAndAlloced sets that summarize all paths from the source to the destination.
     *
     * This object must be thread safe.
     */
    static class MethodSummaryKillAndAlloc {
        /**
         * Map from source iipr to target ippr with a pair of killed and allocated. Note that all source and target
         * ipprs in m should be from the same method.
         *
         * If (s, t, _, _) is not in the map, then it means that t is not reachable from s (or at least we haven't found
         * out that it is, or are not interested in recording that fact).
         *
         * If (s, t, killed, alloced) is in the map, then this means that t is reachable from s, but all paths from s to
         * t will kill the PointsToGraphNodes in killed, and allocate the InstanceKeyRecencys in alloced.
         */
        final ConcurrentMap<InterProgramPointReplica, ConcurrentMap<InterProgramPointReplica, KilledAndAlloced>> m = AnalysisUtil.createConcurrentHashMap();

        private MethodSummaryKillAndAlloc() {
            // Intentionally left blank
        }

        /**
         * Create a result in which every result is unreachable
         *
         * @return
         */
        public static MethodSummaryKillAndAlloc createInitial() {
            return new MethodSummaryKillAndAlloc();
        }

        public KilledAndAlloced getResult(InterProgramPointReplica source, InterProgramPointReplica target) {
            ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> s = m.get(source);
            if (s == null) {
                return KilledAndAlloced.createUnreachable();
            }
            KilledAndAlloced p = s.get(target);
            if (p == null) {
                return KilledAndAlloced.createUnreachable();
            }
            return p;
        }

        public void add(InterProgramPointReplica source, InterProgramPointReplica target, KilledAndAlloced res) {
            assert target != null;
            assert source.getContainingProcedure().equals(target.getContainingProcedure());
            assert source.getContext().equals(target.getContext());
            ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> thisTargetMap = this.getTargetMap(source);
            if (res != null) {
                KilledAndAlloced existing = thisTargetMap.putIfAbsent(target, res);
                assert existing == null;
            }
            else {
                // we are putting in null, i.e., the target is not reachable from the source.
                assert !thisTargetMap.containsKey(target);
                //thisTargetMap.remove(target);
            }
        }

        private ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> getTargetMap(InterProgramPointReplica s) {
            ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> tm = this.m.get(s);
            if (tm == null) {
                tm = AnalysisUtil.createConcurrentHashMap();
                ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> existing = this.m.putIfAbsent(s, tm);
                if (existing != null) {
                    tm = existing;
                }
            }
            return tm;
        }

        @Override
        public int hashCode() {
            return m.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (!(obj instanceof MethodSummaryKillAndAlloc)) {
                return false;
            }
            MethodSummaryKillAndAlloc other = (MethodSummaryKillAndAlloc) obj;
            if (m == null) {
                if (other.m != null) {
                    return false;
                }
            }
            else if (!m.equals(other.m)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("REACHABILITY RESULTS:");
            for (InterProgramPointReplica s : m.keySet()) {
                for (InterProgramPointReplica t : m.get(s).keySet()) {
                    sb.append("\n\t" + s + " -> " + t + "\n\t\t" + m.get(s).get(t));
                }
            }
            return sb.toString();
        }
    }

    private final ConcurrentMap<OrderedPair<IMethod, Context>, MethodSummaryKillAndAlloc> methodSummaryMemoization = AnalysisUtil.createConcurrentHashMap();

    /*
     * Get the reachability results for a method.
     */
    MethodSummaryKillAndAlloc getReachabilityForMethod(IMethod m, Context context) {
        if (ProgramPointDestinationQuery.DEBUG) {
            System.err.println("GETTING METHOD " + PrettyPrinter.methodString(m) + " in " + context);
        }
        OrderedPair<IMethod, Context> cgnode = new OrderedPair<>(m, context);
        MethodSummaryKillAndAlloc res = methodSummaryMemoization.get(cgnode);
        if (res != null) {
            if (ProgramPointDestinationQuery.DEBUG) {
                System.err.println("\tCACHED " + res);
            }
            return res;
        }
        // no results yet.
        res = MethodSummaryKillAndAlloc.createInitial();
        MethodSummaryKillAndAlloc existing = methodSummaryMemoization.putIfAbsent(cgnode, res);
        if (existing != null) {
            // someone beat us to it, and is currently working on the results.
            if (ProgramPointDestinationQuery.DEBUG) {
                System.err.println("\tBEATEN " + existing);
            }
            return existing;
        }
        if (ProgramPointDestinationQuery.DEBUG) {
            System.err.println("\tCOMPUTING");
        }
        MethodSummaryKillAndAlloc rr = computeReachabilityForMethod(m, context);
        ProgramPointDestinationQuery.DEBUG = false;
        return rr;
    }

    private void recordMethodReachability(IMethod m, Context context, MethodSummaryKillAndAlloc res) {
        OrderedPair<IMethod, Context> cgnode = new OrderedPair<>(m, context);
        MethodSummaryKillAndAlloc existing = methodSummaryMemoization.put(cgnode, res);
        if (existing != null && !existing.equals(res)) {
            // trigger update for dependencies.
            methodSummaryChanged(m, context);
        }
    }

    private MethodSummaryKillAndAlloc computeReachabilityForMethod(IMethod m, Context context) {
        // XXX at the moment we will just record from the start node.
        if (ProgramPointDestinationQuery.DEBUG) {
            System.err.println("COMPUTING FOR " + PrettyPrinter.methodString(m) + " in " + context);
        }

        // do a dataflow over the program points. XXX could try to use a dataflow framework to speed this up.

        Map<InterProgramPoint, KilledAndAlloced> results = new HashMap<>();
        WorkQueue<InterProgramPoint> q = new WorkQueue<>();
        Set<InterProgramPoint> visited = new HashSet<>();

        MethodSummaryNodes summ = g.getRegistrar().getMethodSummary(m);
        PostProgramPoint entryIPP = summ.getEntryPP().post();
        q.add(entryIPP);
        getOrCreate(results, entryIPP).setEmpty();

        while (!q.isEmpty()) {
            InterProgramPoint ipp = q.poll();
            if (!visited.add(ipp)) {
                continue;
            }
            if (ProgramPointDestinationQuery.DEBUG) {
                System.err.println("\tFROM QUEUE " + ipp);
            }
            ProgramPoint pp = ipp.getPP();
            assert pp.containingProcedure().equals(m);
            KilledAndAlloced current = getOrCreate(results, ipp);

            if (ipp instanceof PreProgramPoint) {
                if (pp instanceof CallSiteProgramPoint) {
                    // this is a method call! Register the dependency and get some cached results
                    addCalleeDependency(m, context, pp.getReplica(context));

                    CallSiteProgramPoint cspp = (CallSiteProgramPoint) pp;

                    Set<OrderedPair<IMethod, Context>> calleeSet = g.getCalleesOf(pp.getReplica(context));
                    if (calleeSet.isEmpty()) {
                        // no callees, so nothing to do
                        if (ProgramPointDestinationQuery.DEBUG) {
                            System.err.println("\t\tno callees " + ipp);
                        }
                        continue;
                    }

                    KilledAndAlloced postNormal = getOrCreate(results, pp.post());
                    KilledAndAlloced postEx = getOrCreate(results, cspp.getExceptionExit().post());

                    for (OrderedPair<IMethod, Context> callee : calleeSet) {
                        addMethodDependency(m, context, callee);
                        MethodSummaryKillAndAlloc calleeResults = getReachabilityForMethod(callee.fst(), callee.snd());
                        MethodSummaryNodes calleeSummary = g.getRegistrar().getMethodSummary(callee.fst());
                        InterProgramPointReplica calleeEntryIPPR = ProgramPointReplica.create(callee.snd(),
                                                                                              calleeSummary.getEntryPP())
                                                                                      .post();
                        KilledAndAlloced normalRet = calleeResults.getResult(calleeEntryIPPR,
                                                                             ProgramPointReplica.create(callee.snd(),
                                                                                                        calleeSummary.getNormalExitPP())
                                                                                                .pre());
                        KilledAndAlloced exRet = calleeResults.getResult(calleeEntryIPPR,
                                                                         ProgramPointReplica.create(callee.snd(),
                                                                                                    calleeSummary.getExceptionExitPP())
                                                                                            .pre());

                        // The final results will be the sets that are killed or alloced for all the callees
                        //     so intersect the results
                        postNormal.meet(KilledAndAlloced.join(current, normalRet));
                        postEx.meet(KilledAndAlloced.join(current, exRet));

                    }
                    // Add the successor program points to the queue
                    q.add(pp.post());
                    q.add(cspp.getExceptionExit().post());
                }
                else if (pp.isNormalExitSummaryNode() || pp.isExceptionExitSummaryNode()) {
                    // not much to do here. The results will be copied once the work queue finishes.
                    if (ProgramPointDestinationQuery.DEBUG) {
                        System.err.println("\t\tEXIT " + pp);
                    }
                    continue;
                }
                else {
                    PointsToStatement stmt = g.getRegistrar().getStmtAtPP(pp);
                    // not a call or a return, it's just a normal statement.
                    // does ipp kill this.node?
                    if (stmt != null) {
                        OrderedPair<Boolean, PointsToGraphNode> killed = stmt.killsNode(context, g);
                        if (killed != null) {
                            if (!killed.fst()) {
                                if (ProgramPointDestinationQuery.DEBUG) {
                                    System.err.println("\t\tCould Kill "
                                            + stmt.getReadDependencyForKillField(context, g.getHaf()));
                                }
                                // not enough info available yet.
                                // add a depedency since more information may change this search
                                // conservatively assume that it kills any kind of the field we give it.
                                current.addMaybeKilledField(stmt.getMaybeKilledField());
                                addKillDependency(m, context, stmt.getReadDependencyForKillField(context, g.getHaf()));

                            }
                            else if (killed.snd() != null && killed.snd() != null) {
                                if (ProgramPointDestinationQuery.DEBUG) {
                                    System.err.println("\t\tDoes Kill "
                                            + stmt.getReadDependencyForKillField(context, g.getHaf()) + " "
                                            + g.lookupDictionary(killed.snd()));
                                }
                                // this statement really does kill something.
                                current.addKill(g.lookupDictionary(killed.snd()));
                                // record it, including the dependency.
                                addKillDependency(m, context, stmt.getReadDependencyForKillField(context, g.getHaf()));
                            }
                            else if (killed.snd() == null) {
                                // we have enough information to know that this statement does not kill a node we care about
                                removeKillDependency(m,
                                                     context,
                                                     stmt.getReadDependencyForKillField(context, g.getHaf()));
                            }
                        }

                        // is anything allocated at this program point?
                        InstanceKeyRecency justAllocated = stmt.justAllocated(context, g);
                        if (justAllocated != null) {
                            assert justAllocated.isRecent();
                            int/*InstanceKeyRecency*/justAllocatedKey = g.lookupDictionary(justAllocated);
                            if (g.isMostRecentObject(justAllocatedKey)
                                    && g.isTrackingMostRecentObject(justAllocatedKey)) {
                                if (ProgramPointDestinationQuery.DEBUG) {
                                    System.err.println("\t\tDoes Alloc " + justAllocatedKey);
                                }
                                current.addAlloced(justAllocatedKey);
                            }
                        }
                    }
                    // Add the post program point to continue the traversal
                    KilledAndAlloced postResults = getOrCreate(results, pp.post());
                    postResults.meet(current);
                    q.add(pp.post());
                }
            }
            else if (ipp instanceof PostProgramPoint) {
                Set<ProgramPoint> ppSuccs = pp.succs();
                // Add all the successor program points
                for (ProgramPoint succ : ppSuccs) {
                    KilledAndAlloced succResults = getOrCreate(results, succ.pre());
                    succResults.meet(current);
                    q.add(succ.pre());
                }
            }
            else {
                throw new IllegalArgumentException("Don't know about this kind of interprogrampoint");
            }

        }

        MethodSummaryKillAndAlloc rr = MethodSummaryKillAndAlloc.createInitial();
        PreProgramPoint normExitIPP = summ.getNormalExitPP().pre();
        PreProgramPoint exExitIPP = summ.getExceptionExitPP().pre();

        rr.add(entryIPP.getReplica(context), normExitIPP.getReplica(context), getOrCreate(results, normExitIPP));
        rr.add(entryIPP.getReplica(context), exExitIPP.getReplica(context), getOrCreate(results, exExitIPP));

        recordMethodReachability(m, context, rr);
        if (ProgramPointDestinationQuery.DEBUG) {
            System.err.println("COMPUTED " + rr + " FOR " + PrettyPrinter.methodString(m) + " in " + context);
        }
        return rr;
    }

    /* *****************************************************************************
     *
     * REACHABILITY QUERY RESULT MEMOIZATION
     *
     * The following code is responsible for memoizing query results.
     */

    /**
     * Cache of the positive answers. i.e., if (source, destination, noKill, noAlloc) in positiveCache, then there is a
     * path from source to destination that does not kill any object in noKill, nor does it allocate any object in
     * noAlloc.
     */
    private final Set<ProgramPointSubQuery> positiveCache = AnalysisUtil.createConcurrentSet();
    private final Set<ProgramPointSubQuery> negativeCache = AnalysisUtil.createConcurrentSet();

    /* *****************************************************************************
     *
     * DEPENDENCY TRACKING
     *
     * The following code is responsible for recording dependencies
     */
    private final ConcurrentMap<ProgramPointSubQuery, Set<ReachabilityQueryOrigin>> queryDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<ProgramPointReplica, Set<ProgramPointSubQuery>> calleeQueryDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<ProgramPointReplica, Set<OrderedPair<IMethod, Context>>> calleeMethodDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<ProgramPointSubQuery>> callerQueryDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<ProgramPointSubQuery>> methodQueryDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>> methodMethodDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentIntMap<Set<ProgramPointSubQuery>> killQueryDependencies = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();
    private final ConcurrentIntMap<Set<OrderedPair<IMethod, Context>>> killMethodDependencies = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * Record the fact that the result of query depends on the callees of callSite, and thus, if the callees change,
     * then query may need to be reevaluated.
     *
     * @param query
     * @param callSite
     */
    void addCalleeDependency(ProgramPointSubQuery query, ProgramPointReplica callSite) {
        assert callSite.getPP() instanceof CallSiteProgramPoint;

        Set<ProgramPointSubQuery> s = calleeQueryDependencies.get(callSite);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<ProgramPointSubQuery> existing = calleeQueryDependencies.putIfAbsent(callSite, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
    }

    /**
     * query needs to be re-run if there is a new caller of callGraphNode.
     *
     * @param query
     * @param callGraphNode
     */
    void addCallerDependency(ProgramPointSubQuery query, OrderedPair<IMethod, Context> callGraphNode) {
        Set<ProgramPointSubQuery> s = callerQueryDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<ProgramPointSubQuery> existing = callerQueryDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
    }

    /**
     * We need to reanalyze the method results for (m, context) if the reachability results for callGraphNode changes.
     *
     * @param m
     * @param context
     * @param callGraphNode
     */
    void addMethodDependency(ProgramPointSubQuery query, OrderedPair<IMethod, Context> callGraphNode) {
        Set<ProgramPointSubQuery> s = methodQueryDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<ProgramPointSubQuery> existing = methodQueryDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
    }

    void addKillDependency(ProgramPointSubQuery query, ReferenceVariableReplica readDependencyForKillField) {
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);
        Set<ProgramPointSubQuery> s = killQueryDependencies.get(n);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<ProgramPointSubQuery> existing = killQueryDependencies.putIfAbsent(n, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
    }

    void removeKillDependency(ProgramPointSubQuery query, ReferenceVariableReplica readDependencyForKillField) {
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);

        Set<ProgramPointSubQuery> s = killQueryDependencies.get(n);
        if (s != null) {
            s.remove(query);
        }
    }

    private void addCalleeDependency(IMethod m, Context context, ProgramPointReplica callSite) {
        assert callSite.getPP() instanceof CallSiteProgramPoint;

        Set<OrderedPair<IMethod, Context>> s = calleeMethodDependencies.get(callSite);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<OrderedPair<IMethod, Context>> existing = calleeMethodDependencies.putIfAbsent(callSite, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(new OrderedPair<>(m, context));
    }

    /**
     * We need to reanalyze the method results for (m, context) if the reachability results for callGraphNode changes.
     *
     * @param m
     * @param context
     * @param callGraphNode
     */
    private void addMethodDependency(IMethod m, Context context, OrderedPair<IMethod, Context> callGraphNode) {
        Set<OrderedPair<IMethod, Context>> s = methodMethodDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<OrderedPair<IMethod, Context>> existing = methodMethodDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(new OrderedPair<>(m, context));
    }

    private void addKillDependency(IMethod m, Context context, ReferenceVariableReplica readDependencyForKillField) {
        if (readDependencyForKillField == null) {
            return;
        }
        OrderedPair<IMethod, Context> callGraphNode = new OrderedPair<>(m, context);
        int n = g.lookupDictionary(readDependencyForKillField);

        Set<OrderedPair<IMethod, Context>> s = killMethodDependencies.get(n);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<OrderedPair<IMethod, Context>> existing = killMethodDependencies.putIfAbsent(n, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(callGraphNode);
    }

    private void removeKillDependency(IMethod m, Context context, ReferenceVariableReplica readDependencyForKillField) {
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);

        OrderedPair<IMethod, Context> callGraphNode = new OrderedPair<>(m, context);
        Set<OrderedPair<IMethod, Context>> s = killMethodDependencies.get(n);
        if (s != null) {
            s.remove(callGraphNode);
        }
    }

    /**
     * This method is invoked to let us know that a new edge in the call graph has been added from the callSite.
     *
     * We use this method to make sure that we re-run any method reachability results and (negative) queries depended on
     * the call site.
     *
     * @param callSite
     */
    private void calleeAddedTo(ProgramPointReplica callSite) {
        assert callSite.getPP() instanceof CallSiteProgramPoint;
        Set<OrderedPair<IMethod, Context>> meths = calleeMethodDependencies.get(callSite);
        if (meths != null) {
            for (OrderedPair<IMethod, Context> p : meths) {
                // need to re-run the analysis of p
                computeReachabilityForMethod(p.fst(), p.snd());
            }
        }
        Set<ProgramPointSubQuery> queries = calleeQueryDependencies.get(callSite);
        if (queries != null) {

            Iterator<ProgramPointSubQuery> iter = queries.iterator();
            while (iter.hasNext()) {
                ProgramPointSubQuery mr = iter.next();
                // need to re-run the query of mr
                if (!requestRerunQuery(mr)) {
                    // whoops, no need to rerun this anymore.
                    iter.remove();
                }
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
    private void callerAddedTo(OrderedPair<IMethod, Context> callGraphNode) {
        Set<ProgramPointSubQuery> queries = callerQueryDependencies.get(callGraphNode);
        if (queries != null) {
            Iterator<ProgramPointSubQuery> iter = queries.iterator();
            while (iter.hasNext()) {
                ProgramPointSubQuery mr = iter.next();
                // need to re-run the query of mr
                if (!requestRerunQuery(mr)) {
                    // whoops, no need to rerun this anymore.
                    iter.remove();
                }
            }
        }

    }

    private void methodSummaryChanged(IMethod m, Context context) {
        OrderedPair<IMethod, Context> cgnode = new OrderedPair<>(m, context);
        Set<OrderedPair<IMethod, Context>> meths = methodMethodDependencies.get(cgnode);
        if (meths != null) {
            for (OrderedPair<IMethod, Context> p : meths) {
                // need to re-run the analysis of p
                computeReachabilityForMethod(p.fst(), p.snd());
            }
        }
        Set<ProgramPointSubQuery> queries = methodQueryDependencies.get(cgnode);
        if (queries != null) {
            Iterator<ProgramPointSubQuery> iter = queries.iterator();
            while (iter.hasNext()) {
                ProgramPointSubQuery mr = iter.next();
                // need to re-run the query of mr
                if (!requestRerunQuery(mr)) {
                    // whoops, no need to rerun this anymore.
                    iter.remove();
                }
            }
        }
    }

    /**
     * Rerun the query mr if necessary. Will return true if the query was rerun, false if it did not need to be rerurn.
     *
     * @param mr
     */
    private boolean requestRerunQuery(ProgramPointSubQuery mr) {
        if (this.positiveCache.contains(mr)) {
            // the query is already guaranteed to be true.
            return false;
        }
        this.analysisHandle.submitReachabilityQuery(mr);
        return true;
    }

    /**
     * The result of the query changed, from negative to positive. Make sure to reprocess any StmtAndContexts that
     * depended on it, either by adding it to toReprocess, or giving it to the engine immediately.
     *
     */
    private void queryResultChanged(ProgramPointSubQuery mr) {
        assert this.positiveCache.contains(mr);

        // since the query is positive, it will never change in the future.
        // Let's save some memory by removing the set of dependent SaCs.
        Set<ReachabilityQueryOrigin> deps = this.queryDependencies.remove(mr);
        if (deps == null) {
            // nothing to do.
            return;
        }
        // immediately execute the tasks that depended on this.
        for (ReachabilityQueryOrigin task : deps) {
            task.trigger(this.analysisHandle);
        }
    }

    /**
     * This is invoked by the PointsToGraph to let us know that a new edge has been added to the call graph. This allows
     * us to retrigger computation as needed.
     *
     */
    public void addCallGraphEdge(CallSiteProgramPoint callSite, Context callerContext, IMethod callee,
                                 Context calleeContext) {

        // XXX turn these into separate tasks that can be run concurrently
        calleeAddedTo(callSite.getReplica(callerContext));
        callerAddedTo(new OrderedPair<>(callee, calleeContext));

    }

    /**
     * Add a dependency that originatingSaC depends on the result of query.
     *
     * @param query
     * @param originatingSaC
     */
    private void addDependency(ProgramPointSubQuery query, ReachabilityQueryOrigin origin) {
        if (origin == null) {
            // nothing to do
            return;
        }
        Set<ReachabilityQueryOrigin> deps = this.queryDependencies.get(query);
        if (deps == null) {
            deps = AnalysisUtil.createConcurrentSet();
            Set<ReachabilityQueryOrigin> existing = queryDependencies.putIfAbsent(query, deps);
            if (existing != null) {
                deps = existing;
            }
        }
        deps.add(origin);

    }

    public void checkPointsToGraphDelta(GraphDelta delta) {
        IntIterator domainIter = delta.domainIterator();
        while (domainIter.hasNext()) {
            int n = domainIter.next();
            Set<OrderedPair<IMethod, Context>> meths = killMethodDependencies.get(n);
            if (meths != null) {
                for (OrderedPair<IMethod, Context> p : meths) {
                    // need to re-run the analysis of p
                    computeReachabilityForMethod(p.fst(), p.snd());
                }
            }
            Set<ProgramPointSubQuery> queries = killQueryDependencies.get(n);
            if (queries != null) {
                Iterator<ProgramPointSubQuery> iter = queries.iterator();
                while (iter.hasNext()) {
                    ProgramPointSubQuery mr = iter.next();
                    // need to re-run the query of mr
                    if (!requestRerunQuery(mr)) {
                        // whoops, no need to rerun this anymore.
                        iter.remove();
                    }
                }
            }
        }
    }

    public void processSubQuery(ProgramPointSubQuery sq) {
        this.computeQuery(Collections.singleton(sq.source), sq.destination, sq.noKill, sq.noAlloc, sq.forbidden);
    }
}
