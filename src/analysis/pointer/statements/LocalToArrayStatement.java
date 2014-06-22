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
import com.ibm.wala.types.TypeReference;

/**
 * Points-to graph statement for an assignment into an array, a[i] = v
 */
public class LocalToArrayStatement extends PointsToStatement {
    /**
     * Array assigned into
     */
    private final ReferenceVariable array;
    /**
     * Value inserted into array
     */
    private final ReferenceVariable value;
    /**
     * Type of array elements
     */
    private final TypeReference baseType;

    /**
     * Statement for an assignment into an array, a[i] = v. Note that we do not reason about the individual array
     * elements.
     * 
     * @param a
     *            points-to graph node for array assigned into
     * @param value
     *            points-to graph node for assigned value
     * @param baseType
     *            type of the array elements
     * @param m
     *            method the points-to statement came from
     */
    public LocalToArrayStatement(ReferenceVariable a, ReferenceVariable v, TypeReference baseType, IMethod m) {
        super(m);
        this.array = a;
        this.value = v;
        this.baseType = baseType;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                    StatementRegistrar registrar) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, array);
        PointsToGraphNode v = new ReferenceVariableReplica(context, value);

        GraphDelta changed = new GraphDelta(g);

        if (delta == null) {
            // no changes, let's do the processing in a straightforward way.
            for (Iterator<InstanceKey> iter = g.pointsToIterator(a); iter.hasNext();) {
                InstanceKey arrHeapContext = iter.next();
                ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType);
                GraphDelta d1 = g.copyEdges(v, contents);
                changed = changed.combine(d1);
            }
        }
        else {
            // delta is non null. Let's do this smart!
            // We check if a has changed what it points to. If it has, we need to make the new object fields
            // point to everything that the RHS can.
            for (Iterator<InstanceKey> iter = delta.pointsToIterator(a); iter.hasNext();) {
                InstanceKey arrHeapContext = iter.next();
                ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType);
                GraphDelta d1 = g.copyEdges(v, contents);
                changed = changed.combine(d1);
            }
        }

        return changed;
    }

    @Override
    public String toString() {
        return array + "." + PointsToGraph.ARRAY_CONTENTS + " = " + value;
    }
}
