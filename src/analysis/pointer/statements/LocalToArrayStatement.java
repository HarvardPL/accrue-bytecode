package analysis.pointer.statements;

import java.util.Set;

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
            Set<InstanceKey> valHeapContexts = g.getPointsToSet(v);
            if (!valHeapContexts.isEmpty()) {
                Set<InstanceKey> arrayHCs = g.getPointsToSet(a);
                assert checkForNonEmpty(arrayHCs, a, "LOCAL:");

                for (InstanceKey arrHeapContext : arrayHCs) {
                    ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType);
                    GraphDelta d1 = g.addEdges(contents, valHeapContexts);
                    changed = changed.combine(d1);
                }
            }
        }
        else {
            // delta is non null. Let's do this smart!
            // First, we see if v has changed, in which case we propagate to everything that a points to.
            Set<InstanceKey> valHeapContexts = delta.getPointsToSet(v);
            if (!valHeapContexts.isEmpty()) {
                Set<InstanceKey> arrayHCs = g.getPointsToSet(a); // note that we don't use delta here, we want to propagate
                // the change to everything a points to.
                for (InstanceKey arrHeapContext : arrayHCs) {
                    ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType);
                    GraphDelta d1 = g.addEdges(contents, valHeapContexts);
                    changed = changed.combine(d1);
                }
            }
            // Second, we see if a has changed, in which case we want to propate everything that a points to.
            // Second, we check if a has changed what it points to. If it has, we need to make the new object fields
            // point to everything that the RHS can.
            Set<InstanceKey> arrayHCs = delta.getPointsToSet(a);
            for (InstanceKey arrHeapContext : arrayHCs) {
                ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType);
                GraphDelta d1 = g.addEdges(contents, g.getPointsToSet(v)); // no use of delta!
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
