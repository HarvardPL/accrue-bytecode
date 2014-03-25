package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.LocalNode;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;

/**
 * Points-to statement for an assignment from a static field to a local variable, v = o.x
 */
public class StaticFieldToLocalStatement extends PointsToStatement {

    /**
     * assignee
     */
    private final LocalNode staticField;
    /**
     * assigned
     */
    private final LocalNode local;    
    
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
        super(ir);
        assert staticField.isStatic() : staticField + " is not static";
        assert !local.isStatic() : local + " is static";
        this.staticField = staticField;
        this.local = local;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, local);
        PointsToGraphNode r = new ReferenceVariableReplica(haf.initialContext(), staticField);
        
        return g.addEdges(l, g.getPointsToSetFiltered(r, local.getExpectedType()));
    }

    @Override
    public String toString() {
        return local + " = " + staticField;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((local == null) ? 0 : local.hashCode());
        result = prime * result + ((staticField == null) ? 0 : staticField.hashCode());
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
        StaticFieldToLocalStatement other = (StaticFieldToLocalStatement) obj;
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
}
