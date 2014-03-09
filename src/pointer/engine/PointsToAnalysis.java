package pointer.engine;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.PointsToGraph;
import pointer.statements.StatementRegistrar;

import com.ibm.wala.ipa.cha.IClassHierarchy;

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
    protected final IClassHierarchy cha;

    /**
     * Create a new analysis with the given abstraction
     */
    public PointsToAnalysis(HeapAbstractionFactory haf, IClassHierarchy cha) {
        this.haf = haf;
        this.cha = cha;
    }

    public abstract PointsToGraph solve(StatementRegistrar registrar);
}
