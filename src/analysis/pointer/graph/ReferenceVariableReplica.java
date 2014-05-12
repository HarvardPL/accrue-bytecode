package analysis.pointer.graph;

import util.print.PrettyPrinter;
import analysis.pointer.statements.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

/**
 * Reference Variable in a particular context
 */
public class ReferenceVariableReplica implements PointsToGraphNode {

    private final Context context;
    private final ReferenceVariable l;

    public ReferenceVariableReplica(Context context, ReferenceVariable rv) {
        assert (rv != null);
        assert (context != null);
        this.l = rv;
        this.context = context;
    }

    @Override
    public TypeReference getExpectedType() {
        return l.getExpectedType();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((context == null) ? 0 : context.hashCode());
        result = prime * result + ((l == null) ? 0 : l.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ReferenceVariableReplica other = (ReferenceVariableReplica) obj;
        if (context == null) {
            if (other.context != null)
                return false;
        } else if (!context.equals(other.context))
            return false;
        if (l == null) {
            if (other.l != null)
                return false;
        } else if (!l.equals(other.l))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return l + (l.getCode() != null ? " in " + PrettyPrinter.parseMethod(l.getCode().getMethod()) : "") + " Context: " + context;
    }
}
