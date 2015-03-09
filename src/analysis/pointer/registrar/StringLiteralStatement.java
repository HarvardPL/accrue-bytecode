package analysis.pointer.registrar;

import java.util.Collections;

import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.strings.StringVariable;
import analysis.pointer.statements.StringStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class StringLiteralStatement extends StringStatement {

    private static final int MAX_STRING_SET_SIZE = 5;
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
        // XXX: somehow factor out MAX_STIRNG_SET_SIZE
        StringVariableReplica vSVR = new StringVariableReplica(context, v);
        System.err.println("[StringLiteralStatement] g.stringVariableReplicaJoinAt(" + vSVR + ", "
                + AString.makeStringSet(MAX_STRING_SET_SIZE, Collections.singleton(value)) + ")");
        return g.stringVariableReplicaJoinAt(vSVR,
                                             AString.makeStringSet(MAX_STRING_SET_SIZE, Collections.singleton(value)));
    }

    @Override
    public String toString() {
        return "StringLiteralStatement [v=" + v + ", value=" + value + "]";
    }

}
