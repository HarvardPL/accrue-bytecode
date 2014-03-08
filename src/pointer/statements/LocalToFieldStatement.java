package pointer.statements;

import java.util.Set;

import pointer.LocalNode;
import pointer.ObjectField;
import pointer.PointsToGraph;
import pointer.PointsToGraphNode;
import pointer.ReferenceVariableReplica;
import pointer.analyses.HeapAbstractionFactory;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

/**
 * Points to statement for an assignment into a field, o.f = v
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
     * Statement for an assignment into a field
     * 
     * @param f
     *            field assigned to
     * @param o
     *            points to graph node for receiver of field access
     * @param v
     *            points to graph node for value assigned
     */
    public LocalToFieldStatement(FieldReference f, LocalNode o, LocalNode v) {
        this.field = f;
        this.receiver = o;
        this.assigned = v;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g) {
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
    public TypeReference getExpectedType() {
        return field.getFieldType();
    }

}
