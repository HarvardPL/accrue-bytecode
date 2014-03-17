package pointer.statements;

import java.util.Set;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.LocalNode;
import pointer.graph.ObjectField;
import pointer.graph.PointsToGraph;
import pointer.graph.PointsToGraphNode;
import pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.TypeReference;

/**
 * Points-to graph statement for an assignment into an array, a[i] = v
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
     * Code this statement occurs in
     */
    private final IR ir;
    
    /**
     * Statement for an assignment into an array, a[i] = v. Note that we do not
     * reason about the individual array elements.
     * 
     * @param a
     *            points-to graph node for array assigned into
     * @param value
     *            points-to graph node for assigned value
     * @param baseType
     *            type of the array elements
     */
    public LocalToArrayStatement(LocalNode a, LocalNode v, TypeReference baseType, IR ir) {
        this.array = a;
        this.value = v;
        this.baseType = baseType;
        this.ir = ir;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
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
    public IR getCode() {
        return ir;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((array == null) ? 0 : array.hashCode());
        result = prime * result + ((baseType == null) ? 0 : baseType.hashCode());
        result = prime * result + ((ir == null) ? 0 : ir.hashCode());
        result = prime * result + ((value == null) ? 0 : value.hashCode());
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        LocalToArrayStatement other = (LocalToArrayStatement) obj;
        if (array == null) {
            if (other.array != null)
                return false;
        } else if (!array.equals(other.array))
            return false;
        if (baseType == null) {
            if (other.baseType != null)
                return false;
        } else if (!baseType.equals(other.baseType))
            return false;
        if (ir == null) {
            if (other.ir != null)
                return false;
        } else if (!ir.equals(other.ir))
            return false;
        if (value == null) {
            if (other.value != null)
                return false;
        } else if (!value.equals(other.value))
            return false;
        return true;
    }
    
    @Override
    public String toString() {
        return array + "." + PointsToGraph.ARRAY_CONTENTS + " = " + value;
    }
}
