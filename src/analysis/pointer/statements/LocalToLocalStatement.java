package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.LocalNode;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;

/**
 * Points-to statement for a local assignment, left = right
 */
public class LocalToLocalStatement extends PointsToStatement {

    /**
     * assignee
     */
    private final LocalNode left;
    /**
     * assigned
     */
    private final LocalNode right;    
    
    /**
     * Statement for a local assignment, left = right
     * 
     * @param left
     *            points-to graph node for assignee
     * @param right
     *            points-to graph node for the assigned value
     */
    public LocalToLocalStatement(LocalNode left, LocalNode right, IR ir) {
        super(ir);
        assert !left.isStatic() : left + " is static";
        assert !right.isStatic() : right + " is static";
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, left);
        PointsToGraphNode r = new ReferenceVariableReplica(context, right);

        return g.addEdges(l, g.getPointsToSetFiltered(r, left.getExpectedType()));
    }
    
    @Override
    public String toString() {
        return left + " = " + right;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((left == null) ? 0 : left.hashCode());
        result = prime * result + ((right == null) ? 0 : right.hashCode());
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
        LocalToLocalStatement other = (LocalToLocalStatement) obj;
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
}
