package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class LocalToStaticFieldStringStatement extends StringStatement {

    private final StringVariable f;
    private final StringVariable vUse;
    private final StringVariable vDef;

    public LocalToStaticFieldStringStatement(StringVariable f, StringVariable vUse, StringVariable vDef, IMethod method) {
        super(method);
        this.f = f;
        this.vUse = vUse;
        this.vDef = vDef;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                       StmtAndContext originator, HeapAbstractionFactory haf,
                                       StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringVariableReplica(context, this.f));
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        StringVariableReplica vUsesvr = new StringVariableReplica(context, this.vUse);

        g.recordStringStatementUseDependency(vUsesvr, originator);
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        StringVariableReplica vDefsvr = new StringVariableReplica(context, this.vDef);
        StringVariableReplica fsvr = new StringVariableReplica(context, this.f);

        g.recordStringStatementDefineDependency(fsvr, originator);
        // XXX: hack to deal with escaping string
        g.recordStringStatementDefineDependency(vDefsvr, originator);
    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        StringVariableReplica vUsesvr = new StringVariableReplica(context, this.vUse);

        return g.activateStringSolutionVariable(vUsesvr);
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica vUsesvr = new StringVariableReplica(context, this.vUse);
        StringVariableReplica vDefsvr = new StringVariableReplica(context, this.vDef);
        StringVariableReplica fsvr = new StringVariableReplica(context, this.f);

        GraphDelta newDelta = new GraphDelta(g);

        newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(fsvr, vUsesvr));
        // XXX: hack to deal with escaping strings
        newDelta.combine(g.stringSolutionVariableReplicaJoinAt(vDefsvr, ((ReflectiveHAF) haf).getAStringTop()));

        return newDelta;
    }

    @Override
    public String toString() {
        return "CLASSNAME." + this.f + " = " + this.vUse;
    }

}
