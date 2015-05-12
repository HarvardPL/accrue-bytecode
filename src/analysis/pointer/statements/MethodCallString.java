package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.List;

import util.OrderedPair;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.strings.StringLikeVariableReplica;
import analysis.pointer.registrar.strings.StringLikeVariable;

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
    protected static GraphDelta processCall(StringLikeVariable actualReturn,
                                            StringLikeVariable formalReturn,
                                            List<OrderedPair<StringLikeVariable, StringLikeVariable>> stringArgumentAndParameters,
                                            Context context, PointsToGraph g) {
        List<OrderedPair<StringLikeVariableReplica, StringLikeVariableReplica>> stringArgumentAndParameterSVRs = new ArrayList<>();

        for (OrderedPair<StringLikeVariable, StringLikeVariable> argumentAndParameter : stringArgumentAndParameters) {
            StringLikeVariableReplica svr1 = new StringLikeVariableReplica(context, argumentAndParameter.fst());
            StringLikeVariableReplica svr2 = new StringLikeVariableReplica(context, argumentAndParameter.snd());
            OrderedPair<StringLikeVariableReplica, StringLikeVariableReplica> svrpair = new OrderedPair<>(svr1, svr2);
            stringArgumentAndParameterSVRs.add(svrpair);
        }

        GraphDelta newDelta = new GraphDelta(g);

        for (OrderedPair<StringLikeVariableReplica, StringLikeVariableReplica> pair : stringArgumentAndParameterSVRs) {
            if (g.stringSolutionVariableReplicaIsActive(pair.snd())) {
                newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(pair.snd(), pair.fst()));
            }
        }

        assert (actualReturn == null) == (formalReturn == null) : "Should both be either null or non-null";
        if (actualReturn != null) {
            StringLikeVariableReplica actualReturnSVR = new StringLikeVariableReplica(context, actualReturn);
            StringLikeVariableReplica formalReturnSVR = new StringLikeVariableReplica(context, formalReturn);
            if (g.stringSolutionVariableReplicaIsActive(actualReturnSVR)) {
                newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(actualReturnSVR, formalReturnSVR));
            }
        }

        return newDelta;
    }

}
