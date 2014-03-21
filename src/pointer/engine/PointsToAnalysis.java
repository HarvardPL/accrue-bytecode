package pointer.engine;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.PointsToGraph;
import pointer.statements.StatementRegistrar;
import analysis.AnalysisUtil;

/**
 * Points-to analysis engine
 */
public abstract class PointsToAnalysis {

    /**
     * Defining abstraction factory for this points-to analysis
     */
    protected final HeapAbstractionFactory haf;
    /**
     * Class hierarchy
     */
    protected final AnalysisUtil util;

    /**
     * Create a new analysis with the given abstraction
     */
    public PointsToAnalysis(HeapAbstractionFactory haf, AnalysisUtil util) {
        this.haf = haf;
        this.util = util;
    }

    public abstract PointsToGraph solve(StatementRegistrar registrar);
}
