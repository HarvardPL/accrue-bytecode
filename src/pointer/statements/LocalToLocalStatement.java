package pointer.statements;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.LocalNode;
import pointer.graph.PointsToGraph;
import pointer.graph.PointsToGraphNode;
import pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.TypeReference;

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
    public TypeReference getExpectedType() {
        return left.getExpectedType();
    }

    @Override
    public IR getCode() {
        return ir;
    }
}
