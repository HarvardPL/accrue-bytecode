package analysis.pointer.statements;

import java.util.List;

import util.OrderedPair;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class StaticOrSpecialMethodCallString extends MethodCallString {

    private final List<OrderedPair<StringVariable, StringVariable>> stringArgumentAndParameters;
    private final StringVariable formalReturn;
    private final StringVariable actualReturn;

    public StaticOrSpecialMethodCallString(IMethod method,
                                           List<OrderedPair<StringVariable, StringVariable>> stringArgumentAndParameters,
                                           StringVariable returnedVariable, StringVariable returnToVariable) {
        super(method);
        this.stringArgumentAndParameters = stringArgumentAndParameters;
        this.formalReturn = returnedVariable;
        this.actualReturn = returnToVariable;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti, StmtAndContext originator, HeapAbstractionFactory haf, StatementRegistrar registrar) {
        boolean writersAreActive = g.stringSolutionVariableReplicaIsActive(new StringVariableReplica(context,
                                                                                                     this.formalReturn));
        for (OrderedPair<StringVariable, StringVariable> argumentAndParameter : stringArgumentAndParameters) {
            StringVariableReplica parametersvr = new StringVariableReplica(context, argumentAndParameter.snd());
            writersAreActive |= g.stringSolutionVariableReplicaIsActive(parametersvr);
        }

        return writersAreActive;
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        for (OrderedPair<StringVariable, StringVariable> argumentAndParameter : stringArgumentAndParameters) {
            StringVariableReplica argumentsvr = new StringVariableReplica(context, argumentAndParameter.fst());

            g.recordStringStatementUseDependency(argumentsvr, originator);
        }

        assert (actualReturn == null) == (formalReturn == null) : "Should both be either null or non-null";
        if (actualReturn != null) {
            StringVariableReplica formalReturnSVR = new StringVariableReplica(context, formalReturn);

            g.recordStringStatementUseDependency(formalReturnSVR, originator);
        }

    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        for (OrderedPair<StringVariable, StringVariable> argumentAndParameter : stringArgumentAndParameters) {
            StringVariableReplica parametersvr = new StringVariableReplica(context, argumentAndParameter.snd());

            g.recordStringStatementDefineDependency(parametersvr, originator);
        }

        assert (actualReturn == null) == (formalReturn == null) : "Should both be either null or non-null";
        if (actualReturn != null) {
            StringVariableReplica actualReturnSVR = new StringVariableReplica(context, actualReturn);

            g.recordStringStatementDefineDependency(actualReturnSVR, originator);
        }

    }

    @Override
    protected void activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        for (OrderedPair<StringVariable, StringVariable> argumentAndParameter : stringArgumentAndParameters) {
            StringVariableReplica argumentsvr = new StringVariableReplica(context, argumentAndParameter.fst());

            g.activateStringSolutionVariable(argumentsvr);
        }

        assert (actualReturn == null) == (formalReturn == null) : "Should both be either null or non-null";
        if (actualReturn != null) {
            StringVariableReplica formalReturnSVR = new StringVariableReplica(context, formalReturn);

            g.activateStringSolutionVariable(formalReturnSVR);
        }

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
        return "MethodCallStringEscape [stringArgumentAndParameters=" + stringArgumentAndParameters
                + ", returnedVariable=" + formalReturn + ", returnToVariable=" + actualReturn + "]";
    }

}
