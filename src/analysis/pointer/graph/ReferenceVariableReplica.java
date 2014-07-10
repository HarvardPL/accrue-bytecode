package analysis.pointer.graph;

import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

/**
 * Reference Variable in a particular context
 */
public final class ReferenceVariableReplica implements PointsToGraphNode {

    private final Context context;
    private final ReferenceVariable l;
    private final int memoizedHashCode;

    public ReferenceVariableReplica(Context context, ReferenceVariable rv) {
        assert rv != null;
        assert context != null;
        l = rv;
        this.context = context;
        memoizedHashCode = computeHashCode();
    }

    /**
     * Memoize hash code
     * 
     * @return hash code
     */
    private int computeHashCode() {
        return context.hashCode() * 31 + l.hashCode();
    }

    @Override
    public TypeReference getExpectedType() {
        return l.getExpectedType();
    }

    @Override
    public int hashCode() {
        return memoizedHashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (!(obj instanceof ReferenceVariableReplica)) return false;
        ReferenceVariableReplica other = (ReferenceVariableReplica) obj;
        if (!context.equals(other.context)) return false;
        if (!l.equals(other.l)) return false;
        return true;
    }

    @Override
    public String toString() {
        return l + " in " + context;
    }
}
