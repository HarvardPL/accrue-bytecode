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

public class StaticFieldToLocalStringStatement extends StringStatement {

    private final StringLikeVariable v;
    private final StringLikeVariable f;
    private final String classname;

    public StaticFieldToLocalStringStatement(StringLikeVariable v, StringLikeVariable f, String classname, IMethod method) {
        super(method);
        this.v = v;
        this.f = f;
        this.classname = classname;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                       StmtAndContext originator, HeapAbstractionFactory haf,
                                       StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringLikeVariableReplica(context, this.v));
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        StringLikeVariableReplica fRVR = new StringLikeVariableReplica(context, this.f);

        g.recordStringStatementUseDependency(fRVR, originator);
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        StringLikeVariableReplica vRVR = new StringLikeVariableReplica(context, this.v);

        g.recordStringStatementDefineDependency(vRVR, originator);
    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        StringLikeVariableReplica fRVR = new StringLikeVariableReplica(context, this.f);

        return g.activateStringSolutionVariable(fRVR);
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringLikeVariableReplica vRVR = new StringLikeVariableReplica(context, this.v);
        StringLikeVariableReplica fRVR = new StringLikeVariableReplica(context, this.f);

        GraphDelta newDelta = new GraphDelta(g);

        newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(vRVR, fRVR));

        return newDelta;
    }

    @Override
    public String toString() {
        return v + " = " + classname + "." + f;
    }

}
