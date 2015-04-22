package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import util.Logger;
import analysis.StringAndReflectiveUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.MethodReference;

public class GetPropertyStatement extends StringStatement {

    private final CallSiteReference callSite;
    private final MethodReference declaredTarget;
    private final StringVariable result;
    private final List<StringVariable> arguments;

    public static boolean isGetPropertyCall(SSAInvokeInstruction i) {
        return StringAndReflectiveUtil.isGetPropertyCall(i.getDeclaredTarget());
    }

    public GetPropertyStatement(CallSiteReference callSite, IMethod method, MethodReference declaredTarget,
                                StringVariable result, List<StringVariable> arguments) {
        super(method);

        this.callSite = callSite;
        this.declaredTarget = declaredTarget;
        this.result = result;
        this.arguments = arguments;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica resultsvr = new StringVariableReplica(context, this.result);
        List<StringVariableReplica> argumentsvrs = new ArrayList<>();
        for (StringVariable argument : this.arguments) {
            argumentsvrs.add(new StringVariableReplica(context, argument));
        }
        GraphDelta newDelta = new GraphDelta(g);
        PointsToIterable pti = delta == null ? g : delta;

        switch (argumentsvrs.size()) {
        case 1:
        case 2: {
            g.recordStringStatementDefineDependency(resultsvr, originator);

            for (StringVariableReplica argument : argumentsvrs) {
                g.recordStringStatementUseDependency(argument, originator);
                newDelta.combine(g.recordStringVariableDependency(resultsvr, argument));
            }

            Logger.push(true);
            Logger.println("[GetPropertyStatement] _________________________");
            Logger.println("[GetPropertyStatement] me: " + this);
            Logger.println("[GetPropertyStatement] in method " + this.getMethod());
            Logger.println("[GetPropertyStatement] I'm being called: " + g.getAStringFor(argumentsvrs.get(0)));

            // XXX: Hack for exploration of results
            AString shat = ((ReflectiveHAF) haf).getAStringSet(Collections.singleton("XXXX"));

            Logger.println("[GetPropertyStatement] adding: " + shat);
            Logger.push(false);

            newDelta.combine(g.stringVariableReplicaJoinAt(resultsvr, shat));
            return newDelta;
        }
        //        case 2: {
        //            throw new RuntimeException("Unimplemented");
        //        }
        default:
            throw new RuntimeException("found getPropertyStatement without exactly one or two arguments: " + this);
        }

    }

    @Override
    public String toString() {
        return "GetPropertyStatement [callSite=" + callSite + ", declaredTarget=" + declaredTarget + ", result="
                + result + ", arguments=" + arguments + "]";
    }

}
