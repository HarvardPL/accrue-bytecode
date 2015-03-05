package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.StringInstanceKey;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class NewStringStatement extends StringStatement {

    // XXX: This should not be here. The HeapAbstractionFactory needs to know how to generate the bottom of StringInstanceKey.
    private static final int MAX_STRING_SET_SIZE = 5;

    private final StringVariable result;

    public NewStringStatement(StringVariable result, IMethod method) {
        super(method);
        this.result = result;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                  StatementRegistrar registrar, StmtAndContext originator) {
        // XXX: This is an affront to software engineering. I'm baking in my notion of StringInstanceKey when it ought to be provided by the analysis.
        //      once I teach IHAFs to create the bottoms of StringInstanceKey's I'll be fine.
        StringInstanceKey sik = StringInstanceKey.makeStringBottom(MAX_STRING_SET_SIZE);

        return g.stringVariableReplicaJoinAr(new StringVariableReplica(context, result), sik);
    }

    @Override
    public String toString() {
        return result + " = " + "newStringLike()";
    }

}
