package analysis.pointer.graph;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.engine.PointsToTask;

/**
 * If it becomes the case that isAllocInScope(rvr, i), then we need to add nonMostRecentVersion(i) to n.
 */
public class AddNonMostRecentOrigin implements ReachabilityQueryOrigin, PointsToTask {
    private final/*PointsToGraphNode*/int n;
    private final ReferenceVariableReplica rvr;
    private final/*InstanceKeyRecency*/int i;

    public AddNonMostRecentOrigin(/*PointsToGraphNode*/int n, ReferenceVariableReplica rvr, /*InstanceKeyRecency*/
                                  int i) {
        this.n = n;
        this.rvr = rvr;
        this.i = i;
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
        PointsToGraph g = analysisHandle.pointsToGraph();

        assert g.isMostRecentObject(i);
        assert !g.isFlowSensitivePointsToGraphNode(n);

        if (g.isAllocInScope(rvr, i, null)) {
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

}
