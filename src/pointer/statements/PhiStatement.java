package pointer.statements;

import java.util.List;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.LocalNode;
import pointer.graph.PointsToGraph;
import pointer.graph.PointsToGraphNode;
import pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.TypeReference;

/**
 * Points-to graph statement for a phi, representing choice at control flow
 * merges. v = phi(x1, x2, ...)
 */
public class PhiStatement implements PointsToStatement {

    /**
     * Value assigned into
     */
    private final LocalNode assignee;
    /**
     * Arguments into the phi
     */
    private final List<LocalNode> uses;
    /**
     * Type of the value assigned into
     */
    private final TypeReference type;
    /**
     * Code this statement occurs in
     */
    private final IR ir; 
    
    /**
     * Points-to graph statement for a phi, v = phi(xs[1], xs[2], ...)
     * 
     * @param v
     *            value assigned into
     * @param xs
     *            list of arguments to the phi, v is a choice amongst these
     */
    public PhiStatement(LocalNode v, List<LocalNode> xs, IR ir) {
        this.assignee = v;
        this.uses = xs;
        this.type = v.getExpectedType();
        this.ir = ir;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {

        PointsToGraphNode a = new ReferenceVariableReplica(context, assignee);
        boolean changed = false;

        // For every possible branch add edges into assignee
        for (LocalNode use : uses) {
            PointsToGraphNode n = new ReferenceVariableReplica(context, use);
            changed |= g.addEdges(a, g.getPointsToSetFiltered(n, assignee.getExpectedType()));
        }
        return changed;
    }

    @Override
    public IR getCode() {
        return ir;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((assignee == null) ? 0 : assignee.hashCode());
        result = prime * result + ((ir == null) ? 0 : ir.hashCode());
        result = prime * result + ((type == null) ? 0 : type.hashCode());
        result = prime * result + ((uses == null) ? 0 : uses.hashCode());
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        PhiStatement other = (PhiStatement) obj;
        if (assignee == null) {
            if (other.assignee != null)
                return false;
        } else if (!assignee.equals(other.assignee))
            return false;
        if (ir == null) {
            if (other.ir != null)
                return false;
        } else if (!ir.equals(other.ir))
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
    
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append(assignee + " = ");
        s.append("phi(");
        for (int i = 0; i < uses.size() - 1; i ++) {
            s.append(uses.get(i) + ", ");
        }
        s.append(uses.get(uses.size() - 1) + ")");
        return s.toString();
    }
}
