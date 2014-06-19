package analysis.pointer.statements;

import java.util.Set;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
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
    private final ReferenceVariable receiver;
    /**
     * Value assigned into field
     */
    private final ReferenceVariable assigned;

    /**
     * Statement for an assignment into a field, o.f = v
     * 
     * @param o
     *            points-to graph node for receiver of field access
     * @param f
     *            field assigned to
     * @param v
     *            points-to graph node for value assigned
     * @param m
     *            method the points-to statement came from
     */
    public LocalToFieldStatement(ReferenceVariable o, FieldReference f, ReferenceVariable v, IMethod m) {
        super(m);
        this.field = f;
        this.receiver = o;
        this.assigned = v;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode rec = new ReferenceVariableReplica(context, receiver);
        PointsToGraphNode local = new ReferenceVariableReplica(context, assigned);
        Set<InstanceKey> localHeapContexts = g.getPointsToSetFiltered(local, field.getFieldType());
        assert checkForNonEmpty(localHeapContexts, local, "LOCAL: filetered on " + field.getFieldType());

        Set<InstanceKey> recHCs = g.getPointsToSet(rec);
        assert checkForNonEmpty(recHCs, rec, "FIELD RECEIVER");

        boolean changed = false;
        for (InstanceKey recHeapContext : recHCs) {
            ObjectField f = new ObjectField(recHeapContext, field.getName().toString(), field.getFieldType());
            changed |= g.addEdges(f, localHeapContexts);
        }

        return changed;
    }

    @Override
    public String toString() {
        return receiver + "." + (field != null ? field.getName() : PointsToGraph.ARRAY_CONTENTS) + " = " + assigned;
    }
}
