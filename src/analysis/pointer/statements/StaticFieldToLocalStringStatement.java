package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class StaticFieldToLocalStringStatement extends StringStatement {

    private final StringVariable v;
    private final StringVariable f;
    private final String classname;

    public StaticFieldToLocalStringStatement(StringVariable v, StringVariable f, String classname, IMethod method) {
        super(method);
        this.v = v;
        this.f = f;
        this.classname = classname;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                       StmtAndContext originator, HeapAbstractionFactory haf,
                                       StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringVariableReplica(context, this.v));
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        StringVariableReplica fRVR = new StringVariableReplica(context, this.f);

        g.recordStringStatementUseDependency(fRVR, originator);
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        StringVariableReplica vRVR = new StringVariableReplica(context, this.v);

        g.recordStringStatementDefineDependency(vRVR, originator);
    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        StringVariableReplica fRVR = new StringVariableReplica(context, this.f);

        return g.activateStringSolutionVariable(fRVR);
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica vRVR = new StringVariableReplica(context, this.v);
        StringVariableReplica fRVR = new StringVariableReplica(context, this.f);

        GraphDelta newDelta = new GraphDelta(g);

        newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(vRVR, fRVR));

        System.err.println("[WARNING][StaticFieldToLocalStringStatement] This is actually unsound because "
                + "the static field may be mutated. This would be fixed by properly handling escaped strings");

        return newDelta;
    }

    @Override
    public String toString() {
        return v + " = " + classname + "." + f;
    }

}
