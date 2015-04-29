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

/**
 * Represents a call "System.getProperty(args)" or "Security.getProperty()".
 *
 * We
 */
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

        if (!(this.arguments.size() == 1 || this.arguments.size() == 2)) {
            throw new RuntimeException("Cannot create getPropertyStatement without exactly one or two arguments. Given "
                    + this.arguments);

        }
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti, StmtAndContext originator, HeapAbstractionFactory haf, StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringVariableReplica(context, this.result));
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        List<StringVariableReplica> argumentsvrs = new ArrayList<>();
        for (StringVariable argument : this.arguments) {
            argumentsvrs.add(new StringVariableReplica(context, argument));
        }

        switch (this.arguments.size()) {
        case 1:
        case 2: {
            for (StringVariableReplica argument : argumentsvrs) {
                g.recordStringStatementUseDependency(argument, originator);
            }
        }
        default:
            throw new RuntimeException("unreachable");
        }
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        StringVariableReplica resultsvr = new StringVariableReplica(context, this.result);

        switch (this.arguments.size()) {
        case 1:
        case 2: {
            g.recordStringStatementDefineDependency(resultsvr, originator);
        }
        default:
            throw new RuntimeException("unreachable");
        }
    }

    @Override
    protected void activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        List<StringVariableReplica> argumentsvrs = new ArrayList<>();
        for (StringVariable argument : this.arguments) {
            argumentsvrs.add(new StringVariableReplica(context, argument));
        }

        switch (this.arguments.size()) {
        case 1:
        case 2: {
            for (StringVariableReplica argument : argumentsvrs) {
                g.activateStringSolutionVariable(argument);
            }
        }
        default:
            throw new RuntimeException("unreachable");
        }

    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica resultsvr = new StringVariableReplica(context, this.result);
        List<StringVariableReplica> argumentsvrs = new ArrayList<>();
        for (StringVariable argument : this.arguments) {
            argumentsvrs.add(new StringVariableReplica(context, argument));
        }
        GraphDelta newDelta = new GraphDelta(g);

        switch (this.arguments.size()) {
        case 1:
        case 2: {
            Logger.push(true);
            Logger.println("[GetPropertyStatement] _________________________");
            Logger.println("[GetPropertyStatement] me: " + this);
            Logger.println("[GetPropertyStatement] in method " + this.getMethod());
            Logger.println("[GetPropertyStatement] I'm being called: " + g.getAStringFor(argumentsvrs.get(0)));

            // XXX: Hack for exploration of results
            AString shat = ((ReflectiveHAF) haf).getAStringSet(Collections.singleton("XXXX"));

            Logger.println("[GetPropertyStatement] adding: " + shat);
            Logger.pop();

            newDelta.combine(g.stringSolutionVariableReplicaJoinAt(resultsvr, shat));
            return newDelta;
        }
        default:
            throw new RuntimeException("unreachable");
        }

    }

    @Override
    public String toString() {
        return "GetPropertyStatement [callSite=" + callSite + ", declaredTarget=" + declaredTarget + ", result="
                + result + ", arguments=" + arguments + "]";
    }

}
