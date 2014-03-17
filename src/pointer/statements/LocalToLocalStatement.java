package pointer.statements;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.LocalNode;
import pointer.graph.PointsToGraph;
import pointer.graph.PointsToGraphNode;
import pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;

/**
 * Points-to statement for a local assignment, left = right
 */
public class LocalToLocalStatement implements PointsToStatement {

    /**
     * assignee
     */
    private final LocalNode left;
    /**
     * assigned
     */
    private final LocalNode right;
    /**
     * Code this statement occurs in
     */
    private final IR ir;
    
    
    /**
     * Statement for a local assignment, left = right
     * 
     * @param left
     *            points-to graph node for assignee
     * @param right
     *            points-to graph node for the assigned value
     */
    public LocalToLocalStatement(LocalNode left, LocalNode right, IR ir) {
        assert !left.isStatic() : left + " is static";
        assert !right.isStatic() : right + " is static";
        this.left = left;
        this.right = right;
        this.ir = ir;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, left);
        PointsToGraphNode r = new ReferenceVariableReplica(context, right);

        return g.addEdges(l, g.getPointsToSetFiltered(r, left.getExpectedType()));
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
        result = prime * result + ((ir == null) ? 0 : ir.hashCode());
        result = prime * result + ((left == null) ? 0 : left.hashCode());
        result = prime * result + ((right == null) ? 0 : right.hashCode());
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
        LocalToLocalStatement other = (LocalToLocalStatement) obj;
        if (ir == null) {
            if (other.ir != null)
                return false;
        } else if (!ir.equals(other.ir))
            return false;
        if (left == null) {
            if (other.left != null)
                return false;
        } else if (!left.equals(other.left))
            return false;
        if (right == null) {
            if (other.right != null)
                return false;
        } else if (!right.equals(other.right))
            return false;
        return true;
    }
    
    @Override
    public String toString() {
        return left + " = " + right;
    }
}
