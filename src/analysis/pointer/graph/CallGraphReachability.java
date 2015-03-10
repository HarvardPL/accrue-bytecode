package analysis.pointer.graph;

import util.intmap.IntMap;
import analysis.AnalysisUtil;

import com.ibm.wala.util.intset.MutableIntSet;

public class CallGraphReachability {
    /**
     * Map from call graph node to the transitive closure of all call graph nodes reachable from that node via method
     * calls
     */
    // Map<OrderedPair<IMethod,Context>,Set<OrderedPair<IMethod,Context>>>
    private final IntMap<MutableIntSet> reachableFrom = AnalysisUtil.createConcurrentIntMap();
    /**
     * Map from call graph node to all nodes that can transitively reach that node via method calls
     */
    // Map<OrderedPair<IMethod,Context>,Set<OrderedPair<IMethod,Context>>>
    private final IntMap<MutableIntSet> reaches = AnalysisUtil.createConcurrentIntMap();

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
        // TODO Auto-generated method stub
        addDependency(source, dest, triggeringQuery);
        return false;
    }

    /**
     * Add a dependecy from a ProgramPointSubQuery to a call graph query from source to dest
     *
     * @param source source call graph node
     * @param dest destination call graph node
     * @param triggeringQuery query that depends on the results of the query
     */
    private void addDependency(/*OrderedPair<IMethod,Context>*/int source, /*OrderedPair<IMethod,Context>*/int dest, /*ProgramPointSubQuery*/
                               int triggeringQuery) {
        // TODO Auto-generated method stub
    }

    /**
     * Notification that a callee was added to a given call site
     *
     * @param callerSite call site with the new callee
     */
    void calleeAddedTo(/*ProgramPointReplica*/int callerSite) {
        // TODO Auto-generated method stub

    }

    /**
     * Notification that a new caller was added to the given call graph node
     *
     * @param calleeCGNode call graph node that has a new caller
     */
    void callerAddedTo(/*OrderedPair<IMethod,Context>*/int calleeCGNode) {
        // TODO Auto-generated method stub

    }

    /**
     * Clear the caches. Use before an error checking run to make sure the caches don't perpetuate buggy conclusions
     */
    void clearCaches() {
        // TODO Auto-generated method stub

    }

    /**
     * Print information about the analysis so far
     */
    void printDiagnostics() {
        // TODO Auto-generated method stub

    }
}
