package analysis.pointer.graph;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;

import util.OrderedPair;
import util.intmap.ConcurrentIntMap;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

/**
 * Data structure that tracks whether a destination call graph node is reachable from a source call graph node, and also
 * whether a destination call graph node is reachable after returning to a given call site.
 */
public final class CallGraphReachability {
    /**
     * Temporary flag to control the use of reachable by return to a call site. Use this flag to test performance and
     * correctness, and remove when we decide if we want to keep it.
     */
    private static final boolean USE_REACHABLE_BY_RETURN_TO = true;

    /**
     * Map from call graph node to the transitive closure of all call graph nodes reachable from that node via method
     * calls, note that every node is reachable from itself
     */
    // Map<OrderedPair<IMethod,Context>,Set<OrderedPair<IMethod,Context>>>
    private ConcurrentIntMap<MutableIntSet> reachableFrom = AnalysisUtil.createConcurrentIntMap();

    /**
     * Map from call sites to the call graph nodes that are reachable if execution returns to that call site.
     */
    // Map<ProgramPointReplica,Set<OrderedPair<IMethod,Context>>>
    private ConcurrentIntMap<MutableIntSet> reachableByReturnTo = AnalysisUtil.createConcurrentIntMap();

    /**
     * Points-to graph
     */
    private final PointsToGraph g;
    private final ProgramPointReachability ppr;

    /**
     * Create a new algorithm with empty caches
     *
     * @param handle Used to submit program point reachability results when the results of this algorithm change
     */
    public CallGraphReachability(ProgramPointReachability ppr, PointsToGraph g) {
        this.ppr = ppr;
        this.g = g;
    }

    /**
     * Check whether the destination call graph node is reachable via method calls from the source call graph node
     *
     * @param source source call graph node
     * @param dest destination call graph node
     * @param triggeringQuery query that requested this result. If the result changes the query may be rerun.
     * @return true if the destination is reachable via method calls from the source
     */
    boolean isReachable(/*OrderedPair<IMethod,Context>*/int source, /*OrderedPair<IMethod,Context>*/int dest, /*ProgramPointSubQuery*/
                        int triggeringQuery) {
        if (source == dest) {
            // A node is always reachable from itself
            return true;
        }

        IntSet reachableDests = reachableFrom.get(source);
        if (reachableDests != null && reachableDests.contains(dest)) {
            // The destination is reachable from the source
            return true;
        }

        // In order to avoid races, we actually need to add the dependency before checking the sets
        // and getting a negative result.
        // So we add the dependency now, and check the sets again. Think of the check above as a quick check of the
        // cache before we add the dependency and do the real check.
        addDependencyOnSourceNode(source, dest, triggeringQuery);

        if (reachableDests == null) {
            reachableDests = reachableFrom.get(source);
        }
        else {
            // we already have the appropriate set, no need to access reachableFrom again to get it.
        }
        return reachableDests == null ? false : reachableDests.contains(dest);
    }

    /**
     * Check whether the destination call graph node is reachable by returning to the call site.
     *
     * @param callSite call site program point
     * @param dest destination call graph node
     * @param triggeringQuery query that requested this result. If the result changes the query may be rerun.
     * @return true if the destination is reachable by an execution that returns to the specified call site.
     */
    boolean isReachableByReturnTo(/*ProgramPointReplica*/int callSite, /*OrderedPair<IMethod,Context>*/
                                  int dest, /*ProgramPointSubQuery*/
                                  int triggeringQuery) {
        if (!USE_REACHABLE_BY_RETURN_TO) {
            return true;
        }
        assert g.lookupCallSiteReplicaDictionary(callSite) != null;
        assert g.lookupCallSiteReplicaDictionary(callSite).getPP() instanceof CallSiteProgramPoint;
        assert g.lookupCallGraphNodeDictionary(dest) != null;

        IntSet reachableDests = reachableByReturnTo.get(callSite);
        if (reachableDests != null && reachableDests.contains(dest)) {
            // The destination is reachable by returning to the call site
            return true;
        }

        // In order to avoid races, we actually need to add the dependency before checking the sets
        // and getting a negative result.
        // So we add the dependency now, and check the sets again. Think of the check above as a quick check of the
        // cache before we add the dependency and do the real check.
        addDependencyOnCallSite(callSite, dest, triggeringQuery);

        if (reachableDests == null) {
            reachableDests = reachableByReturnTo.get(callSite);
        }
        else {
            // we already have the appropriate set, no need to access reachableByReturnTo again to get it.
        }
        return reachableDests == null ? false : reachableDests.contains(dest);
    }

