package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class NewStringStatement extends StringStatement {

    private final StringVariable result;

    public NewStringStatement(StringVariable result, IMethod method) {
        super(method);
        this.result = result;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica svr = new StringVariableReplica(context, result);

        g.recordStringStatementDefineDependency(svr, originator);

        return g.stringSolutionVariableReplicaJoinAt(svr, ((ReflectiveHAF) haf).getAStringBottom());
    }

    @Override
    public String toString() {
        return result + " = " + "newStringLike()";
    }

}
