package analysis.pointer.registrar;

import java.util.Collections;

import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.strings.StringVariable;
import analysis.pointer.statements.StringStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class StringLiteralStatement extends StringStatement {

    private final StringVariable v;
    private final String value;

    protected StringLiteralStatement(IMethod method, StringVariable v, String value) {
        super(method);
        this.v = v;
        this.value = value;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica vSVR = new StringVariableReplica(context, v);
        AString shat = ((ReflectiveHAF) haf).getAStringSet(Collections.singleton(value));
        System.err.println("[StringLiteralStatement] g.stringVariableReplicaJoinAt(" + vSVR + ", "
                + shat + ")");
        return g.stringVariableReplicaJoinAt(vSVR, shat);
    }

    @Override
    public String toString() {
        return "StringLiteralStatement [v=" + v + ", value=" + value + "]";
    }

}
