package analysis.pointer.analyses;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.ContextKey;

/**
 * Wrapper around a heap context to create a calling context
 */
class AllocationNameContext implements Context {
    /**
     * Heap context this is a wrapper around
     */
    private final AllocationName<ContextStack<AllocSiteNode>> an;

    /**
     * Create a calling context wrapper around the given heap context
     *
     * @param an Heap context this is a wrapper around
     */
    private AllocationNameContext(AllocationName<ContextStack<AllocSiteNode>> an) {
        this.an = an;
    }

    /**
     * Create a new context wrapping the given heap context
     *
     * @param an Heap context this is a wrapper around
     * @return new context
     */
    public static AllocationNameContext create(AllocationName<ContextStack<AllocSiteNode>> an) {
        // XXX ANDREW: maybe make this memoize. Steve: Yes, in the meantime ensure we have equality defined.

        return new AllocationNameContext(an);
    }

    @Override
    public ContextItem get(ContextKey name) {
        return null;
    }

    /**
     * Get the allocation name (heap context) this context wraps
     *
     * @return heap context
     */
    public AllocationName<ContextStack<AllocSiteNode>> allocationName() {
        return an;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (an == null ? 0 : an.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof AllocationNameContext)) {
            return false;
        }
        AllocationNameContext other = (AllocationNameContext) obj;
        if (an == null) {
            if (other.an != null) {
                return false;
            }
        }
        else if (!an.equals(other.an)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return String.valueOf(an);
    }
}
