package analysis.pointer.graph;

import java.util.concurrent.atomic.AtomicLong;

import util.OrderedPair;
import util.intmap.ConcurrentIntMap;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

/**
 * Data structure that tracks whether a desination call graph node is reachable from a source call graph node
 */
public final class CallGraphReachability {
    /**
     * Map from call graph node to the transitive closure of all call graph nodes reachable from that node via method
     * calls, note that every node is reachable from itself
     */
    // Map<OrderedPair<IMethod,Context>,Set<OrderedPair<IMethod,Context>>>
    private ConcurrentIntMap<MutableIntSet> reachableFrom = AnalysisUtil.createConcurrentIntMap();
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
            // The source is reachable from the destination
            return true;
        }

        // In order to avoid races, we actually need to add the dependency before checking the sets
        // and getting a negative result.
        // So we add the dependency now, and check the sets again. Think of the check above as a quick check of the
        // cache before we add the dependency and do the real check.
        addDependency(source, dest, triggeringQuery);

        if (reachableDests == null) {
            reachableDests = reachableFrom.get(source);
        }
        else {
            // we already have the appropriate set, no need to access reachableFrom again to get it.
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
        if (getReachableFrom(callerCGNode).contains(calleeCGNode)) {
            // The callee is already reachable from the caller
            // Whoever made it reachable is responsible for propagating it
            return;
        }

        // Everything reachable from the callee is now reachable from the caller (and any callers of the caller)
        addToCallerTree(callerCGNode, getReachableFrom(calleeCGNode));

        addCallGraphEdgeTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * The call graph nodes in setToAdd are transitively reachable from the caller, add them to its reachable set as
     * well as to the reachable set of any callers of the caller.
     *
     * @param caller caller call graph node
     * @param setToAdd call graph nodes that are reachable from the caller
     * @return The set that was actually added
     */
    private void addToCallerTree(/*OrderedPair<IMethod,Context>*/int caller, /*Set<OrderedPair<IMethod,Context>>*/
                                 IntSet setToAdd) {
        MutableIntSet newlyReachable = MutableSparseIntSet.makeEmpty();
        IntIterator toAddIterator = setToAdd.intIterator();

        while (toAddIterator.hasNext()) {
            int callee = toAddIterator.next();
            if (recordReachableFrom(caller, callee)) {
                // This is a newly reachable node
                newlyReachable.add(callee);
            }
        }

        if (newlyReachable.isEmpty()) {
            // Base case: nothing to propagate
            return;
        }

        // Add anything newly reachable from the caller to its callers
        /*Iterator<ProgramPointReplica>*/IntIterator callSitesCallingCaller = g.getCallersOf(caller).intIterator();
        while (callSitesCallingCaller.hasNext()) {
            int callerOfCaller = getCGNodeForCallSite(/*ProgramPointReplica*/callSitesCallingCaller.next());
            addToCallerTree(callerOfCaller, newlyReachable);
        }
    }

    /**
     * Add that the callee is transitively reachable from the caller
     *
     * @param caller source call graph node
     * @param callee destination call graph node
     * @return true if this is a new relationship
     */
    private boolean recordReachableFrom(/*OrderedPair<IMethod,Context>*/int caller, /*OrderedPair<IMethod,Context>*/
                                        int callee) {
        MutableIntSet s = getReachableFrom(caller);
        boolean added = s.add(callee);
        if (added) {
            // This is a new edge notify any dependencies
            ConcurrentIntMap<MutableIntSet> destinationMap = dependencies.get(caller);
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
    private MutableIntSet getReachableFrom(int cgNode) {
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
    private final ConcurrentIntMap<ConcurrentIntMap</*Set<ProgramPointSubQuery>*/MutableIntSet>> dependencies = AnalysisUtil.createConcurrentIntMap();

    /**
     * Add a dependency from a ProgramPointSubQuery to a call graph query from source to dest
     *
     * @param source source call graph node
     * @param dest destination call graph node
     * @param triggeringQuery query that depends on the results of the query
     */
    private void addDependency(/*OrderedPair<IMethod,Context>*/int source, /*OrderedPair<IMethod,Context>*/int dest, /*ProgramPointSubQuery*/
                               int triggeringQuery) {
        ConcurrentIntMap<MutableIntSet> destinationMap = dependencies.get(source);
        if (destinationMap == null) {
            destinationMap = AnalysisUtil.createConcurrentIntMap();
            ConcurrentIntMap<MutableIntSet> existing = dependencies.putIfAbsent(source, destinationMap);
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
            if (size > 500) {
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
        }
        return sb.toString();
    }
}
