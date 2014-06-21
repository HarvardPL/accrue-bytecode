package analysis.pointer.statements;

import java.util.Set;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
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
 * Points-to statement for an Access a field and assign the result to a local. l = o.f
 */
public class FieldToLocalStatment extends PointsToStatement {

    /**
     * Field being accessed
     */
    private final FieldReference declaredField;
    /**
     * receiver of field access
     */
    private final ReferenceVariable receiver;
    /**
     * local assigned into
     */
    private final ReferenceVariable assignee;

    /**
     * Points-to statement for a field access assigned to a local, l = o.f
     * 
     * @param l
     *            points-to graph node for local assigned into
     * @param o
     *            points-to graph node for receiver of field access
     * @param f
     *            field accessed
     * @param m
     *            method the statement was created for
     */
    protected FieldToLocalStatment(ReferenceVariable l, ReferenceVariable o, FieldReference f, IMethod m) {
        super(m);
        this.declaredField = f;
        this.receiver = o;
        this.assignee = l;
    }

    @Override
    public String toString() {
        return assignee + " = " + receiver + "." + declaredField.getName();
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                    StatementRegistrar registrar) {
        PointsToGraphNode left = new ReferenceVariableReplica(context, assignee);
        PointsToGraphNode rec = new ReferenceVariableReplica(context, receiver);

        GraphDelta changed = new GraphDelta();

        if (delta == null) {
            // let's do the normal processing
            Set<InstanceKey> s = g.getPointsToSetWithDelta(rec, delta);

            assert checkForNonEmpty(s, rec, "FIELD RECEIVER: " + this);

            for (InstanceKey recHeapContext : s) {
                ObjectField f = new ObjectField(recHeapContext, declaredField);

                Set<InstanceKey> fieldHCs = g.getPointsToSetFiltered(f, left.getExpectedType());
                assert checkForNonEmpty(fieldHCs, f,
                                                "FIELD filtered: " + PrettyPrinter.typeString(left.getExpectedType()));

                GraphDelta d1 = g.addEdges(left, fieldHCs);
                changed = changed.combine(d1);
            }
            return changed;
        }
        else {
            // we have a delta. Let's be smart about how we use it.
            // Statement is v = o.f. First check if o points to anything new. If it does now point to some new abstract
            // object k, add everything that k.f points to to v's set.
            for (InstanceKey recHeapContext : delta.getPointsToSet(rec)) {
                ObjectField f = new ObjectField(recHeapContext, declaredField.getName().toString(),
                                                declaredField.getFieldType());

                Set<InstanceKey> fieldHCs = g.getPointsToSetFiltered(f, left.getExpectedType()); // don't use delta
                                                                                                 // here: we want the
                                                                                                 // entire set!
                GraphDelta d1 = g.addEdges(left, fieldHCs);
                changed = changed.combine(d1);
            }

            // Now, let's check if there are any k.f's that have changed, and if so, whether o can point to k.
            Set<InstanceKey> allReceivers = g.getPointsToSetWithDelta(rec, delta); // don't use delta, we want
                                                                                   // everything that the
                                                                        // receiver can
                                                                        // point to!
            for (ObjectField f : delta.getObjectFields(declaredField)) {
                if (allReceivers.contains(f.receiver())) {
                    // the receiver points to the base of the object field (i.e., for object field k.f, it points to k)!

                    // we use delta here, since we only want to propagate what delta points to.
                    Set<InstanceKey> fieldHCs = g.getPointsToSetFilteredWithDelta(f, left.getExpectedType(), delta);
                    GraphDelta d1 = g.addEdges(left, fieldHCs);
                    changed = changed.combine(d1);
                }
            }
            return changed;
        }
    }
}
