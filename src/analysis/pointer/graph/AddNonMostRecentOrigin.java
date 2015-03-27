package analysis.pointer.graph;

import java.util.concurrent.atomic.AtomicInteger;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.engine.PointsToTask;

/**
 * For a flow-insensitive reference variable replica rvr with local scope, if an allocation to i comes in the scope of
 * rvr, then then we need to add nonMostRecentVersion(i) to n.
 */
public class AddNonMostRecentOrigin implements ReachabilityQueryOrigin, PointsToTask {
    private final/*PointsToGraphNode*/int n;
    private final ReferenceVariableReplica rvr;
    private final/*InstanceKeyRecency*/int i;

    public static final AtomicInteger count = new AtomicInteger(0);
    public static final AtomicInteger total = new AtomicInteger(0);

    public AddNonMostRecentOrigin(/*PointsToGraphNode*/int n, ReferenceVariableReplica rvr, /*InstanceKeyRecency*/
                                  int i) {
        this.n = n;
        this.rvr = rvr;
        this.i = i;
        assert rvr.hasLocalScope();

    }

    @Override
    public void trigger(PointsToAnalysisHandle analysisHandle, GraphDelta changes) {
        trigger(analysisHandle);
    }

    @Override
    public void trigger(PointsToAnalysisHandle analysisHandle) {
        analysisHandle.submitAddNonMostRecentTask(this);
    }

    @Override
    public void process(PointsToAnalysisHandle analysisHandle) {
        if (PointsToAnalysisMultiThreaded.PRINT_NUM_PROCESSED) {
            count.incrementAndGet();
            total.incrementAndGet();
        }
        PointsToGraph g = analysisHandle.pointsToGraph();

        assert g.isMostRecentObject(i);
        assert !g.isFlowSensitivePointsToGraphNode(n);

        if (g.isAllocInScope(rvr, i, this)) {
            GraphDelta changes = g.addEdge(g.lookupPointsToGraphNodeDictionary(n),
                                           g.lookupInstanceKeyDictionary(g.nonMostRecentVersion(i)),
                                           null);
            analysisHandle.handleChanges(changes);
        }
    }

    @Override
    public StmtAndContext getStmtAndContext() {
        return null;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + i;
        result = prime * result + n;
        result = prime * result + ((rvr == null) ? 0 : rvr.hashCode());
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
        if (!(obj instanceof AddNonMostRecentOrigin)) {
            return false;
        }
        AddNonMostRecentOrigin other = (AddNonMostRecentOrigin) obj;
        if (i != other.i) {
            return false;
        }
        if (n != other.n) {
            return false;
        }
        if (rvr == null) {
            if (other.rvr != null) {
                return false;
            }
        }
        else if (!rvr.equals(other.rvr)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "AddNonMostRecentOrigin [n=" + this.n + ", rvr=" + this.rvr + ", i=" + this.i + "]";
    }
}
