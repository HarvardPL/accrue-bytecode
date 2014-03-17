package pointer.statements;

import java.util.HashSet;
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
 * Points-to statement for an Access a field and assign the result to a local. l = o.f
 */
public class FieldToLocalStatment implements PointsToStatement {

    /**
     * Field being accessed
     */
    private final FieldReference declaredField;
    /**
     * receiver of field access
     */
    private final LocalNode receiver;
    /**
     * local assigned into
     */
    private final LocalNode assignee;
    /**
     * Code this statement occurs in
     */
    private final IR ir;

    /**
     * Points-to statement for a field access assigned to a local, l = o.f
     * 
     * @param f
     *            field accessed
     * @param o
     *            points-to graph node for receiver of field access
     * @param l
     *            points-to graph node for local assigned into
     */
    public FieldToLocalStatment(FieldReference f, LocalNode o, LocalNode l, IR ir) {
        this.declaredField = f;
        this.receiver = o;
        this.assignee = l;
        this.ir = ir;
    }
    
    @Override
    public String toString() {
        return assignee + " = " + receiver + "." + declaredField.getName();
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode left = new ReferenceVariableReplica(context, assignee);
        PointsToGraphNode rec = new ReferenceVariableReplica(context, receiver);

        Set<InstanceKey> fields = new HashSet<>();
        for (InstanceKey recHeapContext : g.getPointsToSet(rec)) {
            ObjectField f = new ObjectField(recHeapContext, declaredField.getName().toString(), declaredField.getFieldType());
            for (InstanceKey fieldHeapContext : g.getPointsToSetFiltered(f, assignee.getExpectedType())) {
                fields.add(fieldHeapContext);
            }
        }
        return g.addEdges(left, fields);
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
        result = prime * result + ((declaredField == null) ? 0 : declaredField.hashCode());
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
        FieldToLocalStatment other = (FieldToLocalStatment) obj;
        if (assignee == null) {
            if (other.assignee != null)
                return false;
        } else if (!assignee.equals(other.assignee))
            return false;
        if (declaredField == null) {
            if (other.declaredField != null)
                return false;
        } else if (!declaredField.equals(other.declaredField))
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
    
    
}
