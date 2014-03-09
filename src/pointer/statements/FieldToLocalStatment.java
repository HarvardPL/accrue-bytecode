package pointer.statements;

import java.util.HashSet;
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
 * Points to statement for an Access a field and assign the result to a local. l = o.f
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
     * Type of the assignment (type of the local)
     */
    private final TypeReference type;

    /**
     * Points to statement for a field access assigned to a local, l = o.f
     * 
     * @param f
     *            field accessed
     * @param o
     *            points to graph node for receiver of field access
     * @param l
     *            points to graph node for local assigned into
     */
    public FieldToLocalStatment(FieldReference f, LocalNode o, LocalNode l) {
        this.declaredField = f;
        this.receiver = o;
        this.assignee = l;
        this.type = l.getExpectedType();
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode left = new ReferenceVariableReplica(context, assignee);
        PointsToGraphNode rec = new ReferenceVariableReplica(context, receiver);

        Set<InstanceKey> fields = new HashSet<>();
        for (InstanceKey recHeapContext : g.getPointsToSet(rec)) {
            ObjectField f = new ObjectField(recHeapContext, declaredField.getName().toString(), getExpectedType());
            for (InstanceKey fieldHeapContext : g.getPointsToSetFiltered(f, getExpectedType())) {
                fields.add(fieldHeapContext);
            }
        }
        return g.addEdges(left, fields);
    }

    @Override
    public TypeReference getExpectedType() {
        return type;
    }

}
