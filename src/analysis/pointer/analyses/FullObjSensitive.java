package analysis.pointer.analyses;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * A full object sensitive analysis, as described in "Pick Your Contexts Well: Understanding Object-Sensitivity" by
 * Smaragdakis, Bravenboer, and Lhotak, POPL 2011.
 * <p>
 * Not that this analysis is imprecise for static calls: the context for the static call is the context of the caller.
 * It is recommended that one combine this with another abstraction to recover precision for static calls (e.g.
 * StaticCallStiteSensitive)
 */
public class FullObjSensitive extends HeapAbstractionFactory {

    /**
     * Object sensitivity parameter
     */
    private final int n;

    /**
     * default is 2 Object sensitive + 1H.
     */
    private static final int DEFAULT_DEPTH = 2;

    /**
     * Create an n-object-sensitive analysis
     *
     * @param n
     *            depth of calling context stack
     */
    public FullObjSensitive(int n) {
        this.n = n;
    }

    /**
     * Create a full object sensitive abstraction factory with the default parameters
     */
    public FullObjSensitive() {
        this(DEFAULT_DEPTH);
    }

    @SuppressWarnings("unchecked")
    @Override
    public AllocationNameContext merge(CallSiteLabel callSite,
            InstanceKey receiver, Context callerContext) {
        if (callSite.isStatic()) {
            // this is a static method call return the caller's context
            return (AllocationNameContext) callerContext;
        }

        AllocationName<ContextStack<AllocSiteNode>> rec =
                (AllocationName<ContextStack<AllocSiteNode>>) receiver;
        return AllocationNameContext.create(rec);
    }

    @Override
    public AllocationName<ContextStack<AllocSiteNode>> record(
            AllocSiteNode allocationSite, Context context) {
        AllocationNameContext c = (AllocationNameContext) context;
        AllocationName<ContextStack<AllocSiteNode>> an = c.allocationName();
        ContextStack<AllocSiteNode> stack;
        if (an == null) {
            stack = ContextStack.emptyStack();
        }
        else {
            stack = an.getContext().push(an.getAllocationSite(), n);
        }
        return AllocationName.create(stack, allocationSite);
    }

    @Override
    public AllocationNameContext initialContext() {
        return AllocationNameContext.create(null);
    }

    @Override
    public String toString() {
        return n + "FullObjSens+1H";
    }

    public static class AllocationNameContext implements Context {
        private final AllocationName<ContextStack<AllocSiteNode>> an;

        AllocationNameContext(AllocationName<ContextStack<AllocSiteNode>> an) {
            this.an = an;
        }

        public static AllocationNameContext create(
                AllocationName<ContextStack<AllocSiteNode>> an) {
            // XXX ANDREW: maybe make this memoize. Steve: Yes, in the meantime ensure we have equality defined.

            return new AllocationNameContext(an);
        }

        @Override
        public ContextItem get(ContextKey name) {
            return null;
        }

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
}
