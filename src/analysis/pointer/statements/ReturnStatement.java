package analysis.pointer.statements;

import java.util.Set;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * Points-to statement for a "return" instruction
 */
public class ReturnStatement extends PointsToStatement {

    /**
     * Node for return result
     */
    private final ReferenceVariable result;
    /**
     * Node summarizing all return values for the method
     */
    private final ReferenceVariable returnSummary;

    /**
     * Create a points-to statement for a return instruction
     * 
     * @param result
     *            Node for return result
     * @param returnSummary
     *            Node summarizing all return values for the method
     * @param m
     *            method the points-to statement came from
     */
    protected ReturnStatement(ReferenceVariable result, ReferenceVariable returnSummary, IMethod m) {
        super(m);
        this.result = result;
        this.returnSummary = returnSummary;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                    StatementRegistrar registrar) {
        ReferenceVariableReplica returnRes = new ReferenceVariableReplica(context, result);
        ReferenceVariableReplica summaryRes = new ReferenceVariableReplica(context, returnSummary);

        Set<InstanceKey> s = g.getPointsToSetWithDelta(returnRes, delta);
        assert checkForNonEmpty(s, returnRes, "RETURN");

        return g.addEdges(summaryRes, s);
    }

    @Override
    public String toString() {
        return ("return " + result);
    }
}
