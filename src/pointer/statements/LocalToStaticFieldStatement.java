package pointer.statements;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.LocalNode;
import pointer.graph.PointsToGraph;
import pointer.graph.PointsToGraphNode;
import pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;

/**
 * Points-to statement for an assignment from a local into a static field
 */
public class LocalToStaticFieldStatement implements PointsToStatement {

    /**
     * assignee
     */
    private final LocalNode local;
    /**
     * assigned
     */
    private final LocalNode staticField;
    /**
     * Code this statement occurs in
     */
    private final IR ir;
    
    
    /**
     * Statement for an assignment from a local into a static field,
     * ClassName.staticField = local
     * 
     * @param staticField
     *            points-to graph node for the assigned value
     * @param local
     *            points-to graph node for assignee
     */
    public LocalToStaticFieldStatement(LocalNode staticField, LocalNode local, IR ir) {
        assert !local.isStatic() : local + " is static";
        assert staticField.isStatic() : staticField + " is not static";
        this.local = local;
        this.staticField = staticField;
        this.ir = ir;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(haf.initialContext(), staticField);
        PointsToGraphNode r = new ReferenceVariableReplica(context, local);

        return g.addEdges(l, g.getPointsToSetFiltered(r, local.getExpectedType()));
    }

    @Override
    public IR getCode() {
        return ir;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((ir == null) ? 0 : ir.hashCode());
        result = prime * result + ((local == null) ? 0 : local.hashCode());
        result = prime * result + ((staticField == null) ? 0 : staticField.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        LocalToStaticFieldStatement other = (LocalToStaticFieldStatement) obj;
        if (ir == null) {
            if (other.ir != null)
                return false;
        } else if (!ir.equals(other.ir))
            return false;
        if (local == null) {
            if (other.local != null)
                return false;
        } else if (!local.equals(other.local))
            return false;
        if (staticField == null) {
            if (other.staticField != null)
                return false;
        } else if (!staticField.equals(other.staticField))
            return false;
        return true;
    }
    
    @Override
    public String toString() {
        return local + " = " + staticField;
    }
}
