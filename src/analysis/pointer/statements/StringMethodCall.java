package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.List;

import util.Logger;
import analysis.AnalysisUtil;
import analysis.StringAndReflectiveUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.FlowSensitiveStringVariableFactory;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.MethodReference;

/**
 * Represents a method call on a StringBuilder.
 */
public class StringMethodCall extends StringStatement {
    private final CallSiteReference callSite;

    /**
     * Which method was invoked?
     */
    private final MethodEnum invokedMethod;
    private final StringVariable result;

    /**
     * StringVariable representing the value of the receiver object before the method call
     */
    private final StringVariable receiverUse;

    /**
     * StringVariable representing the value of the receiver object after the method call
     */
    private final StringVariable receiverDef;

    /**
     * Arguments to the method call.
     */
    private final List<StringVariable> arguments;
    private final FlowSensitiveStringVariableFactory stringVariableFactory;

    private enum MethodEnum {
        concatM, toStringM
    }

    private static MethodEnum imethodToMethodEnum(IMethod m) {
        if (m.equals(StringAndReflectiveUtil.stringBuilderAppendStringBuilderIMethod)
                || m.equals(StringAndReflectiveUtil.stringBuilderAppendStringIMethod)) {
            return MethodEnum.concatM;
        }
        else if (m.equals(StringAndReflectiveUtil.stringBuilderToStringIMethod)) {
            return MethodEnum.toStringM;
        }
        else {
            throw new RuntimeException("Unhandled string method: " + m);
        }
    }

    public StringMethodCall(CallSiteReference callSite, IMethod method, MethodReference declaredTarget,
                            StringVariable svresult, StringVariable svreceiverUse, StringVariable svreceiverDef,
                            List<StringVariable> svarguments, FlowSensitiveStringVariableFactory stringVariableFactory) {
        super(method);
        this.callSite = callSite;
        this.invokedMethod = imethodToMethodEnum(AnalysisUtil.getClassHierarchy().resolveMethod(declaredTarget));
        this.result = svresult;
        this.receiverUse = svreceiverUse;
        this.receiverDef = svreceiverDef;
        this.arguments = svarguments;
        this.stringVariableFactory = stringVariableFactory;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                       StmtAndContext originator, HeapAbstractionFactory haf,
                                       StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringVariableReplica(context, this.result));
    }

    @Override
    protected void registerDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                        PointsToIterable pti, StmtAndContext originator, StatementRegistrar registrar) {
        StringVariableReplica resultSVR = new StringVariableReplica(context, this.result);
        StringVariableReplica receiverUseSVR = new StringVariableReplica(context, this.receiverUse);
        StringVariableReplica receiverDefSVR = new StringVariableReplica(context, this.receiverDef);
        List<StringVariableReplica> argumentSVRs = new ArrayList<>(this.arguments.size());
        for (StringVariable argument : this.arguments) {
            argumentSVRs.add(new StringVariableReplica(context, argument));
        }

        switch (this.invokedMethod) {
        case concatM: {
            // the first argument is a copy of the "this" argument
            assert argumentSVRs.size() == 2 : argumentSVRs.size();

            g.recordStringStatementDefineDependency(receiverDefSVR, originator);

            g.recordStringStatementUseDependency(receiverUseSVR, originator);
            g.recordStringStatementUseDependency(argumentSVRs.get(1), originator);

            break;
        }
        case toStringM: {
            g.recordStringStatementDefineDependency(resultSVR, originator);
            g.recordStringStatementUseDependency(receiverUseSVR, originator);

            break;
        }
        default: {
            throw new RuntimeException("Unhandled case of invokedMethod: " + this.invokedMethod);
        }
        }
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica resultSVR = new StringVariableReplica(context, this.result);
        StringVariableReplica receiverUseSVR = new StringVariableReplica(context, this.receiverUse);
        StringVariableReplica receiverDefSVR = new StringVariableReplica(context, this.receiverDef);
        List<StringVariableReplica> argumentSVRs = new ArrayList<>(this.arguments.size());
        for (StringVariable argument : this.arguments) {
            argumentSVRs.add(new StringVariableReplica(context, argument));
        }

        Logger.println("[StringMethodCall." + this.invokedMethod + "] " + this);
        switch (this.invokedMethod) {
        case concatM: {
            // the first argument is a copy of the "this" argument
            assert argumentSVRs.size() == 2 : argumentSVRs.size();

            AString receiverAString = g.getAStringFor(receiverUseSVR);
            AString argumentAString = g.getAStringFor(argumentSVRs.get(1));

            AString concated = receiverAString.concat(argumentAString);

            return g.stringSolutionVariableReplicaJoinAt(receiverDefSVR, concated);
        }
        case toStringM: {
            return g.stringSolutionVariableReplicaUpperBounds(resultSVR, receiverUseSVR);
        }
        default: {
            throw new RuntimeException("Unhandled case of invokedMethod: " + this.invokedMethod);
        }

        }
    }

    @Override
    public String toString() {
        return "(" + this.result + ", " + this.receiverDef + ") <- " + this.receiverUse + "." + this.invokedMethod
                + "(" + this.arguments + ")";
    }

}
