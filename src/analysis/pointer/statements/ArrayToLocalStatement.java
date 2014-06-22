package analysis.pointer.statements;

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
import com.ibm.wala.types.TypeReference;

/**
 * Points-to graph statement for an assignment from an array element, v = a[i]
 */
public class ArrayToLocalStatement extends PointsToStatement {

    private final ReferenceVariable value;
    private final ReferenceVariable array;
    private final TypeReference baseType;

    /**
     * Points-to graph statement for an assignment from an array element, v = a[i]
     * 
     * @param v
     *            Points-to graph node for the assignee
     * @param a
     *            Points-to graph node for the array being accessed
     * @param baseType
     *            base type of the array
     * @param m
     */
    protected ArrayToLocalStatement(ReferenceVariable v, ReferenceVariable a, TypeReference baseType, IMethod m) {
        super(m);
        this.value = v;
        this.array = a;
        this.baseType = baseType;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                    StatementRegistrar registrar) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, array);
        PointsToGraphNode v = new ReferenceVariableReplica(context, value);
        TypeFilter filter = new TypeFilter(v.getExpectedType());

        GraphDelta changed = new GraphDelta();
        // TODO filter only arrays with assignable base types
        // Might have to subclass InstanceKey to keep more info about arrays

        
        if (delta == null) {
            // no delta, so let's do some simple processing.
            for (InstanceKey arrHeapContext : g.getPointsToSet(a)) {
                ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType);
                GraphDelta d1 = g.copyFilteredEdges(contents, filter, v);
                changed = changed.combine(d1);
            }
        }
        else {
            // we have a delta. Let's be smart about how we use it.
            // Statement is v = a[i]. First check if a points to anything new. If it does now point to some new abstract
            // object k, add everything that k[i] points to to v's set.
            for (InstanceKey arrHeapContext : delta.getPointsToSet(a)) {
                ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType);
                GraphDelta d1 = g.copyFilteredEdges(contents, filter, v);// don't use
                                                                                                       // delta here: we
                                                                                                       // want the
                                                                                                       // entire set!
                changed = changed.combine(d1);
            }

            // Note: we do not need to check if there are any k[i]'s that have changed, since that will be
            // taken care of automatically by copy dependencies.
        }
        return changed;
    }

    @Override
    public String toString() {
        return value + " = " + array + "." + PointsToGraph.ARRAY_CONTENTS;
    }
}
