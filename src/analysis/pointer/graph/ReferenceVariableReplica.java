package analysis.pointer.graph;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;
import analysis.pointer.statements.ProgramPoint.PreProgramPoint;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

/**
 * Reference Variable in a particular context
 */
public final class ReferenceVariableReplica implements PointsToGraphNode {
    private final Context context;
    private final ReferenceVariable var;
    private final int memoizedHashCode;

    /**
     * Create a replica of a given reference variable. This represents a local variable or static field in a particular
     * context.
     *
     * @param context context the reference variable occurs in (if the reference variable is a singleton then this will
     *            be ignored in favor of the "initial" or "empty" context)
     * @param rv reference variable for the local variable or static field
     * @param haf heap abstraction factory (used to get the "initial context" for singleton reference variables)
     */
    public ReferenceVariableReplica(Context context, ReferenceVariable rv, HeapAbstractionFactory haf) {
        assert rv != null;
        assert context != null;
        var = rv;
        if (rv.isSingleton()) {
            this.context = haf.initialContext();
        }
        else {
            this.context = context;
        }
        memoizedHashCode = computeHashCode();
    }

    /**
     * Memoize hash code
     *
     * @return hash code
     */
    private int computeHashCode() {
        return context.hashCode() * 31 + var.hashCode();
    }

    @Override
    public TypeReference getExpectedType() {
        return var.getExpectedType();
    }

    @Override
    public boolean isFlowSensitive() {
        return var.isFlowSensitive();
    }

    public boolean hasLocalScope() {
        return var.hasLocalScope();
    }

    public InterProgramPointReplica localDef() {
        return var.localDef().post().getReplica(this.context);
    }

    public Set<InterProgramPointReplica> localUses() {
        return new IPPRSet(var.localUses(), this.context);
    }

    public boolean hasInstantaneousScope() {
        return var.hasInstantaneousScope();
    }

    @Override
    public int hashCode() {
        return memoizedHashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof ReferenceVariableReplica)) {
            return false;
        }
        ReferenceVariableReplica other = (ReferenceVariableReplica) obj;
        if (!context.equals(other.context)) {
            return false;
        }
        if (!var.equals(other.var)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return var + " in " + context;
    }

    public static final class IPPRSet extends AbstractSet<InterProgramPointReplica> {
        private final Set<ProgramPoint> ippSet;
        private final Context context;

        public IPPRSet(Set<ProgramPoint> ippSet, Context context) {
            this.ippSet = ippSet;
            this.context = context;
        }

        @Override
        public boolean contains(Object o) {
            if (o instanceof InterProgramPointReplica) {
                InterProgramPointReplica ippr = (InterProgramPointReplica) o;
                if (context.equals(ippr.getContext()) && ippr.getInterPP() instanceof PreProgramPoint) {
                    return ippSet.contains(ippr.getInterPP().getPP());
                }
            }
            return false;
        }
        @Override
        public Iterator<InterProgramPointReplica> iterator() {
            return new IPPRSetIterator(ippSet.iterator(), context);
        }

        @Override
        public int size() {
            return ippSet.size();
        }

    }

    public static class IPPRSetIterator implements Iterator<InterProgramPointReplica> {
        private final Iterator<ProgramPoint> ippIter;
        private final Context context;

        public IPPRSetIterator(Iterator<ProgramPoint> iterator, Context context) {
            this.ippIter = iterator;
            this.context = context;
        }

        @Override
        public boolean hasNext() {
            return ippIter.hasNext();
        }

        @Override
        public InterProgramPointReplica next() {
            return ippIter.next().pre().getReplica(this.context);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

}
