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
import analysis.pointer.graph.strings.StringLikeVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringLikeVariable;

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
    private final StringLikeVariable result;
    private final List<StringLikeVariable> arguments;

    public static boolean isGetPropertyCall(SSAInvokeInstruction i) {
        return StringAndReflectiveUtil.isGetPropertyCall(i.getDeclaredTarget());
    }

    public GetPropertyStatement(CallSiteReference callSite, IMethod method, MethodReference declaredTarget,
                                StringLikeVariable result, List<StringLikeVariable> arguments) {
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
        return g.stringSolutionVariableReplicaIsActive(new StringLikeVariableReplica(context, this.result));
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        List<StringLikeVariableReplica> argumentsvrs = new ArrayList<>();
        for (StringLikeVariable argument : this.arguments) {
            argumentsvrs.add(new StringLikeVariableReplica(context, argument));
        }

        switch (this.arguments.size()) {
        case 1:
        case 2: {
            for (StringLikeVariableReplica argument : argumentsvrs) {
                g.recordStringStatementUseDependency(argument, originator);
            }
            break;
        }
        default:
            throw new RuntimeException("unreachable");
        }
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        StringLikeVariableReplica resultsvr = new StringLikeVariableReplica(context, this.result);

        switch (this.arguments.size()) {
        case 1:
        case 2: {
            g.recordStringStatementDefineDependency(resultsvr, originator);
            break;
        }
        default:
            throw new RuntimeException("unreachable");
        }
    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        List<StringLikeVariableReplica> argumentsvrs = new ArrayList<>();
        for (StringLikeVariable argument : this.arguments) {
            argumentsvrs.add(new StringLikeVariableReplica(context, argument));
        }

        GraphDelta changes = new GraphDelta(g);

        switch (this.arguments.size()) {
        case 1:
        case 2: {
            for (StringLikeVariableReplica argument : argumentsvrs) {
                changes.combine(g.activateStringSolutionVariable(argument));
            }
            break;
        }
        default:
            throw new RuntimeException("unreachable");
        }

        return changes;
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringLikeVariableReplica resultsvr = new StringLikeVariableReplica(context, this.result);
        List<StringLikeVariableReplica> argumentsvrs = new ArrayList<>();
        for (StringLikeVariable argument : this.arguments) {
            argumentsvrs.add(new StringLikeVariableReplica(context, argument));
        }
        GraphDelta newDelta = new GraphDelta(g);

        switch (this.arguments.size()) {
        case 1:
        case 2: {
            Logger.push(true);
            Logger.println("[GetPropertyStatement] _________________________");
            Logger.println("[GetPropertyStatement] me: " + this);
            Logger.println("[GetPropertyStatement] in method " + this.getMethod());
            Logger.println("[GetPropertyStatement] I'm being called: " + g.getAStringSetFor(argumentsvrs.get(0)));


            // XXX: Hack for exploration of results
            AString shat = ((ReflectiveHAF) haf).getAStringSet(Collections.singleton("XXXX"));

            Logger.println("[GetPropertyStatement] adding: " + shat);
            Logger.pop();

            newDelta.combine(g.stringSolutionVariableReplicaJoinAt(resultsvr, shat));
            break;
        }
        default:
            throw new RuntimeException("unreachable");
        }

        return newDelta;
    }

    @Override
    public String toString() {
        return "GetPropertyStatement [callSite=" + callSite + ", declaredTarget=" + declaredTarget + ", result="
                + result + ", arguments=" + arguments + "]";
    }

}
