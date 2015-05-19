package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public abstract class StringStatement implements ConstraintStatement {

    protected final IMethod method;

    protected StringStatement(IMethod method) {
        this.method = method;
    }

    /* The last four are only needed for the VirtualMethodCallString class */
    protected abstract boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                                StmtAndContext originator, HeapAbstractionFactory haf,
                                                StatementRegistrar registrar);

    protected abstract void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                                     PointsToIterable pti, StmtAndContext originator,
                                                     StatementRegistrar registrar);

    protected abstract void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                                      PointsToIterable pti, StmtAndContext originator,
                                                      StatementRegistrar registrar);

    protected abstract GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                                PointsToIterable pti, StmtAndContext originator,
                                                StatementRegistrar registrar);

    protected abstract GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                                 PointsToIterable pti, StatementRegistrar registrar,
                                                 StmtAndContext originator);

    @Override
    public final GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                    StatementRegistrar registrar, StmtAndContext originator) {
        PointsToIterable pti = delta == null ? g : delta;

        GraphDelta changes = new GraphDelta(g);

        this.registerWriteDependencies(context, haf, g, pti, originator, registrar);
        if (this.writersAreActive(context, g, pti, originator, haf, registrar)) {
            this.registerReadDependencies(context, haf, g, pti, originator, registrar);
            changes.combine(this.activateReads(context, haf, g, pti, originator, registrar));
            changes.combine(this.updateSolution(context, haf, g, pti, registrar, originator));
        }

        return changes;
    }

    @Override
    public IMethod getMethod() {
        return this.method;
    }

    @Override
    public final int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public final boolean equals(Object obj) {
        return this == obj;
    }

    @Override
    public abstract String toString();

}
