package analysis.pointer.statements;

import java.util.Iterator;

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
    protected FieldToLocalStatment(ReferenceVariable l, ReferenceVariable o,
            FieldReference f, IMethod m) {
        super(m);
        declaredField = f;
        receiver = o;
        assignee = l;
    }

    @Override
    public String toString() {
        return assignee + " = " + receiver + "." + declaredField.getName();
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf,
            PointsToGraph g, GraphDelta delta, StatementRegistrar registrar) {
        PointsToGraphNode left =
                new ReferenceVariableReplica(context, assignee);
        PointsToGraphNode rec = new ReferenceVariableReplica(context, receiver);

        GraphDelta changed = new GraphDelta(g);

        if (delta == null) {
            // let's do the normal processing
            for (Iterator<InstanceKey> iter = g.pointsToIterator(rec); iter.hasNext();) {
                InstanceKey recHeapContext = iter.next();
                ObjectField f = new ObjectField(recHeapContext, declaredField);

                //GraphDelta d1 = g.copyFilteredEdges(f, filter, left);
                GraphDelta d1 = g.copyEdges(f, left);
                changed = changed.combine(d1);
            }
        }
        else {
            // we have a delta. Let's be smart about how we use it.
            // Statement is v = o.f. First check if o points to anything new. If it does now point to some new abstract
            // object k, add everything that k.f points to to v's set.
            for (Iterator<InstanceKey> iter = delta.pointsToIterator(rec); iter.hasNext();) {
                InstanceKey recHeapContext = iter.next();
                ObjectField f =
                        new ObjectField(recHeapContext,
                                        declaredField.getName().toString(),
                                        declaredField.getFieldType());
                GraphDelta d1 = g.copyEdges(f, left);
                changed = changed.combine(d1);
            }

            // Note: we do not need to check if there are any k.f's that have changed, since that will be
            // taken care of automatically by subset relations.
        }
        return changed;
    }
}
