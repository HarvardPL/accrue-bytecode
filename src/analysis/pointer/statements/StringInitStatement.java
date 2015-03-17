package analysis.pointer.statements;

import java.util.Collections;

import util.Logger;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class StringInitStatement extends StringStatement {

    private final CallSiteReference callSite;
    private final StringVariable sv;

    public StringInitStatement(CallSiteReference callSite, IMethod method, StringVariable sv) {
        super(method);
        this.callSite = callSite;
        this.sv = sv;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica svr = new StringVariableReplica(context, this.sv);

        Logger.println("[StringInitStatement.process] ");
        GraphDelta newDelta = new GraphDelta(g);

        g.recordStringStatementDefineDependency(svr, originator);

        newDelta.combine(g.stringVariableReplicaJoinAt(svr,
                                                       ((ReflectiveHAF) haf).getAStringSet(Collections.singleton(""))));
        Logger.println("[StringInitStatement.process] " + svr + " <- " + g.getAStringFor(svr));

        return newDelta;
    }

    @Override
    public String toString() {
        // TODO Auto-generated method stub
        return null;
    }

}
