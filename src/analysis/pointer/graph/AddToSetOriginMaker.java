package analysis.pointer.graph;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

/**
 * Able to make ReachabilityQueryOrigins such that if the reachability query changes, then an instancekey will be added
 * to the points to set of n.
 *
 * Note that n is a flow insensitive points to graph node.
 */
public class AddToSetOriginMaker implements ReachabilityQueryOriginMaker {
    private final/*PointsToGraphNode*/int n;

    private final/*PointsToGraphNode*/Integer src;

    public AddToSetOriginMaker(/*PointsToGraphNode*/int n, PointsToGraph g) {
        this(n, g, null);
    }

    public AddToSetOriginMaker(/*PointsToGraphNode*/int n, PointsToGraph g, /*PointsToGraphNode*/Integer src) {
        this.n = n;
        this.src = src;
        assert !g.isFlowSensitivePointsToGraphNode(n);
    }

    @Override
    public ReachabilityQueryOrigin makeOrigin(int src, int trg, InterProgramPointReplica ippr) {
        assert this.src == null ? src >= 0 : src < 0 : "At most one of this.src and src should be specified";
        return new AddToSetOrigin(n, this.src == null ? src : this.src, trg, ippr);
    }

    private static class AddToSetOrigin implements ReachabilityQueryOrigin {
        private final/*PointsToGraphNode*/int n;

        private final/*PointsToGraphNode*/int src;
        private final/*InstanceKeyRecency*/int trg;
        private final InterProgramPointReplica ippr;

        public AddToSetOrigin(int n, int src, int trg, InterProgramPointReplica ippr) {
            this.n = n;
            this.src = src;
            this.trg = trg;
            this.ippr = ippr;
        }

        @Override
        public void trigger(PointsToAnalysisHandle analysisHandle, GraphDelta changes) {
            trigger(analysisHandle);
        }

        @Override
        public void trigger(PointsToAnalysisHandle analysisHandle) {
            PointsToGraph g = analysisHandle.pointsToGraph();
            if (g.pointsTo(src, trg, ippr, this)) {
                if (!g.pointsTo(n, trg, null, this)) {
                    // add edge!
                    GraphDelta delta = g.addEdge(g.lookupPointsToGraphNodeDictionary(n),
                                                 g.lookupInstanceKeyDictionary(trg),
                                                 null);
                    analysisHandle.handleChanges(delta);
                }
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
            result = prime * result + ((ippr == null) ? 0 : ippr.hashCode());
            result = prime * result + n;
            result = prime * result + src;
            result = prime * result + trg;
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
            if (!(obj instanceof AddToSetOrigin)) {
                return false;
            }
            AddToSetOrigin other = (AddToSetOrigin) obj;
            if (n != other.n) {
                return false;
            }
            if (src != other.src) {
                return false;
            }
            if (trg != other.trg) {
                return false;
            }
            if (ippr == null) {
                if (other.ippr != null) {
                    return false;
                }
            }
            else if (!ippr.equals(other.ippr)) {
                return false;
            }
            return true;
        }

    }
}
