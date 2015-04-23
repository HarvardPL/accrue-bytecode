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
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica vRVR = new StringVariableReplica(context, this.v);
        StringVariableReplica fRVR = new StringVariableReplica(context, this.f);

        GraphDelta newDelta = new GraphDelta(g);

        g.recordStringStatementDefineDependency(vRVR, originator);

        g.recordStringStatementUseDependency(fRVR, originator);

        newDelta.combine(g.recordStringSolutionVariableDependency(vRVR, fRVR));

        newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(vRVR, fRVR));

        return newDelta;
    }

    @Override
    public String toString() {
        return v + " = " + classname + "." + f;
    }

}
