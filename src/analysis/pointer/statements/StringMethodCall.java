package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.List;

import analysis.AnalysisUtil;
import analysis.StringAndReflectiveUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.strings.StringLikeLocationReplica;
import analysis.pointer.graph.strings.StringLikeVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringLikeVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.MethodReference;

/**
 * Represents a method call on a StringBuilder.
 */
public class StringMethodCall extends StringStatement {
    /**
     * Which method was invoked?
     */
    private final MethodEnum invokedMethod;
    private final StringLikeVariable result;

    /**
     * StringVariable representing the value of the receiver object before the method call
     */
    private final StringLikeVariable receiverUse;

    /**
     * StringVariable representing the value of the receiver object after the method call
     */
    private final StringLikeVariable receiverDef;

    /**
     * Arguments to the method call.
     */
    private final List<StringLikeVariable> arguments;
    private enum MethodEnum {
        sbAppendM, toStringM
    }

    private static MethodEnum imethodToMethodEnum(IMethod m) {
        if (m.equals(StringAndReflectiveUtil.stringBuilderAppendStringBuilderIMethod)
                || m.equals(StringAndReflectiveUtil.stringBuilderAppendStringIMethod)) {
            return MethodEnum.sbAppendM;
        }
        else if (m.equals(StringAndReflectiveUtil.stringToStringIMethod)
                || m.equals(StringAndReflectiveUtil.stringBuilderToStringIMethod)) {
            return MethodEnum.toStringM;
        }
        else {
            throw new RuntimeException("Unhandled string method: " + m);
        }
    }

    public StringMethodCall(IMethod method, MethodReference declaredTarget, StringLikeVariable svresult,
                            StringLikeVariable svreceiverUse, StringLikeVariable svreceiverDef, List<StringLikeVariable> svarguments) {
        super(method);
        this.invokedMethod = imethodToMethodEnum(AnalysisUtil.getClassHierarchy().resolveMethod(declaredTarget));
        this.result = svresult;
        this.receiverUse = svreceiverUse;
        this.receiverDef = svreceiverDef;
        this.arguments = svarguments;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                       StmtAndContext originator, HeapAbstractionFactory haf,
                                       StatementRegistrar registrar) {
        boolean writersAreActive = false;

        switch (this.invokedMethod) {
        case sbAppendM: {
            // the first argument is a copy of the "this" argument
            assert this.arguments.size() == 2 : this.arguments.size();

            //            writersAreActive |= g.stringSolutionVariableReplicaIsActive(new StringVariableReplica(context,
            //                                                                                                  this.receiverDef));

            writersAreActive |= g.stringSolutionVariableReplicaIsActive(new StringLikeVariableReplica(context, this.result));
            break;
        }
        case toStringM: {

            writersAreActive |= g.stringSolutionVariableReplicaIsActive(new StringLikeVariableReplica(context, this.result));

            break;
        }
        default: {
            throw new RuntimeException("Unhandled case of invokedMethod: " + this.invokedMethod);
        }
        }

        return writersAreActive;
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        StringLikeVariableReplica receiverUseSVR = new StringLikeVariableReplica(context, this.receiverUse);

        switch (this.invokedMethod) {
        case sbAppendM: {
            // the first argument is a copy of the "this" argument
            assert this.arguments.size() == 2 : this.arguments.size();

            List<StringLikeVariableReplica> argumentSVRs = new ArrayList<>(this.arguments.size());
            for (StringLikeVariable argument : this.arguments) {
                argumentSVRs.add(new StringLikeVariableReplica(context, argument));
            }

            g.recordStringStatementUseDependency(receiverUseSVR, originator);
            g.recordStringStatementUseDependency(argumentSVRs.get(1), originator);

            break;
        }
        case toStringM: {
            g.recordStringStatementUseDependency(receiverUseSVR, originator);

            break;
        }
        default: {
            throw new RuntimeException("Unhandled case of invokedMethod: " + this.invokedMethod);
        }
        }

    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {

        switch (this.invokedMethod) {
        case sbAppendM: {
            // the first argument is a copy of the "this" argument
            assert this.arguments.size() == 2 : this.arguments.size();

            //            StringVariableReplica receiverDefSVR = new StringVariableReplica(context, this.receiverDef);
            StringLikeVariableReplica resultSVR = new StringLikeVariableReplica(context, this.result);

            //            g.recordStringStatementDefineDependency(receiverDefSVR, originator);
            g.recordStringStatementDefineDependency(resultSVR, originator);

            break;
        }
        case toStringM: {
            StringLikeVariableReplica resultSVR = new StringLikeVariableReplica(context, this.result);

            g.recordStringStatementDefineDependency(resultSVR, originator);

            break;
        }
        default: {
            throw new RuntimeException("Unhandled case of invokedMethod: " + this.invokedMethod);
        }
        }

    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                       PointsToIterable pti, StmtAndContext originator, StatementRegistrar registrar) {
        StringLikeVariableReplica receiverUseSVR = new StringLikeVariableReplica(context, this.receiverUse);
        List<StringLikeVariableReplica> argumentSVRs = new ArrayList<>(this.arguments.size());
        for (StringLikeVariable argument : this.arguments) {
            argumentSVRs.add(new StringLikeVariableReplica(context, argument));
        }

        GraphDelta changes = new GraphDelta(g);

        switch (this.invokedMethod) {
        case sbAppendM: {
            // the first argument is a copy of the "this" argument
            assert this.arguments.size() == 2 : this.arguments.size();

            changes.combine(g.activateStringSolutionVariable(receiverUseSVR));
            changes.combine(g.activateStringSolutionVariable(argumentSVRs.get(1)));

            break;
        }
        case toStringM: {
            changes.combine(g.activateStringSolutionVariable(receiverUseSVR));

            break;
        }
        default: {
            throw new RuntimeException("Unhandled case of invokedMethod: " + this.invokedMethod);
        }
        }

        return changes;
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringLikeVariableReplica resultSVR = new StringLikeVariableReplica(context, this.result);
        StringLikeVariableReplica receiverUseSVR = new StringLikeVariableReplica(context, this.receiverUse);
        StringLikeVariableReplica receiverDefSVR = new StringLikeVariableReplica(context, this.receiverDef);
        List<StringLikeVariableReplica> argumentSVRs = new ArrayList<>(this.arguments.size());
        for (StringLikeVariable argument : this.arguments) {
            argumentSVRs.add(new StringLikeVariableReplica(context, argument));
        }

        switch (this.invokedMethod) {
        case sbAppendM: {
            // the first argument is a copy of the "this" argument
            assert argumentSVRs.size() == 2 : argumentSVRs.size();
            GraphDelta changed = new GraphDelta(g);

            for (StringLikeLocationReplica receiverLocation : receiverUseSVR.getStringLocations()) {
                for (StringLikeLocationReplica argumentLocation : argumentSVRs.get(1).getStringLocations()) {

                    AString receiverAString = g.getAStringFor(receiverLocation);
                    AString argumentAString = g.getAStringFor(argumentLocation);

                    AString concated = receiverAString.concat(argumentAString);

                    changed.combine(g.stringSolutionVariableReplicaJoinAt(resultSVR, concated));
                }
            }
            return changed;
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
