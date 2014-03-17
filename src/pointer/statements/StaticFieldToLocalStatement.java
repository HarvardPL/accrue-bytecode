package pointer.statements;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.LocalNode;
import pointer.graph.PointsToGraph;
import pointer.graph.PointsToGraphNode;
import pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;

/**
 * Points-to statement for an assignment from a static field to a local variable
 */
public class StaticFieldToLocalStatement implements PointsToStatement {

    /**
     * assignee
     */
    private final LocalNode staticField;
    /**
     * assigned
     */
    private final LocalNode local;
    /**
     * Code this statement occurs in
     */
    private final IR ir;
    
    
    /**
     * Statement for an assignment from a static field to a local, local =
     * ClassName.staticField
     * 
     * @param local
     *            points-to graph node for the assigned value
     * @param staticField
     *            points-to graph node for assignee
     */
    public StaticFieldToLocalStatement(LocalNode local, LocalNode staticField, IR ir) {
        assert staticField.isStatic() : staticField + " is not static";
        assert !local.isStatic() : local + " is static";
        this.staticField = staticField;
        this.local = local;
        this.ir = ir;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, local);
        PointsToGraphNode r = new ReferenceVariableReplica(haf.initialContext(), staticField);
        
        return g.addEdges(l, g.getPointsToSetFiltered(r, staticField.getExpectedType()));
    }

    @Override
    public IR getCode() {
        return ir;
    }
    
    @Override
    public String toString() {
        return staticField + " = " + local;
    }
}
