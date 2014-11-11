package analysis.pointer.engine;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.AddNonMostRecentOrigin;
import analysis.pointer.graph.AddToSetOriginMaker.AddToSetOrigin;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ProgramPointReachability.SubQuery;

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
     * Request the the PointsToAnalysis process the AddNonMostRecentOrigin.
     *
     * @param task
     */
    void submitAddNonMostRecentTask(AddNonMostRecentOrigin task);


    /**
     * Request the the PointsToAnalysis process the AddToSetOrigin.
     *
     * @param task
     */
    void submitAddToSetTask(AddToSetOrigin task);


    /**
     * Request the the ProgramPointReachability query be processed.
     *
     * @param task
     */
    void submitReachabilityQuery(SubQuery mr);

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
