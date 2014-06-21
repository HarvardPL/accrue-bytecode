package analysis.pointer.statements;

import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to graph statement for a phi, representing choice at control flow merges. v = phi(x1, x2, ...)
 */
public class PhiStatement extends PointsToStatement {

    /**
     * Value assigned into
     */
    private final ReferenceVariable assignee;
    /**
     * Arguments into the phi
     */
    private final List<ReferenceVariable> uses;

    /**
     * Points-to graph statement for a phi, v = phi(xs[1], xs[2], ...)
     * 
     * @param v
     *            value assigned into
     * @param xs
     *            list of arguments to the phi, v is a choice amongst these
     * @param m
     *            method containing the phi instruction
     */
    protected PhiStatement(ReferenceVariable v, List<ReferenceVariable> xs, IMethod m) {
        super(m);
        assert !xs.isEmpty();
        this.assignee = v;
        this.uses = xs;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                    StatementRegistrar registrar) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, assignee);

        GraphDelta changed = new GraphDelta();
        // For every possible branch add edges into assignee
        for (ReferenceVariable use : uses) {
            PointsToGraphNode n = new ReferenceVariableReplica(context, use);

            GraphDelta d1 = g.copyEdgesWithDelta(n, a, delta);

            changed = changed.combine(d1);
        }
        return changed;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append(assignee + " = ");
        s.append("phi(");
        for (int i = 0; i < uses.size() - 1; i++) {
            s.append(uses.get(i) + ", ");
        }
        s.append(uses.get(uses.size() - 1) + ")");
        return s.toString();
    }
}
