package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.strings.StringLikeVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringLikeVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class LocalToStaticFieldStringStatement extends StringStatement {

    private final StringLikeVariable f;
    private final StringLikeVariable v;

    public LocalToStaticFieldStringStatement(StringLikeVariable f, StringLikeVariable v, IMethod method) {
        super(method);
        this.f = f;
        this.v = v;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                       StmtAndContext originator, HeapAbstractionFactory haf,
                                       StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringLikeVariableReplica(context, this.f));
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        StringLikeVariableReplica svr = new StringLikeVariableReplica(context, this.v);

        g.recordStringStatementUseDependency(svr, originator);
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        StringLikeVariableReplica fsvr = new StringLikeVariableReplica(context, this.f);

        g.recordStringStatementDefineDependency(fsvr, originator);
    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                       PointsToIterable pti, StmtAndContext originator, StatementRegistrar registrar) {
        StringLikeVariableReplica svr = new StringLikeVariableReplica(context, this.v);

        return g.activateStringSolutionVariable(svr);
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringLikeVariableReplica svr = new StringLikeVariableReplica(context, this.v);
        StringLikeVariableReplica fsvr = new StringLikeVariableReplica(context, this.f);

        GraphDelta newDelta = new GraphDelta(g);

        newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(fsvr, svr));

        return newDelta;
    }

    @Override
    public String toString() {
        return "CLASSNAME." + this.f + " = " + this.v;
    }

}
