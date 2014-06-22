package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.TypeFilter;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to statement for a local assignment, left = right
 */
public class LocalToLocalStatement extends PointsToStatement {

    /**
     * assignee
     */
    private final ReferenceVariable left;
    /**
     * assigned
     */
    private final ReferenceVariable right;

    /**
     * Statement for a local assignment, left = right
     * 
     * @param left
     *            points-to graph node for assignee
     * @param right
     *            points-to graph node for the assigned value
     * @param m
     *            method the assignment is from
     */
    protected LocalToLocalStatement(ReferenceVariable left, ReferenceVariable right, IMethod m) {
        super(m);
        assert !left.isSingleton() : left + " is static";
        assert !right.isSingleton() : right + " is static";
        this.left = left;
        this.right = right;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                    StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, left);
        PointsToGraphNode r = new ReferenceVariableReplica(context, right);
        TypeFilter filter = new TypeFilter(left.getExpectedType());
        // don't need to use delta, as this just adds a subset edge
        return g.copyFilteredEdges(r, filter, l);
    }

    @Override
    public String toString() {
        return left + " = " + right;
    }
}
