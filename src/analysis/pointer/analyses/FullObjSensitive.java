package analysis.pointer.analyses;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

/**
 * A full object sensitive analysis, as described in "Pick Your Contexts Well: Understanding Object-Sensitivity" by
 * Smaragdakis, Bravenboer, and Lhotak, POPL 2011.
 * <p>
 * Not that this analysis is imprecise for static calls: the context for the static call is the context of the caller.
 * It is recommended that one combine this with another abstraction to recover precision for static calls (e.g.
 * StaticCallStiteSensitive)
 */
public class FullObjSensitive extends
        HeapAbstractionFactory<AllocationName<ContextStack<AllocSiteNode>>, AllocationNameContext> {

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

    @Override
    public AllocationNameContext merge(CallSiteLabel callSite,
                                             AllocationName<ContextStack<AllocSiteNode>> receiver,
                                             AllocationNameContext callerContext) {
        if (callSite.isStatic()) {
            // this is a static method call return the caller's context
            return callerContext;
        }

        return AllocationNameContext.create(receiver);
    }

    @Override
    public AllocationName<ContextStack<AllocSiteNode>> record(AllocSiteNode allocationSite,
                                                              AllocationNameContext context) {
        AllocationName<ContextStack<AllocSiteNode>> an = context.allocationName();
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
}
