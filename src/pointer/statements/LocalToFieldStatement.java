package pointer.statements;

import java.util.Set;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.LocalNode;
import pointer.graph.ObjectField;
import pointer.graph.PointsToGraph;
import pointer.graph.PointsToGraphNode;
import pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.FieldReference;

/**
 * Points-to statement for an assignment into a field, o.f = v
 */
public class LocalToFieldStatement implements PointsToStatement {
    /**
     * Field assigned into
     */
    private final FieldReference field;
    /**
     * receiver for field access
     */
    private final LocalNode receiver;
    /**
     * Value assigned into field
     */
    private final LocalNode assigned;
    /**
     * Code this statement occurs in
     */
    private final IR ir;
    
    /**
     * Statement for an assignment into a field
     * 
     * @param f
     *            field assigned to
     * @param o
     *            points-to graph node for receiver of field access
     * @param v
     *            points-to graph node for value assigned
     */
    public LocalToFieldStatement(FieldReference f, LocalNode o, LocalNode v, IR ir) {
        this.field = f;
        this.receiver = o;
        this.assigned = v;
        this.ir = ir;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode rec = new ReferenceVariableReplica(context, receiver);
        PointsToGraphNode local = new ReferenceVariableReplica(context, assigned);

        Set<InstanceKey> localHeapContexts = g.getPointsToSetFiltered(local, field.getFieldType());

        boolean changed = false;
        for (InstanceKey recHeapContext : g.getPointsToSet(rec)) {
            ObjectField f = new ObjectField(recHeapContext, field.getName().toString(), field.getFieldType());
            changed |= g.addEdges(f, localHeapContexts);
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
        result = prime * result + ((assigned == null) ? 0 : assigned.hashCode());
        result = prime * result + ((field == null) ? 0 : field.hashCode());
        result = prime * result + ((ir == null) ? 0 : ir.hashCode());
        result = prime * result + ((receiver == null) ? 0 : receiver.hashCode());
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
        LocalToFieldStatement other = (LocalToFieldStatement) obj;
        if (assigned == null) {
            if (other.assigned != null)
                return false;
        } else if (!assigned.equals(other.assigned))
            return false;
        if (field == null) {
            if (other.field != null)
                return false;
        } else if (!field.equals(other.field))
            return false;
        if (ir == null) {
            if (other.ir != null)
                return false;
        } else if (!ir.equals(other.ir))
            return false;
        if (receiver == null) {
            if (other.receiver != null)
                return false;
        } else if (!receiver.equals(other.receiver))
            return false;
        return true;
    }
    
    @Override
    public String toString() {
        return receiver + "." + field.getName() + " = " + assigned;
    }
}
