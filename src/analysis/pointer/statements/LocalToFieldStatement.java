package analysis.pointer.statements;

import java.util.Set;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.TypeFilter;
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

    private final TypeFilter filter;

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
        this.filter = new TypeFilter(f.getFieldType());
        this.receiver = o;
        this.assigned = v;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                    StatementRegistrar registrar) {
        PointsToGraphNode rec = new ReferenceVariableReplica(context, receiver);
        PointsToGraphNode local = new ReferenceVariableReplica(context, assigned);

        GraphDelta changed = new GraphDelta();

        if (delta == null) {
            // no delta, let's do some simple processing
            Set<InstanceKey> recHCs = g.getPointsToSet(rec);
            assert checkForNonEmpty(recHCs, rec, "FIELD RECEIVER");

            for (InstanceKey recHeapContext : recHCs) {
                ObjectField f = new ObjectField(recHeapContext, field);
                // o.f can point to anything that local can.
                GraphDelta d1 = g.copyFilteredEdges(local, filter, f);

                changed = changed.combine(d1);
            }
        }
        else {
            // We have a delta, so let's be smart about the processing.
            // First, for statement o.f = v, if v has changed what it points to, we need to add that to all the points
            // to sets on the left hand side.
            if (!delta.getPointsToSet(local).isEmpty()) {
                Set<InstanceKey> recHCs = g.getPointsToSet(rec); // note that we don't use delta here, we want to propagate
                // the change to everything o points to.
                for (InstanceKey recHeapContext : recHCs) {
                    ObjectField f = new ObjectField(recHeapContext, field);
                    GraphDelta d1 = g.copyFilteredEdgesWithDelta(local, filter, f, delta);
                    changed = changed.combine(d1);

                }
            }

            // Second, we check if o has changed what it points to. If it has, we need to make the new object fields
            // point to everything that the RHS can.
            Set<InstanceKey> recHCs = delta.getPointsToSet(rec);
            for (InstanceKey recHeapContext : recHCs) {
                ObjectField contents = new ObjectField(recHeapContext, field);
                GraphDelta d1 = g.copyEdges(local, contents); // no use of delta!
                changed = changed.combine(d1);

            }
        }

        return changed;
    }

    @Override
    public String toString() {
        return receiver + "." + (field != null ? field.getName() : PointsToGraph.ARRAY_CONTENTS) + " = " + assigned;
    }
}
