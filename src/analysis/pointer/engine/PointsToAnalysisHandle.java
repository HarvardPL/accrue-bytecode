package analysis.pointer.engine;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;

/**
 * This interface provides a mechanism to submit StmtAndContexts to the PointsToAnalysis.
 */
public interface PointsToAnalysisHandle {

    /**
     * request that the PointsToAnalysis process the StmtAndContext
     *
     */
    void submitStmtAndContext(StmtAndContext sac);

    /**
     * Get the points to graph.
     *
     * @return
     */
    PointsToGraph pointsToGraph();

    /**
     * Handle changes to the points to graph.
     * 
     * @param delta
     */
    void handleChanges(GraphDelta delta);
}
