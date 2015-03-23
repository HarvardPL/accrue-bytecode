package analysis.pointer.graph;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisHandle;

/**
 * Origin that has no effect. This should only be used for debugging and printing. E.g. getting the sources from a
 * {@link ProgramPointSetClosure} to print them.
 */
public class DoNothingOrigin implements ReachabilityQueryOrigin {

    public static DoNothingOrigin INSTANCE = new DoNothingOrigin();

    private DoNothingOrigin() {
        // do nothing
    }

    @Override
    public void trigger(PointsToAnalysisHandle analysisHandle, GraphDelta changes) {
        // Do nothing
    }

    @Override
    public void trigger(PointsToAnalysisHandle analysisHandle) {
        // Do nothing
    }

    @Override
    public StmtAndContext getStmtAndContext() {
        return null;
    }

}