    private final AtomicLong addCallGraphEdgeTime = new AtomicLong(0);

    /**
     * Notification that a callee was added to a given call site
     *
     * @param callerSite call site with the new callee
     * @param calleeCGNode call graph node that is the new callee
     */
    void addCallGraphEdge(/*ProgramPointReplica*/int callerSite, /*OrderedPair<IMethod,Context>*/int calleeCGNode) {
        long start = System.currentTimeMillis();

        /*OrderedPair<IMethod,Context>*/int callerCGNode = getCGNodeForCallSite(callerSite);
        if (getReachableFromSourceNode(callerCGNode).contains(calleeCGNode)) {
            // The callee is already reachable from the caller
            // Whoever made it reachable is responsible for propagating the appropriate information.
        }
        else {
            // Everything reachable from the callee is now reachable from the caller (and any callers of the caller)
            addToCallerTree(callerSite, getReachableFromSourceNode(calleeCGNode));

            if (USE_REACHABLE_BY_RETURN_TO) {
                // propagate the reachableByReturnTo callerSite to all call sites within the callee.
                /*Iterator<ProgramPointReplica>*/IntIterator callSitesWithinCalleeMethod = this.g.getCallSitesWithinMethod(calleeCGNode)
                                                                                                  .intIterator();
                /*Set<OrderedPair<IMethod,Context>>*/IntSet reachableByReturnToCallerSite = this.getReachableByReturnTo(callerSite);
                while (callSitesWithinCalleeMethod.hasNext()) {
                    addToReturnToCallSite(callSitesWithinCalleeMethod.next(), reachableByReturnToCallerSite);
                }

                // propagate the nodes that are reachable from calleeCGNode into the reachableByReturnTo
                // sets of the call sites that come before callerSite (within the same method, callerCGNode).
                /*Iterator<ProgramPointReplica>*/IntIterator callSitesBefore = callSitesBefore(callerSite);
                /*Set<OrderedPair<IMethod,Context>>*/IntSet reachableFromCallee = getReachableFromSourceNode(calleeCGNode);
                while (callSitesBefore.hasNext()) {
                    addToReturnToCallSite(callSitesBefore.next(), reachableFromCallee);
                }
            }
        }


        addCallGraphEdgeTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * Return an iterator over the ProgramPointReplicas of the call sites that might come before callSite in the same
     * method.
     *
     * @param callerSite
     * @return
     */
    private IntIterator callSitesBefore(final int callSite) {
        final IntIterator iter = this.g.getCallSitesWithinMethod(this.getCGNodeForCallSite(callSite)).intIterator();
        return new IntIterator() {
            private int next = -1;

            @Override
            public boolean hasNext() {
                while (next < 0 && iter.hasNext()) {
                    next = iter.next();
                    if (next == callSite) {
                        next = -1;
                    }
                    else {
                        break;
                    }
                }
                return next >= 0;
            }

            @Override
            public int next() {
                if (hasNext()) {
                    int n = this.next;
                    this.next = -1;
                    return n;
                }
                throw new NoSuchElementException();
            }

        };

    }

    /**
     * The call graph nodes in setToAdd are transitively reachable from the caller, add them to its reachable set as
     * well as to the reachable set of any callers of the caller.
     *
     * @param caller caller call graph node
     * @param setToAdd call graph nodes that are reachable from the caller
     * @return The set that was actually added
     */
    private void addToCallerTree(/*ProgramPointReplica*/int callSite, /*Set<OrderedPair<IMethod,Context>>*/
                                 IntSet setToAdd) {
        int caller = getCGNodeForCallSite(callSite);

        MutableIntSet newlyReachable = MutableSparseIntSet.makeEmpty();
        IntIterator toAddIterator = setToAdd.intIterator();

        while (toAddIterator.hasNext()) {
            int callee = toAddIterator.next();
            if (recordReachableFromSourceNode(caller, callee)) {
                // This is a newly reachable node
                newlyReachable.add(callee);
            }
        }

        if (newlyReachable.isEmpty()) {
            // Base case: nothing to propagate
        }
        else {
            // Add anything newly reachable from the caller to its callers
            /*Iterator<ProgramPointReplica>*/IntIterator callSitesCallingCaller = g.getCallersOf(caller).intIterator();
            while (callSitesCallingCaller.hasNext()) {
                addToCallerTree(callSitesCallingCaller.next(), newlyReachable);
            }

            if (USE_REACHABLE_BY_RETURN_TO) {
                // we also need to add newlyReachable to the appropriate call sites
                IntIterator callerSitesOfCaller = g.getCallersOf(caller).intIterator();
                while (callerSitesOfCaller.hasNext()) {
                    int callSiteOfCaller = callerSitesOfCaller.next();
                    IntIterator callSitesBefore = callSitesBefore(callSiteOfCaller);
                    while (callSitesBefore.hasNext()) {
                        int csb = callSitesBefore.next();
                        addToReturnToCallSite(csb, newlyReachable);
                    }
                }
            }
        }
    }

    /**
     * callSite is a node such that, if you return to that call site (i.e., after finishing executing a callee of the
     * call site) it may be possible to reach any of the CG nodes in setToAdd. This method adds all of the elements of
     * setToAdd to the reachableByReturnTo of callSite, and recurses on all call sites *in callees of callSite*.
     */
    private void addToReturnToCallSite(/*ProgramPointReplica*/int callSite, /*Set<OrderedPair<IMethod,Context>>*/
                                       IntSet setToAdd) {
        if (!USE_REACHABLE_BY_RETURN_TO) {
            throw new UnsupportedOperationException();
        }

        MutableIntSet newlyReachedFromCallSite = MutableSparseIntSet.makeEmpty();
        IntIterator iter = setToAdd.intIterator();

        while (iter.hasNext()) {
            int callee = iter.next();
            if (recordReachableByReturnTo(callSite, callee)) {
                // This is a newly reachable node
                newlyReachedFromCallSite.add(callee);
            }
        }

        if (newlyReachedFromCallSite.isEmpty()) {
            // base case, nothing left to propagate.
            return;
        }

        // now we need to recurse on all call sites in the callees of callSite
        IntIterator calleesOfCallSite = g.getCalleesOf(callSite).intIterator();
        while (calleesOfCallSite.hasNext()) {
            int callee = calleesOfCallSite.next();

            IntIterator calleeCallSites = g.getCallSitesWithinMethod(callee).intIterator();
            while (calleeCallSites.hasNext()) {
                int calleeCallSite = calleeCallSites.next();
                addToReturnToCallSite(calleeCallSite, newlyReachedFromCallSite);
            }
        }
    }

    /**
     * Add that the callee is transitively reachable from the caller
     *
     * @param caller source call graph node
     * @param callee destination call graph node
     * @return true if this is a new relationship
     */
    private boolean recordReachableFromSourceNode(/*OrderedPair<IMethod,Context>*/int caller, /*OrderedPair<IMethod,Context>*/
                                        int callee) {
        MutableIntSet s = getReachableFromSourceNode(caller);
        boolean added = s.add(callee);
        if (added) {
            // This is a new edge notify any dependencies
            ConcurrentIntMap<MutableIntSet> destinationMap = dependenciesOnSourceNode.get(caller);
            if (destinationMap != null) {
                MutableIntSet deps = destinationMap.remove(callee);
                if (deps != null) {
                    IntIterator iter = deps.intIterator();
                    while (iter.hasNext()) {
                        ppr.requestRerunQuery(iter.next());
                    }
                }
            }
        }
        return added;
    }

    /**
     * Add that the callee is transitively reachable if control returns to callSite
     *
     * @param caller source call graph node
     * @param callee destination call graph node
     * @return true if this is a new relationship
     */
    private boolean recordReachableByReturnTo(/*ProgramPointReplica*/int callSite, /*OrderedPair<IMethod,Context>*/
                                              int callee) {
        MutableIntSet s = getReachableByReturnTo(callSite);
        boolean added = s.add(callee);
        if (added) {
            // This is a new edge notify any dependencies
            ConcurrentIntMap<MutableIntSet> destinationMap = dependenciesOnCallSite.get(callSite);
            if (destinationMap != null) {
                MutableIntSet deps = destinationMap.remove(callee);
                if (deps != null) {
                    IntIterator iter = deps.intIterator();
                    while (iter.hasNext()) {
                        ppr.requestRerunQuery(iter.next());
                    }
                }
            }
        }
        return added;
    }

    /**
     * Get the set of call graph nodes reachable from the given call graph node
     *
     * @param cgNode node to get reachable from
     * @return set of nodes reachable from the given node, always includes the node itself
     */
    private MutableIntSet getReachableFromSourceNode(int cgNode) {
        MutableIntSet s = reachableFrom.get(cgNode);
        if (s == null) {
            s = AnalysisUtil.createDenseConcurrentIntSet(0);
            // every node is reachable from itself
            s.add(cgNode);
            MutableIntSet existing = reachableFrom.putIfAbsent(cgNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        return s;
    }

    /**
     * Get the set of call graph nodes reachable by returning to the given call site
     *
     * @param callSite call site to get reachable from
     * @return set of nodes reachable by returning to the given call site, which always includes the containing method
     *         of the call site.
     */
    private MutableIntSet getReachableByReturnTo(/*ProgramPointReplica*/int callSite) {
        MutableIntSet s = reachableByReturnTo.get(callSite);
        if (s == null) {
            s = AnalysisUtil.createDenseConcurrentIntSet(0);
            // the containing method is always reachable.
            s.add(this.getCGNodeForCallSite(callSite));
            MutableIntSet existing = reachableByReturnTo.putIfAbsent(callSite, s);
            if (existing != null) {
                s = existing;
            }
        }
        return s;
    }

    /**
     * Get the call graph node containing the given call site
     *
     * @param callSite call site
     * @return call graph node index
     */
    private/*OrderedPair<IMethod,Context>*/int getCGNodeForCallSite(/*ProgramPointReplica*/int callSite) {
        ProgramPointReplica pprep = g.lookupCallSiteReplicaDictionary(callSite);
        OrderedPair<IMethod, Context> pair = new OrderedPair<>(pprep.getPP().getContainingProcedure(),
                                                               pprep.getContext());
        return g.lookupCallGraphNodeDictionary(pair);
    }

    /**
     * Clear the caches. Use before an error checking run to make sure the caches don't perpetuate buggy conclusions
     */
    void clearCaches() {
        reachableFrom = AnalysisUtil.createConcurrentIntMap();
        reachableByReturnTo = AnalysisUtil.createConcurrentIntMap();
    }

    /**
     * Print information about the analysis so far
     */
    void printDiagnostics() {
        double addEdge = addCallGraphEdgeTime.get() / 1000.0;
        double analysisTime = (System.currentTimeMillis() - PointsToAnalysis.startTime) / 1000.0;
        StringBuilder sb = new StringBuilder();
        sb.append("\n%%%%%%%%%%%%%%%%% CALL GRAPH REACHABILITY %%%%%%%%%%%%%%%%%\n");
        sb.append("\tAddCallGraphEdge: " + addEdge + "s; RATIO: " + addEdge / analysisTime + "\n");
        sb.append("\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n");
        System.err.println(sb.toString());
    }

    /**
     * Map from a source CG node to a map from the destination CG nodes requested from that source to the set of
     * ProgramPointSubQuery that depend on whether the destination is reachable from the source.
     */
    // Map<OrderedPair<IMethod,Context>,Map<OrderedPair<IMethod,Context>,Set<ProgramPointSubQuery>>>
    private final ConcurrentIntMap<ConcurrentIntMap</*Set<ProgramPointSubQuery>*/MutableIntSet>> dependenciesOnSourceNode = AnalysisUtil.createConcurrentIntMap();

    /**
     * Add a dependency from a ProgramPointSubQuery to a call graph query from source to dest
     *
     * @param source source call graph node
     * @param dest destination call graph node
     * @param triggeringQuery query that depends on the results of the query
     */
    private void addDependencyOnSourceNode(/*OrderedPair<IMethod,Context>*/int source, /*OrderedPair<IMethod,Context>*/int dest, /*ProgramPointSubQuery*/
                               int triggeringQuery) {
        ConcurrentIntMap<MutableIntSet> destinationMap = dependenciesOnSourceNode.get(source);
        if (destinationMap == null) {
            destinationMap = AnalysisUtil.createConcurrentIntMap();
            ConcurrentIntMap<MutableIntSet> existing = dependenciesOnSourceNode.putIfAbsent(source, destinationMap);
            if (existing != null) {
                destinationMap = existing;
            }
        }

        MutableIntSet s = destinationMap.get(dest);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = destinationMap.putIfAbsent(dest, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(triggeringQuery);
    }

    /**
     * Map from a call site program point to a map from the destination CG nodes requested from that call site to the set of
     * ProgramPointSubQuery that depend on whether the destination is reachable by returning to the callsite.
     */
    // Map<OrderedPair<IMethod,Context>,Map<OrderedPair<IMethod,Context>,Set<ProgramPointSubQuery>>>
    private final ConcurrentIntMap<ConcurrentIntMap</*Set<ProgramPointSubQuery>*/MutableIntSet>> dependenciesOnCallSite = AnalysisUtil.createConcurrentIntMap();


    /**
     * Add a dependency from a ProgramPointSubQuery to a call graph query from source to dest
     *
     * @param callSite callSite
     * @param dest destination call graph node
     * @param triggeringQuery query that depends on the results of the query
     */
    private void addDependencyOnCallSite(/*ProgramPointReplica*/int callSite, /*OrderedPair<IMethod,Context>*/
                                         int dest, /*ProgramPointSubQuery*/
                               int triggeringQuery) {
        ConcurrentIntMap<MutableIntSet> destinationMap = dependenciesOnCallSite.get(callSite);
        if (destinationMap == null) {
            destinationMap = AnalysisUtil.createConcurrentIntMap();
            ConcurrentIntMap<MutableIntSet> existing = dependenciesOnCallSite.putIfAbsent(callSite, destinationMap);
            if (existing != null) {
                destinationMap = existing;
            }
        }

        MutableIntSet s = destinationMap.get(dest);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = destinationMap.putIfAbsent(dest, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(triggeringQuery);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        IntIterator iter = reachableFrom.keyIterator();
        while (iter.hasNext()) {
            int cg = iter.next();
            int size = 0;
            IntIterator reachable = reachableFrom.get(cg).intIterator();
            while (reachable.hasNext()) {
                reachable.next();
                size++;
            }
            if (size < 10000) {
                sb.append("0");
            }
            if (size < 1000) {
                sb.append("0");
            }
            if (size < 100) {
                sb.append("0");
            }
            if (size < 10) {
                sb.append("0");
            }
            sb.append(size);
            sb.append(" ");
            sb.append(g.lookupCallGraphNodeDictionary(cg));
            sb.append("\n");
        }
        return sb.toString();
    }
}
