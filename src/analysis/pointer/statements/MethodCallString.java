package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.List;

import util.OrderedPair;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public abstract class MethodCallString extends StringStatement {

    public MethodCallString(IMethod method) {
        super(method);
    }

    /**
     *
     * @param actualReturn StringVariable for the actual return of the call site
     * @param formalReturn StringVariable from the method summary for the formal return
     * @param stringArgumentAndParameters Pairs of actual arguments and formal arguments.
     * @param context Context of this call.
     * @param haf
     * @param g
     * @param pti
     * @param registrar
     * @param originator
     * @return
     */
    protected static GraphDelta processCall(StringVariable actualReturn,
                                            StringVariable formalReturn,
                                            List<OrderedPair<StringVariable, StringVariable>> stringArgumentAndParameters,
                                            Context context, PointsToGraph g) {
        List<OrderedPair<StringVariableReplica, StringVariableReplica>> stringArgumentAndParameterSVRs = new ArrayList<>();

        for (OrderedPair<StringVariable, StringVariable> argumentAndParameter : stringArgumentAndParameters) {
            StringVariableReplica svr1 = new StringVariableReplica(context, argumentAndParameter.fst());
            StringVariableReplica svr2 = new StringVariableReplica(context, argumentAndParameter.snd());
            OrderedPair<StringVariableReplica, StringVariableReplica> svrpair = new OrderedPair<>(svr1, svr2);
            stringArgumentAndParameterSVRs.add(svrpair);
        }

        GraphDelta newDelta = new GraphDelta(g);

        for (OrderedPair<StringVariableReplica, StringVariableReplica> pair : stringArgumentAndParameterSVRs) {
            newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(pair.snd(), pair.fst()));
        }

        assert (actualReturn == null) == (formalReturn == null) : "Should both be either null or non-null";
        if (actualReturn != null) {
            StringVariableReplica actualReturnSVR = new StringVariableReplica(context, actualReturn);
            StringVariableReplica formalReturnSVR = new StringVariableReplica(context, formalReturn);
            newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(actualReturnSVR, formalReturnSVR));
        }

        return newDelta;
    }

}
