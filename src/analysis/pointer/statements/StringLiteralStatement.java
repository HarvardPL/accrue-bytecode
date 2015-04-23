package analysis.pointer.statements;

import java.util.Collections;

import analysis.pointer.analyses.AString;
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

public class StringLiteralStatement extends StringStatement {

    private final StringVariable v;
    private final String value;

    public StringLiteralStatement(IMethod method, StringVariable v, String value) {
        super(method);
        this.v = v;
        this.value = value;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica vSVR = new StringVariableReplica(context, v);

        g.recordStringStatementDefineDependency(vSVR, originator);

        AString shat = ((ReflectiveHAF) haf).getAStringSet(Collections.singleton(value));

        return g.stringSolutionVariableReplicaJoinAt(vSVR, shat);
    }

    @Override
    public String toString() {
        return "StringLiteralStatement [v=" + v + ", value=" + value + "]";
    }

}
