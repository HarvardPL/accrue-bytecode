package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
    private ReferenceVariable receiver;
    /**
     * Value assigned into field
     */
    private ReferenceVariable localVar;

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
    public LocalToFieldStatement(ReferenceVariable o, FieldReference f,
            ReferenceVariable v, IMethod m) {
        super(m);
        field = f;
        receiver = o;
        localVar = v;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf,
            PointsToGraph g, GraphDelta delta, StatementRegistrar registrar) {
        PointsToGraphNode rec = new ReferenceVariableReplica(context, receiver);
        PointsToGraphNode local =
                new ReferenceVariableReplica(context, localVar);

        GraphDelta changed = new GraphDelta(g);

        if (delta == null) {
            // no delta, let's do some simple processing
            for (Iterator<InstanceKey> iter = g.pointsToIterator(rec); iter.hasNext();) {
                InstanceKey recHeapContext = iter.next();

                ObjectField f = new ObjectField(recHeapContext, field);
                // o.f can point to anything that local can.
                GraphDelta d1 = g.copyEdges(local, f);

                changed = changed.combine(d1);
            }
        }
        else {
            // We check if o has changed what it points to. If it has, we need to make the new object fields
            // point to everything that the RHS can.
            for (Iterator<InstanceKey> iter = delta.pointsToIterator(rec); iter.hasNext();) {
                InstanceKey recHeapContext = iter.next();
                ObjectField contents = new ObjectField(recHeapContext, field);
                GraphDelta d1 = g.copyEdges(local, contents);
                changed = changed.combine(d1);
            }
        }

        return changed;
    }

    @Override
    public String toString() {
        return receiver
                + "."
                + (field != null
                        ? field.getName() : PointsToGraph.ARRAY_CONTENTS)
                + " = " + localVar;
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0 || useNumber == 1;
        if (useNumber == 0) {
            receiver = newVariable;
            return;
        }
        localVar = newVariable;
    }

    @Override
    public List<ReferenceVariable> getUses() {
        List<ReferenceVariable> uses = new ArrayList<>(2);
        uses.add(receiver);
        uses.add(localVar);
        return uses;
    }

    @Override
    public ReferenceVariable getDef() {
        return null;
    }
}
