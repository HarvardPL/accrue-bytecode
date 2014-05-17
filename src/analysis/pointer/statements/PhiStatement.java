package analysis.pointer.statements;

import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.statements.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.types.TypeReference;

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
     * Type of the value assigned into
     */
    private final TypeReference type;

    /**
     * Points-to graph statement for a phi, v = phi(xs[1], xs[2], ...)
     * 
     * @param v
     *            value assigned into
     * @param xs
     *            list of arguments to the phi, v is a choice amongst these
     */
    public PhiStatement(ReferenceVariable v, List<ReferenceVariable> xs, IR ir, SSAPhiInstruction i) {
        super(ir, i);
        assert !xs.isEmpty();
        this.assignee = v;
        this.uses = xs;
        this.type = v.getExpectedType();
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
                                                + PrettyPrinter.parseMethod(getCode().getMethod()));
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

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((assignee == null) ? 0 : assignee.hashCode());
        result = prime * result + ((type == null) ? 0 : type.hashCode());
        result = prime * result + ((uses == null) ? 0 : uses.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        PhiStatement other = (PhiStatement) obj;
        if (assignee == null) {
            if (other.assignee != null)
                return false;
        } else if (!assignee.equals(other.assignee))
            return false;
        if (type == null) {
            if (other.type != null)
                return false;
        } else if (!type.equals(other.type))
            return false;
        if (uses == null) {
            if (other.uses != null)
                return false;
        } else if (!uses.equals(other.uses))
            return false;
        return true;
    }
}
