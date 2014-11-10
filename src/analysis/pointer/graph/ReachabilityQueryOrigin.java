package analysis.pointer.graph;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisHandle;

public interface ReachabilityQueryOrigin extends AllocationDepender {
    /**
     * This method is called when the reachability query changes from false to true.
     *
     * @param analysisHandle
     */
    void trigger(PointsToAnalysisHandle analysisHandle);

    /**
     * The StmtAndContext for this origin, if any.
     * 
     * @return
     */
    StmtAndContext getStmtAndContext();

}
