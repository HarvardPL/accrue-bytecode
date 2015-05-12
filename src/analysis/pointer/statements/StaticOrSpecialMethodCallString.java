package analysis.pointer.statements;

import java.util.List;

import util.OrderedPair;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.strings.StringLikeVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringLikeVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class StaticOrSpecialMethodCallString extends MethodCallString {

    private final List<OrderedPair<StringLikeVariable, StringLikeVariable>> stringArgumentAndParameters;
    private final StringLikeVariable formalReturn;
    private final StringLikeVariable actualReturn;
    private final IMethod targetMethod;

    public StaticOrSpecialMethodCallString(IMethod method,
                                           List<OrderedPair<StringLikeVariable, StringLikeVariable>> stringArgumentAndParameters,
                                           StringLikeVariable returnedVariable, StringLikeVariable returnToVariable,
                                           IMethod targetMethod) {
        super(method);
        this.stringArgumentAndParameters = stringArgumentAndParameters;
        this.formalReturn = returnedVariable;
        this.actualReturn = returnToVariable;
        this.targetMethod = targetMethod;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti, StmtAndContext originator, HeapAbstractionFactory haf, StatementRegistrar registrar) {
        boolean writersAreActive = false;
        if (this.formalReturn != null) {
            g.stringSolutionVariableReplicaIsActive(new StringLikeVariableReplica(context, this.formalReturn));
        }

        for (OrderedPair<StringLikeVariable, StringLikeVariable> argumentAndParameter : stringArgumentAndParameters) {
            StringLikeVariableReplica parametersvr = new StringLikeVariableReplica(context, argumentAndParameter.snd());
            writersAreActive |= g.stringSolutionVariableReplicaIsActive(parametersvr);
        }

        return writersAreActive;
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        for (OrderedPair<StringLikeVariable, StringLikeVariable> argumentAndParameter : stringArgumentAndParameters) {
            StringLikeVariableReplica argumentsvr = new StringLikeVariableReplica(context, argumentAndParameter.fst());

            g.recordStringStatementUseDependency(argumentsvr, originator);
        }

        assert (actualReturn == null) == (formalReturn == null) : "Should both be either null or non-null";
        if (actualReturn != null) {
            StringLikeVariableReplica formalReturnSVR = new StringLikeVariableReplica(context, formalReturn);

            g.recordStringStatementUseDependency(formalReturnSVR, originator);
        }

    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        for (OrderedPair<StringLikeVariable, StringLikeVariable> argumentAndParameter : stringArgumentAndParameters) {
            StringLikeVariableReplica parametersvr = new StringLikeVariableReplica(context, argumentAndParameter.snd());

            g.recordStringStatementDefineDependency(parametersvr, originator);
        }

        assert (actualReturn == null) == (formalReturn == null) : "Should both be either null or non-null";
        if (actualReturn != null) {
            StringLikeVariableReplica actualReturnSVR = new StringLikeVariableReplica(context, actualReturn);

            g.recordStringStatementDefineDependency(actualReturnSVR, originator);
        }

    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        GraphDelta changes = new GraphDelta(g);

        for (OrderedPair<StringLikeVariable, StringLikeVariable> argumentAndParameter : stringArgumentAndParameters) {
            StringLikeVariableReplica argumentsvr = new StringLikeVariableReplica(context, argumentAndParameter.fst());

            changes.combine(g.activateStringSolutionVariable(argumentsvr));
        }

        assert (actualReturn == null) == (formalReturn == null) : "Should both be either null or non-null";
        if (actualReturn != null) {
            StringLikeVariableReplica formalReturnSVR = new StringLikeVariableReplica(context, formalReturn);

            changes.combine(g.activateStringSolutionVariable(formalReturnSVR));
        }

        return changes;
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        return MethodCallString.processCall(this.actualReturn,
                                            this.formalReturn,
                                            this.stringArgumentAndParameters,
                                            context,
                                            g);
    }

    @Override
    public String toString() {
        return "StaticOrSpecialMethodCallString [stringArgumentAndParameters=" + stringArgumentAndParameters
                + ", formalReturn=" + formalReturn + ", actualReturn=" + actualReturn + ", targetMethod="
                + targetMethod + ", inside method " + this.getMethod() + "]";
    }

}

