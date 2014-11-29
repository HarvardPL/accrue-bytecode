package analysis.pointer.graph;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

/**
 * Represents that the origin of a reachability query was a StmtAndContext.
 */
public class StmtAndContextReachabilityOriginator implements ReachabilityQueryOrigin, ReachabilityQueryOriginMaker {
    private final StmtAndContext sac;

    public StmtAndContextReachabilityOriginator(StmtAndContext sac) {
        this.sac = sac;
    }

    @Override
    public ReachabilityQueryOrigin makeOrigin(int src, int trg, InterProgramPointReplica ippr) {
        return this;
    }

    @Override
    public void trigger(PointsToAnalysisHandle analysisHandle, GraphDelta changes) {
        analysisHandle.submitStmtAndContext(this.sac, changes);
    }

    @Override
    public void trigger(PointsToAnalysisHandle analysisHandle) {
        analysisHandle.submitStmtAndContext(this.sac);
    }

    @Override
    public StmtAndContext getStmtAndContext() {
        return sac;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((sac == null) ? 0 : sac.hashCode());
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
        if (!(obj instanceof StmtAndContextReachabilityOriginator)) {
            return false;
        }
        StmtAndContextReachabilityOriginator other = (StmtAndContextReachabilityOriginator) obj;
        if (sac == null) {
            if (other.sac != null) {
                return false;
            }
        }
        else if (!sac.equals(other.sac)) {
            return false;
        }
        return true;
    }

}
