package analysis.pointer.analyses;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

/**
 * A Type Sensitive pointer analysis (nType+mH), as described in
 * "Pick Your Contexts Well: Understanding Object-Sensitivity" by Smaragdakis, Bravenboer, and Lhotak, POPL 2011.
 * <p>
 * Not that this analysis is imprecise for static calls (each call to a static method is analyzed in the same context),
 * it is recommended that one combine this with another abstraction to recover precision for static calls (e.g.
 * StaticCallStiteSensitive)
 */
public class TypeSensitive extends
        HeapAbstractionFactory<AllocationName<ContextStack<ClassWrapper>>, ContextStack<ClassWrapper>> {

    /**
     * Number of elements to record for Calling Contexts
     */
    private final int n;
    /**
     * Number of elements to record for Heap Contexts
     */
    private final int m;

    /**
     * default is 2Type+1H.
     */
    private static final int DEFAULT_TYPE_DEPTH = 2;
    /**
     * default is 2Type+1H.
     */
    private static final int DEFAULT_HEAP_DEPTH = 1;

    /**
     * Create an nType+mH analysis
     * <p>
     * There is no benefit to having n > m + 1
     *
     * @param n
     *            depth of calling context stack
     * @param m
     *            depth of heap context stack
     */
    public TypeSensitive(int n, int m) {
        this.n = n;
        this.m = m;
    }

    /**
     * Create a type sensitive abstraction factory with the default parameters
     */
    public TypeSensitive() {
        this(DEFAULT_TYPE_DEPTH, DEFAULT_HEAP_DEPTH);
    }

    @Override
    public ContextStack<ClassWrapper> merge(CallSiteLabel callSite,
                                            AllocationName<ContextStack<ClassWrapper>> receiver,
                                            ContextStack<ClassWrapper> callerContext) {
        if (callSite.isStatic()) {
            // this is a static method call. Return the caller's
            // context.
            return callerContext;
        }

        AllocationName<ContextStack<ClassWrapper>> rec = receiver;
        return rec.getContext().push(new ClassWrapper(rec.getAllocationSite().getAllocatingClass()), n);
    }

    @Override
    public AllocationName<ContextStack<ClassWrapper>> record(AllocSiteNode allocationSite,
                                                             ContextStack<ClassWrapper> context) {
        ContextStack<ClassWrapper> allocationContext = context.truncate(m);
        return AllocationName.create(allocationContext, allocationSite);
    }

    @Override
    public ContextStack<ClassWrapper> initialContext() {
        return ContextStack.<ClassWrapper> emptyStack();
    }

    @Override
    public String toString() {
        return n + "Type+" + m + "H";
    }
}
