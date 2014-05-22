package analysis.pointer.statements;

import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAPhiInstruction;

/**
 * Points-to graph statement for a phi, representing choice at control flow
 * merges. v = phi(x1, x2, ...)
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
     */
    protected PhiStatement(ReferenceVariable v, List<ReferenceVariable> xs, IR ir, SSAPhiInstruction i) {
        super(ir, i);
        assert !xs.isEmpty();
        this.assignee = v;
        this.uses = xs;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {

        PointsToGraphNode a = new ReferenceVariableReplica(context, assignee);
        boolean changed = false;

        // For every possible branch add edges into assignee
        for (ReferenceVariable use : uses) {
            PointsToGraphNode n = new ReferenceVariableReplica(context, use);
            if (DEBUG && g.getPointsToSet(n).isEmpty()) {
                System.err.println("PHI ARG: " + n + " for "
                                                + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                                + PrettyPrinter.methodString(getCode().getMethod()));
            }
            changed |= g.addEdges(a, g.getPointsToSet(n));
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
