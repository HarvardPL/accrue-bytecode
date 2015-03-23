package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class LocalToStaticFieldStringStatement extends StringStatement {

    private final StringVariable f;
    private StringVariable v;

    public LocalToStaticFieldStringStatement(StringVariable f, StringVariable v, IMethod method) {
        super(method);
        this.f = f;
        this.v = v;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica vsvr = new StringVariableReplica(context, this.v);
        StringVariableReplica fsvr = new StringVariableReplica(context, this.f);

        GraphDelta newDelta = new GraphDelta(g);

        g.recordStringStatementDefineDependency(fsvr, originator);

        g.recordStringStatementUseDependency(vsvr, originator);

        newDelta.combine(g.recordStringVariableDependency(fsvr, vsvr));

        newDelta.combine(g.stringVariableReplicaUpperBounds(fsvr, vsvr));

        return newDelta;
    }

    @Override
    public String toString() {
        return "CLASSNAME." + this.f + " = " + this.v;
    }

}
