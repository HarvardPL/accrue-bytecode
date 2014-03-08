package pointer.statements;

import java.util.Set;

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
 * Points to graph statement for an assignment into an array, a[i] = v
 */
public class LocalToArrayStatement implements PointsToStatement {
    /**
     * Array assigned into
     */
    private final LocalNode array;
    /**
     * Value inserted into array
     */
    private final LocalNode value;
    /**
     * Type of array elements
     */
    private final TypeReference baseType;

    /**
     * Statement for an assignment into an array, a[i] = v. Note that we do not
     * reason about the individual array elements.
     * 
     * @param a
     *            points to graph node for array assigned into
     * @param value
     *            points to graph node for assigned value
     * @param baseType
     *            type of the array elements
     */
    public LocalToArrayStatement(LocalNode a, LocalNode v, TypeReference baseType) {
        this.array = a;
        this.value = v;
        this.baseType = baseType;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, array);
        PointsToGraphNode v = new ReferenceVariableReplica(context, value);

        Set<InstanceKey> valHeapContexts = g.getPointsToSetFiltered(v, baseType);

        boolean changed = false;
        for (InstanceKey arrHeapContext : g.getPointsToSet(a)) {
            ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType);
            changed |= g.addEdges(contents, valHeapContexts);
        }
        return changed;
    }

    @Override
    public TypeReference getExpectedType() {
        return array.expectedType();
    }

}
