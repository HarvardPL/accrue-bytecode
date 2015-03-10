package analysis.pointer.graph;

import util.OrderedPair;
import util.intmap.ConcurrentIntMap;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * Data structure that tracks whether a desination call graph node is reachable from a source call graph node
 */
public class CallGraphReachability {
    /**
     * Map from call graph node to the transitive closure of all call graph nodes reachable from that node via method
     * calls, note that every node is reachable from itself
     */
    // Map<OrderedPair<IMethod,Context>,Set<OrderedPair<IMethod,Context>>>
    private ConcurrentIntMap<MutableIntSet> reachableFrom = AnalysisUtil.createConcurrentIntMap();
    /**
     * Map from call graph node to all nodes that can transitively reach that node via method calls, note that every
     * node reaches itselt
     */
    // Map<OrderedPair<IMethod,Context>,Set<OrderedPair<IMethod,Context>>>
    private ConcurrentIntMap<MutableIntSet> reaches = AnalysisUtil.createConcurrentIntMap();

    /**
     * Used to submit program point reachability results when the results of this algorithm change
     */
    private final PointsToAnalysisHandle handle;
    /**
     * Points-to graph
     */
    private PointsToGraph g;

    /**
     * Create a new algorithm with empty caches
     *
     * @param handle Used to submit program point reachability results when the results of this algorithm change
     */
    public CallGraphReachability(PointsToAnalysisHandle handle, PointsToGraph g) {
        this.handle = handle;
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

        if (reachableFrom.get(source).contains(dest)) {
            // The source is reachable from the destination
            return true;
        }
        addDependency(source, dest, triggeringQuery);
        return false;
    }

    /**
     * Notification that a callee was added to a given call site XXX Make sure this is thread safe!
     *
     * @param callerSite call site with the new callee
     * @param calleeCGNode call graph node that is the new callee
     */
    void addCallGraphEdge(/*ProgramPointReplica*/int callerSite, /*OrderedPair<IMethod,Context>*/int calleeCGNode) {
        /*OrderedPair<IMethod,Context>*/int callerCGNode = getCGNodeForCallSite(callerSite);

        // Add the edge to the reachableFrom cache
        if (!recordReachableFrom(callerCGNode, calleeCGNode)) {
            // The callee was already reachable from the caller
            // Whoever added that edge is responsible for propagating it
            return;
        }

        // Everything reachable from the callee is now reachable from the caller (and any callers of the caller)
        IntIterator calleesOfCallee = getReachableFrom(calleeCGNode).intIterator();
        IntIterator callersOfCaller = getReaches(callerCGNode).intIterator();
        while (callersOfCaller.hasNext()) {
            while (calleesOfCallee.hasNext()) {
                recordReachableFrom(callersOfCaller.next(), calleesOfCallee.next());
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
    private boolean recordReachableFrom(int caller, int callee) {
        MutableIntSet s = getReachableFrom(caller);
        boolean added = s.add(callee);
        if (added) {
            // This is a new edge

            // Add the edge to the reverse map
            getReaches(callee).add(caller);

            // Notify any dependencies
            ConcurrentIntMap<MutableIntSet> destinationMap = dependencies.get(caller);
            if (destinationMap != null) {
                MutableIntSet deps = destinationMap.get(callee);
                if (deps != null) {
                    IntIterator iter = deps.intIterator();
                    while (iter.hasNext()) {
                        handle.submitReachabilityQuery(ProgramPointSubQuery.lookupDictionary(iter.next()));
                    }
                }
            }
        }
        return added;
    }


    /**
     * Get the set of call graph nodes that reach the given call graph node
     *
     * @param cgNode get nodes that reach this node
     * @return set of nodes that reach the given node, always includes the node itself
     */
    private MutableIntSet getReaches(int cgNode) {
        MutableIntSet s = reaches.get(cgNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = reaches.putIfAbsent(cgNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(cgNode);
        return s;
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
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = reachableFrom.putIfAbsent(cgNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(cgNode);
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
        reaches = AnalysisUtil.createConcurrentIntMap();
    }

    /**
     * Print information about the analysis so far
     */
    void printDiagnostics() {
        // TODO Auto-generated method stub

    }

    /**
     * Map from a source CG node to a map from the destination CG nodes requested from that source to the set of
     * ProgramPointSubQuery that depend on whether the destination is reachable from the source.
     */
    // Map<OrderedPair<IMethod,Context>,Map<OrderedPair<IMethod,Context>,Set<ProgramPointSubQuery>>>
    private final ConcurrentIntMap<ConcurrentIntMap</*Set<ProgramPointSubQuery>*/MutableIntSet>> dependencies = AnalysisUtil.createConcurrentIntMap();

    /**
     * Add a dependecy from a ProgramPointSubQuery to a call graph query from source to dest
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
            s = AnalysisUtil.createDenseConcurrentIntSet();
            MutableIntSet existing = destinationMap.putIfAbsent(dest, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(triggeringQuery);
    }
}
