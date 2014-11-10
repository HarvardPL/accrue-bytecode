package analysis.pointer.graph;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisHandle;

public interface AllocationDepender {
    /**
     * This method is called when a new allocation site is registered.
     *
     * @param analysisHandle
     */
    //    void trigger(PointsToAnalysisHandle analysisHandle);

    /**
     * This method is called when a new allocation site is registered, with the GraphDelta.
     * 
     */
    void trigger(PointsToAnalysisHandle analysisHandle, GraphDelta changes);

    /**
     * The StmtAndContext for this origin, if any.
     *
     * @return
     */
    StmtAndContext getStmtAndContext();


}
