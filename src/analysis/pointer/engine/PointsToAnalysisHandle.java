package analysis.pointer.engine;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

/**
 * This interface provides a mechanism to submit StmtAndContexts to the PointsToAnalysis.
 */
public interface PointsToAnalysisHandle {

    /**
     * request that the PointsToAnalysis process the StmtAndContext
     * 
     */
    void submitStmtAndContext(StmtAndContext sac);
}
