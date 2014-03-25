package analysis.pointer.statements;

import java.util.Set;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.LocalNode;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.FieldReference;

/**
 * Points-to statement for an assignment into a field, o.f = v
 */
public class LocalToFieldStatement extends PointsToStatement {
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
        super(ir);
        this.field = f;
        this.receiver = o;
        this.assigned = v;
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
    public String toString() {
        return receiver + "." + field.getName() + " = " + assigned;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((assigned == null) ? 0 : assigned.hashCode());
        result = prime * result + ((field == null) ? 0 : field.hashCode());
        result = prime * result + ((receiver == null) ? 0 : receiver.hashCode());
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
        if (receiver == null) {
            if (other.receiver != null)
                return false;
        } else if (!receiver.equals(other.receiver))
            return false;
        return true;
    }
}
