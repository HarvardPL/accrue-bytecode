package pointer.statements;

import pointer.LocalNode;
import pointer.ObjectField;
import pointer.PointsToGraph;
import pointer.PointsToGraphNode;
import pointer.ReferenceVariableReplica;
import pointer.analyses.HeapAbstractionFactory;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.TypeReference;

/**
 * Points to graph statement for an assignment from an array element, v = a[i]
 */
public class ArrayToLocalStatement implements PointsToStatement {

    private final LocalNode value;
    private final LocalNode array;
    private final TypeReference baseType;

    /**
     * Points to graph statement for an assignment from an array element, v =
     * a[i]
     * 
     * @param v
     *            Points to graph node for the assignee
     * @param a
     *            Points to graph node for the array being accessed
     * @param baseType
     *            base type of the array
     */
    public ArrayToLocalStatement(LocalNode v, LocalNode a, TypeReference baseType) {
        super();
        this.value = v;
        this.array = a;
        this.baseType = baseType;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, array);
        PointsToGraphNode v = new ReferenceVariableReplica(context, value);

        boolean changed = false;
        // TODO filter only arrays with assignable base types
        // Might have to subclass InstanceKey to keep more info about arrays
        for (InstanceKey arrHeapContext : g.getPointsToSet(a)) {
            ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType);
            changed |= g.addEdges(v, g.getPointsToSetFiltered(contents, v.getExpectedType()));
        }
        return changed;
    }

    @Override
    public TypeReference getExpectedType() {
        return value.getExpectedType();
    }

}
