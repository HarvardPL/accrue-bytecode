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
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;

/**
 * This class answers questions about what programs are reachable from what other program points, and caches answers
 * smartly.
 */
public class ProgramPointReachabilityNew {
    /**
     * Keep a reference to the PointsToGraph for convenience.
     */
    private final PointsToGraph g;

    /**
     * A reference to allow us to submit a StmtAndContext for reprocessing
     */
    private final PointsToAnalysisHandle analysisHandle;

    /**
     * Create a new reachability query engine
     *
     * @param g points to graph
     * @param analysisHandle interface for submitting jobs to the pointer analysis
     */
    ProgramPointReachabilityNew(PointsToGraph g, PointsToAnalysisHandle analysisHandle) {
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
            res = KilledAndAlloced.UNREACHABLE;
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
            SubQuery mr = new SubQuery(src, destination, noKill, noAlloc, forbidden);
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
        // try to solve it for each source.
        Set<InterProgramPointReplica> visited = new HashSet<>();
        for (InterProgramPointReplica src : sources) {
            SubQuery query = new SubQuery(src, destination, noKill, noAlloc, forbidden);
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
            // Now try a depth first search starting at the source
            ReachabilityResult res = performSearchForRelevantNode(src, query, relevantNodes, visited, false);
            if (res == ReachabilityResult.FOUND) {
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
    private boolean recordQueryResult(SubQuery query, boolean b) {
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
    private Set<OrderedPair<IMethod, Context>> findRelevantNodes(SubQuery query) {
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
    private Set<CallGraphEdge> getOutGoingEdges(OrderedPair<IMethod, Context> cgNode, SubQuery query) {
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

    /**
     * Try to find a path from src to destination that is not killed by an allocation of one of the instance keys in
     * noAlloc or an assignment into one of the points-to graph nodes in noKill and does not pass through one of the
     * nodes in the forbidden set.
     *
     * @param src source program point
     * @param relevantNodes set of call graph nodes that cannot be summarized and must be searched directly
     * @param visited set of program points we have already visited while computing the main query result
     * @param query The query that caused this search, used to track dependencies
     * @param isFromKnownCallSite whether this search was triggered by a call-site program point
     *
     * @return true if a path could be found, false if no path could be found
     */
    private ReachabilityResult performSearchForRelevantNode(InterProgramPointReplica src, SubQuery query,
                                                            Set<OrderedPair<IMethod, Context>> relevantNodes,
                                                            Set<InterProgramPointReplica> visited,
                                                            boolean isFromKnownCallSite) {
        if (!visited.add(src)) {
            // we've already tried it...
            return ReachabilityResult.UNREACHABLE;
        }
        IMethod currentMethod = src.getContainingProcedure();
        Context currentContext = src.getContext();
        OrderedPair<IMethod, Context> currentCallGraphNode = new OrderedPair<>(currentMethod, currentContext);

        // Is the destination node in the same node as the source
        boolean inSameMethod = query.destination.getContainingProcedure().equals(currentMethod);

        // try searching forward from src, carefully handling calls.
        Deque<InterProgramPointReplica> q = new ArrayDeque<>();

        // Program points to delay until after we search other paths
        Deque<InterProgramPointReplica> delayed = new ArrayDeque<>();

        // Record the exits that are reachable within the method containing the source
        ReachabilityResult reachableExits = ReachabilityResult.UNREACHABLE;

        q.add(src);
        while (!q.isEmpty()) {
            InterProgramPointReplica ippr = q.poll();
            assert (ippr.getContainingProcedure().equals(currentMethod)) : "All nodes for a single search should be ";
            if (ippr.equals(query.destination)) {
                // Found it!
                return ReachabilityResult.FOUND;
            }

            if (query.forbidden.contains(ippr)) {
                // prune this!
                continue;
            }

            InterProgramPoint ipp = ippr.getInterPP();
            ProgramPoint pp = ipp.getPP();

            if (ipp instanceof PreProgramPoint) {
                if (pp instanceof CallSiteProgramPoint) {

                    CallSiteProgramPoint cspp = (CallSiteProgramPoint) pp;

                    // Special handling for static class initializers
                    if (cspp.isClinit()) {
                        // Whether the class initializer has been processed
                        boolean hasBeenAdded;
                        if (g.getClassInitializers() != null) {
                            // Still constructing the call graph
                            hasBeenAdded = g.getClassInitializers().contains(cspp.getClinit());
                        }
                        else {
                            try {
                                Context c = g.getHaf().initialContext();
                                CGNode clinitNode = g.getCallGraph().findOrCreateNode(cspp.getClinit(), c);
                                hasBeenAdded = g.getCallGraph().getEntrypointNodes().contains(clinitNode);
                            }
                            catch (CancelException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        if (!hasBeenAdded) {
                            // This is a class initializer that has not been added to the call graph yet.
                            // Add the post program point anyway. This is potentially imprecise, but sound.

                            // We do this since some of the clinit program points are for classes that we
                            // (imprecisely) think could be initialized (based on type information) before starting
                            // the points-to analysis, but once we have more precise information it turns out
                            // they never can be initialized.

                            InterProgramPointReplica post = pp.post().getReplica(currentContext);
                            if (visited.add(post)) {
                                q.add(post);
                            }

                            // No need to process, there will be no callees
                            continue;
                        }
                    }

                    // This is a program point for a method call
                    if (inSameMethod) {
                        delayed.add(ippr);
                    }

                    ReachabilityResult res = handleCall(query, ippr, relevantNodes, visited);
                    if (res == ReachabilityResult.FOUND) {
                        return ReachabilityResult.FOUND;
                    }
                    // We just analyzed a call, add the post for the caller if we found any exits
                    if (res.containsNormalExit()) {
                        InterProgramPointReplica post = cspp.post().getReplica(currentContext);
                        if (visited.add(post)) {
                            q.add(post);
                        }
                    }
                    if (res.containsExceptionExit()) {
                        InterProgramPointReplica post = cspp.getExceptionExit().post().getReplica(currentContext);
                        if (visited.add(post)) {
                            q.add(post);
                        }
                    }
                    continue;
                }
                else if (pp.isNormalExitSummaryNode() || pp.isExceptionExitSummaryNode()) {

                    // Record the exit
                    if (pp.isNormalExitSummaryNode()) {
                        reachableExits.join(ReachabilityResult.NORMAL_EXIT);
                    }

                    if (pp.isExceptionExitSummaryNode()) {
                        reachableExits.join(ReachabilityResult.EXCEPTION_EXIT);
                    }

                    if (isFromKnownCallSite) {
                        // This is from a known call-site, the return to the caller is handled at the call-site in "handleCall"
                        continue;
                    }

                    if (inSameMethod) {
                        delayed.add(ippr);
                        continue;
                    }

                    // We do not know the call-site we are returning to perform searches from all possible caller-sites
                    boolean found = handleMethodExitToUnknownCallSite(query,
                                                                      currentCallGraphNode,
                                                                      relevantNodes,
                                                                      visited,
                                                                      pp.isExceptionExitSummaryNode());
                    if (found) {
                        return ReachabilityResult.FOUND;
                    }

                    continue;
                }
                PointsToStatement stmt = g.getRegistrar().getStmtAtPP(pp);
                // not a call or a return, it's just a normal statement.
                // does ipp kill this.node?
                if (stmt != null && handlePossibleKill(stmt, currentContext, query)) {
                    continue;
                }
                // Path was not killed add the post PP for the pre PP
                InterProgramPointReplica post = pp.post().getReplica(currentContext);
                if (visited.add(post)) {
                    q.add(post);
                }
            } // end of "pre" program point handling
            else if (ipp instanceof PostProgramPoint) {
                Set<ProgramPoint> ppSuccs = pp.succs();
                for (ProgramPoint succ : ppSuccs) {
                    InterProgramPointReplica succIPPR = succ.pre().getReplica(currentContext);
                    if (visited.add(succIPPR)) {
                        q.add(succIPPR);
                    }
                }
            }
            else {
                throw new IllegalArgumentException("Don't know about this kind of interprogrampoint");
            }
        } // end of normal queue
        while (!delayed.isEmpty()) {
            InterProgramPointReplica source = delayed.poll();
            ProgramPoint pp = source.getInterPP().getPP();
            if (pp instanceof CallSiteProgramPoint) {
                CallSiteProgramPoint cspp = (CallSiteProgramPoint) pp;
                ReachabilityResult res = handleCall(query, source, relevantNodes, visited);
                if (res == ReachabilityResult.FOUND) {
                    return ReachabilityResult.FOUND;
                }

                // We just analyzed a call, add the post for the caller if we found any exits
                if (res.containsNormalExit()) {
                    InterProgramPointReplica post = cspp.post().getReplica(currentContext);
                    // Search from the normal successor of the call-site pre program point
                    res = performSearchForRelevantNode(post, query, relevantNodes, visited, isFromKnownCallSite);
                    reachableExits.join(res);
                }
                if (res.containsExceptionExit()) {
                    // Search from the exceptional successor of the call-site pre program point
                    InterProgramPointReplica post = cspp.getExceptionExit().post().getReplica(currentContext);
                    res = performSearchForRelevantNode(post, query, relevantNodes, visited,

                    isFromKnownCallSite);
                    reachableExits.join(res);
                }
                continue;
            }
            else if (pp.isExceptionExitSummaryNode() || pp.isNormalExitSummaryNode()) {
                boolean found = handleMethodExitToUnknownCallSite(query,
                                                                  currentCallGraphNode,
                                                                  relevantNodes,
                                                                  visited,
                                                                  pp.isExceptionExitSummaryNode());
                if (found) {
                    return ReachabilityResult.FOUND;
                }
                continue;
            }

            ReachabilityResult res = performSearchForRelevantNode(source,
                                                                  query,
                                                                  relevantNodes,
                                                                  visited,
                                                                  isFromKnownCallSite);
            if (res == ReachabilityResult.FOUND) {
                return ReachabilityResult.FOUND;
            }
        }
        // we didn't find it
        return ReachabilityResult.UNREACHABLE;
    }

    /**
     * Handle a "pre" program point that may kill the current path.
     *
     * @param stmt points-to graph statement for the current program point
     * @param currentContext current context
     * @param query query being executed
     * @return whether the path was killed
     */
    private boolean handlePossibleKill(PointsToStatement stmt, Context currentContext, SubQuery query) {
        OrderedPair<Boolean, PointsToGraphNode> killed = stmt.killsNode(currentContext, g);
        if (killed != null) {
            if (!killed.fst()) {
                // not enough info available yet.
                //add a depedency since more information may change this search
                addKillDependency(query, stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
                // for the moment, assume conservatively that this statement
                // may kill a field we are interested in.
                return true;
            }
            else if (killed.snd() != null && query.noKill.contains(g.lookupDictionary(killed.snd()))) {
                // dang! we killed something we shouldn't. Prune the search.
                // add a depedency in case this changes in the future.
                addKillDependency(query, stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
                return true;
            }
            else if (killed.snd() == null) {
                // we have enough information to know that this statement does not kill a node we care about
                removeKillDependency(query, stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
            }
            // we have enough information to determine whether this statement kills a field, and it does not
            // kill anything we care about. So we can continue with the search.
            assert killed.fst() && (killed.snd() == null || !query.noKill.contains(g.lookupDictionary(killed.snd())));
        }

        // is "to" allocated at this program point?
        InstanceKeyRecency justAllocated = stmt.justAllocated(currentContext, g);
        if (justAllocated != null) {
            assert justAllocated.isRecent();
            int justAllocatedKey = g.lookupDictionary(justAllocated);
            if (g.isMostRecentObject(justAllocatedKey) && g.isTrackingMostRecentObject(justAllocatedKey)
                    && query.noAlloc.contains(g.lookupDictionary(justAllocated))) {
                // dang! we killed allocated we shouldn't. Prune the search.
                return true;
            }
        }
        return false;
    }

    /**
     * Handle a the exit from a method when the precise call-site program point of the caller is unknown, check whether
     * the destination is reachable from the exit (by checking all possible callers)
     *
     * @param query current query
     * @param currentCallGraphNode call graph node we are exiting
     * @param relevantNodes relevant call graph nodes
     * @param destination destination
     * @param noKill Set of points to graph nodes that should not be killed on a valid path
     * @param noAlloc Set of points to graph nodes that should not be allocated on a valid path
     * @param forbidden program points that act as "kill" nodes cutting off any path
     * @param tasksToReprocess Queries that need to be reprocessed because something may have caused the results to
     *            change
     * @param isExceptionExit whether the method is exiting via exception
     * @return true if the destination program point was found
     */
    private boolean handleMethodExitToUnknownCallSite(SubQuery query,
                                                      OrderedPair<IMethod, Context> currentCallGraphNode,
                                                      Set<OrderedPair<IMethod, Context>> relevantNodes,
                                                      Set<InterProgramPointReplica> visited, boolean isExceptionExit) {
        // We are exiting the current method!
        // register dependency from this result to the callee. i.e., we should be notified if a new caller is added
        addCallerDependency(query, currentCallGraphNode);

        // We don't have a call-site to return to, explore all callers
        Set<OrderedPair<CallSiteProgramPoint, Context>> callers = g.getCallersOf(currentCallGraphNode);
        if (callers == null) {
            // no callers
            return false;
        }

        for (OrderedPair<CallSiteProgramPoint, Context> callerSite : callers) {
            CallSiteProgramPoint cspp = callerSite.fst();
            OrderedPair<IMethod, Context> caller = new OrderedPair<>(callerSite.fst().getContainingProcedure(),
                                                                     callerSite.snd());
            if (relevantNodes.contains(caller)) {
                // this is a relevant node, and we need to dig into it.
                InterProgramPointReplica callerSiteReplica;
                if (isExceptionExit) {
                    callerSiteReplica = cspp.getExceptionExit().post().getReplica(callerSite.snd());
                }
                else {
                    callerSiteReplica = cspp.post().getReplica(callerSite.snd());
                }

                // let's explore the caller now.
                ReachabilityResult res = performSearchForRelevantNode(callerSiteReplica, query, relevantNodes, visited,

                false);
                if (res == ReachabilityResult.FOUND) {
                    // we found it!
                    return true;
                }
                // We may have found method exits, but those will also have an unknown call-site and will be
                // handled by this method
            }
            else {
                // not a relevant node, so no need to pursue it.
            }
        }
        // Not found
        return false;
    }

    /**
     * Handle a method call, check whether the destination is reachable from the given call-site
     *
     * @param query current query
     * @param ippr call site program point replica
     * @param relevantNodes relevant call graph nodes
     * @param inSameMethod whether the source and destination are in the same method
     * @param delayed queue for nodes for which processing has been delayed (paired with their call-sites)
     * @param forbidden program points that act as "kill" nodes cutting off any path
     * @param tasksToReprocess Queries that need to be reprocessed because something may have caused the results to
     *            change
     * @param q program point work queue
     * @param currentContext context for the source program poinr
     * @return true if the destination program point was found
     */
    private ReachabilityResult handleCall(SubQuery query, InterProgramPointReplica ippr,
                                          Set<OrderedPair<IMethod, Context>> relevantNodes,
                                          Set<InterProgramPointReplica> visited) {
        assert calleeQueryDependencies.get(ippr.getRegularProgramPointReplica()).contains(query) : "Missing dependency. Query: "
                + query + " callSite: " + ippr;
        CallSiteProgramPoint pp = (CallSiteProgramPoint) ippr.getInterPP().getPP();

        // This is a method call! Register the dependency.
        addCalleeDependency(query, pp.getReplica(ippr.getContext()));

        Set<OrderedPair<IMethod, Context>> calleeSet = g.getCalleesOf(pp.getReplica(ippr.getContext()));
        // Exit nodes that are reachable from this call-site
        ReachabilityResult reachableExits = ReachabilityResult.UNREACHABLE;
        for (OrderedPair<IMethod, Context> callee : calleeSet) {
            MethodSummaryNodes calleeSummary = g.getRegistrar().getMethodSummary(callee.fst());
            InterProgramPointReplica calleeEntryIPPR = calleeSummary.getEntryPP().post().getReplica(callee.snd());
            if (relevantNodes.contains(callee)) {
                // this is a relevant node, and we need to dig into it.
                // check if we already have results for this callee
                ReachabilityResult res = getCachedCalleeResults(calleeEntryIPPR);
                if (res == null) {
                    // search the callee
                    res = performSearchForRelevantNode(calleeEntryIPPR, query, relevantNodes, visited, true);
                    // Record the results for the callee
                    recordCalleeResult(calleeEntryIPPR, res);
                }
                if (res == ReachabilityResult.FOUND) {
                    return ReachabilityResult.FOUND;
                }
                reachableExits.join(res);
            }
            // If both exit types are not already accounted for then check this irrelevant node
            else if (reachableExits != ReachabilityResult.NORMAL_AND_EXCEPTION_EXIT) {

                // the node was not relevant, use the summary results.
                addMethodDependency(query, callee);
                MethodSummaryKillAndAlloc calleeResults = getReachabilityForMethod(callee.fst(), callee.snd());
                KilledAndAlloced normalRet = calleeResults.getResult(calleeEntryIPPR,
                                                                     calleeSummary.getNormalExitPP()
                                                                                  .pre()
                                                                                  .getReplica(callee.snd()));
                KilledAndAlloced exRet = calleeResults.getResult(calleeEntryIPPR,
                                                                 calleeSummary.getExceptionExitPP()
                                                                              .pre()
                                                                              .getReplica(callee.snd()));

                if (normalRet.allows(query.noKill, query.noAlloc, g)) {
                    // we don't kill things we aren't meant to, not allocated things we aren't meant to!
                    reachableExits.join(ReachabilityResult.NORMAL_EXIT);
                }
                if (exRet.allows(query.noKill, query.noAlloc, g)) {
                    // we don't kill things we aren't meant to, not allocated things we aren't meant to!
                    reachableExits.join(ReachabilityResult.EXCEPTION_EXIT);
                }
                // otherwise, this means the callee kills a points-to graph node we are interseted in,
                // or it allocates an instancekey we are interested in.
                // Prune the search...
            }

        }
        return reachableExits;
    }


    /**
     * Exits that can be reached from the source node of a reachablity query
     */
    private static enum ReachabilityResult {
        /**
         * No exits are reachable
         */
        UNREACHABLE,
        /**
         * The destination was found
         */
        FOUND,
        /**
         * The normal exit program point is reachable
         */
        NORMAL_EXIT,
        /**
         * The exception exit program point is reachable
         */
        EXCEPTION_EXIT,
        /**
         * Both the exception exit and normal exit program points are reachable
         */
        NORMAL_AND_EXCEPTION_EXIT;

        /**
         * This is a lattice, join this with the given result
         *
         * <pre>
         *           FOUND
         *             |
         *   NORMAL_AND_EXCEPTION_EXIT
         *         /         \
         * NORMAL_EXIT    EXCEPTION_EXIT
         *         \         /
         *         UNREACHABLE
         *
         * </pre>
         *
         * @param other result to join with this one
         * @return The the result of joining the this with other
         */
        ReachabilityResult join(ReachabilityResult other) {
            assert other != null;
            if (this == other) {
                return this;
            }
            if (this == FOUND || other == FOUND) {
                return FOUND;
            }
            if (this == NORMAL_AND_EXCEPTION_EXIT || other == NORMAL_AND_EXCEPTION_EXIT) {
                return NORMAL_AND_EXCEPTION_EXIT;
            }
            if (this == UNREACHABLE) {
                return other;
            }
            if (other == UNREACHABLE) {
                return this;
            }

            if ((this == NORMAL_EXIT && other == EXCEPTION_EXIT) || (other == NORMAL_EXIT && this == EXCEPTION_EXIT)) {
                return NORMAL_AND_EXCEPTION_EXIT;
            }
            throw new RuntimeException("Forgot a combination " + this + ".join(" + other + ")");
        }

        /**
         * Can a normal exit be reached?
         *
         * @return whether a normal exit (from one of the callees) is reachable from the call-site
         */
        boolean containsNormalExit() {
            if (this == NORMAL_EXIT || this == NORMAL_AND_EXCEPTION_EXIT) {
                return true;
            }
            return false;
        }

        /**
         * Can an exceptional exit be reached?
         *
         * @return whether an exceptional exit (from one of the callees) is reachable from the call-site
         */
        boolean containsExceptionExit() {
            if (this == EXCEPTION_EXIT || this == NORMAL_AND_EXCEPTION_EXIT) {
                return true;
            }
            return false;
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
    private static class MethodSummaryKillAndAlloc {
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
                return KilledAndAlloced.UNREACHABLE;
            }
            KilledAndAlloced p = s.get(target);
            if (p == null) {
                return KilledAndAlloced.UNREACHABLE;
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
    private MethodSummaryKillAndAlloc getReachabilityForMethod(IMethod m, Context context) {
        OrderedPair<IMethod, Context> cgnode = new OrderedPair<>(m, context);
        MethodSummaryKillAndAlloc res = methodSummaryMemoization.get(cgnode);
        if (res != null) {
            return res;
        }
        // no results yet.
        res = MethodSummaryKillAndAlloc.createInitial();
        MethodSummaryKillAndAlloc existing = methodSummaryMemoization.putIfAbsent(cgnode, res);
        if (existing != null) {
            // someone beat us to it, and is currently working on the results.
            return existing;
        }
        return computeReachabilityForMethod(m, context);
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
                                // not enough info available yet.
                                // add a depedency since more information may change this search
                                // conservatively assume that it kills any kind of the field we give it.
                                current.addMaybeKilledField(stmt.getMaybeKilledField());
                                addKillDependency(m, context, stmt.getReadDependencyForKillField(context, g.getHaf()));

                            }
                            else if (killed.snd() != null && killed.snd() != null) {
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
    private final Set<SubQuery> positiveCache = AnalysisUtil.createConcurrentSet();
    private final Set<SubQuery> negativeCache = AnalysisUtil.createConcurrentSet();

    public static class SubQuery {
        final InterProgramPointReplica source;
        final InterProgramPointReplica destination;
        final/*Set<PointsToGraphNode>*/IntSet noKill;
        final/*Set<InstanceKeyRecency>*/IntSet noAlloc;
        final Set<InterProgramPointReplica> forbidden;

        SubQuery(InterProgramPointReplica source, InterProgramPointReplica destination, /*Set<PointsToGraphNode>*/
                 IntSet noKill, final/*Set<InstanceKeyRecency>*/IntSet noAlloc, Set<InterProgramPointReplica> forbidden) {
            this.source = source;
            this.destination = destination;
            this.noKill = noKill;
            this.noAlloc = noAlloc;
            this.forbidden = forbidden;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = destination.hashCode();
            result = prime * result + source.hashCode();
            result = prime * result + noAlloc.size();
            result = prime * result + noKill.size();
            result = prime * result + forbidden.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SubQuery)) {
                return false;
            }
            SubQuery other = (SubQuery) obj;
            if (!source.equals(other.source)) {
                return false;
            }
            if (!destination.equals(other.destination)) {
                return false;
            }
            if (!noAlloc.sameValue(other.noAlloc)) {
                return false;
            }
            if (!noKill.sameValue(other.noKill)) {
                return false;
            }
            if (!forbidden.equals(other.forbidden)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "SubQuery [" + source + " => " + destination + ", noKill=" + noKill + ", noAlloc=" + noAlloc
                    + ", forbidden=" + forbidden + "]";
        }

    }

    /* *****************************************************************************
     *
     * DEPENDENCY TRACKING
     *
     * The following code is responsible for recording dependencies
     */
    private final ConcurrentMap<SubQuery, Set<ReachabilityQueryOrigin>> queryDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<ProgramPointReplica, Set<SubQuery>> calleeQueryDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<ProgramPointReplica, Set<OrderedPair<IMethod, Context>>> calleeMethodDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<SubQuery>> callerQueryDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<SubQuery>> methodQueryDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>> methodMethodDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentIntMap<Set<SubQuery>> killQueryDependencies = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();
    private final ConcurrentIntMap<Set<OrderedPair<IMethod, Context>>> killMethodDependencies = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * Record the fact that the result of query depends on the callees of callSite, and thus, if the callees change,
     * then query may need to be reevaluated.
     *
     * @param query
     * @param callSite
     */
    private void addCalleeDependency(SubQuery query, ProgramPointReplica callSite) {
        assert callSite.getPP() instanceof CallSiteProgramPoint;

        Set<SubQuery> s = calleeQueryDependencies.get(callSite);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<SubQuery> existing = calleeQueryDependencies.putIfAbsent(callSite, s);
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
    private void addCallerDependency(SubQuery query, OrderedPair<IMethod, Context> callGraphNode) {
        Set<SubQuery> s = callerQueryDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<SubQuery> existing = callerQueryDependencies.putIfAbsent(callGraphNode, s);
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
    private void addMethodDependency(SubQuery query, OrderedPair<IMethod, Context> callGraphNode) {
        Set<SubQuery> s = methodQueryDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<SubQuery> existing = methodQueryDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
    }

    private void addKillDependency(SubQuery query, ReferenceVariableReplica readDependencyForKillField) {
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);
        Set<SubQuery> s = killQueryDependencies.get(n);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<SubQuery> existing = killQueryDependencies.putIfAbsent(n, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
    }

    private void removeKillDependency(SubQuery query, ReferenceVariableReplica readDependencyForKillField) {
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);

        Set<SubQuery> s = killQueryDependencies.get(n);
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
        Set<SubQuery> queries = calleeQueryDependencies.get(callSite);
        if (queries != null) {

            Iterator<SubQuery> iter = queries.iterator();
            while (iter.hasNext()) {
                SubQuery mr = iter.next();
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
        Set<SubQuery> queries = callerQueryDependencies.get(callGraphNode);
        if (queries != null) {
            Iterator<SubQuery> iter = queries.iterator();
            while (iter.hasNext()) {
                SubQuery mr = iter.next();
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
        Set<SubQuery> queries = methodQueryDependencies.get(cgnode);
        if (queries != null) {
            Iterator<SubQuery> iter = queries.iterator();
            while (iter.hasNext()) {
                SubQuery mr = iter.next();
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
    private boolean requestRerunQuery(SubQuery mr) {
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
    private void queryResultChanged(SubQuery mr) {
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
    void addCallGraphEdge(CallSiteProgramPoint callSite, Context callerContext, IMethod callee, Context calleeContext) {

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
    private void addDependency(SubQuery query, ReachabilityQueryOrigin origin) {
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
            Set<SubQuery> queries = killQueryDependencies.get(n);
            if (queries != null) {
                Iterator<SubQuery> iter = queries.iterator();
                while (iter.hasNext()) {
                    SubQuery mr = iter.next();
                    // need to re-run the query of mr
                    if (!requestRerunQuery(mr)) {
                        // whoops, no need to rerun this anymore.
                        iter.remove();
                    }
                }
            }
        }
    }

    public void processSubQuery(SubQuery sq) {
        this.computeQuery(Collections.singleton(sq.source), sq.destination, sq.noKill, sq.noAlloc, sq.forbidden);
    }
}
