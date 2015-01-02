package analysis.pointer.analyses;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.classLoader.IClass;

/**
 * A full object sensitive analysis, as described in "Pick Your Contexts Well: Understanding Object-Sensitivity" by
 * Smaragdakis, Bravenboer, and Lhotak, POPL 2011.
 * <p>
 * Not that this analysis is imprecise for static calls: the context for the static call is the context of the caller.
 * It is recommended that one combine this with another abstraction to recover precision for static calls (e.g.
 * StaticCallStiteSensitive)
 */
public class FullObjSensitiveMagic extends
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
     * @param n depth of calling context stack
     */
    public FullObjSensitiveMagic(int n) {
        this.n = n;
    }

    /**
     * Create a full object sensitive abstraction factory with the default parameters
     */
    public FullObjSensitiveMagic() {
        this(DEFAULT_DEPTH);
    }

    /**
     * Is the given class is one of the tracked classes
     *
     * @param c class to check
     * @return true if <code>c</code> is one of the tracked classes
     */
    private static boolean isInteresting(IClass c) {
        return c.getReference().toString().contains("bouncycastle");
    }

    @Override
    public AllocationNameContext merge(CallSiteLabel callSite, AllocationName<ContextStack<AllocSiteNode>> receiver,
                                       AllocationNameContext callerContext) {
        if (!callSite.isStatic() && isInteresting(receiver.getConcreteType())) {
            AllocationName<ContextStack<AllocSiteNode>> rec = receiver;
            return AllocationNameContext.create(rec);
        }
        return initialContext();
    }

    @Override
    public AllocationName<ContextStack<AllocSiteNode>> record(AllocSiteNode allocationSite,
                                                              AllocationNameContext context) {
        if (isInteresting(allocationSite.getAllocatedClass()) || isInteresting(allocationSite.getAllocatingClass())) {
            AllocationNameContext c = context;
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
        ContextStack<AllocSiteNode> stack = ContextStack.emptyStack();
        return AllocationName.create(stack, allocationSite);
    }

    @Override
    public AllocationNameContext initialContext() {
        return AllocationNameContext.create(null);
    }

    @Override
    public String toString() {
        return n + "StringBuilderFullObjSens+1H";
    }
}
