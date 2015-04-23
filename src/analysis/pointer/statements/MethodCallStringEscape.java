package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.List;

import util.OrderedPair;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public abstract class MethodCallStringEscape extends StringStatement {

    public MethodCallStringEscape(IMethod method) {
        super(method);
    }

    protected GraphDelta processCall(StringVariable returnToVariable, StringVariable returnedVariable,
                                     List<OrderedPair<StringVariable, StringVariable>> stringArgumentAndParameters,
                                     Context context,
                                     HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                     StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica returnToVariableSVR = new StringVariableReplica(context, returnToVariable);
        StringVariableReplica returnedVariableSVR = new StringVariableReplica(context, returnedVariable);
        List<OrderedPair<StringVariableReplica, StringVariableReplica>> stringArgumentAndParameterSVRs = new ArrayList<>();
        for (OrderedPair<StringVariable, StringVariable> argumentAndParameter : stringArgumentAndParameters) {
            StringVariableReplica svr1 = new StringVariableReplica(context, argumentAndParameter.fst());
            StringVariableReplica svr2 = new StringVariableReplica(context, argumentAndParameter.snd());
            OrderedPair<StringVariableReplica, StringVariableReplica> svrpair = new OrderedPair<>(svr1, svr2);
            stringArgumentAndParameterSVRs.add(svrpair);
        }

        GraphDelta newDelta = new GraphDelta(g);

        g.recordStringStatementDefineDependency(returnToVariableSVR, originator);
        g.recordStringStatementUseDependency(returnedVariableSVR, originator);
        newDelta.combine(g.recordStringSolutionVariableDependency(returnToVariableSVR, returnedVariableSVR));
        newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(returnToVariableSVR, returnedVariableSVR));

        for (OrderedPair<StringVariableReplica, StringVariableReplica> pair : stringArgumentAndParameterSVRs) {
            g.recordStringStatementUseDependency(pair.fst(), originator);
            g.recordStringStatementDefineDependency(pair.snd(), originator);
            newDelta.combine(g.recordStringSolutionVariableDependency(pair.fst(), pair.snd()));
            newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(pair.fst(), pair.snd()));
        }

        return newDelta;
    }

}
