package analysis.pointer.analyses;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

/**
 * Analysis that tracks call sites of (only) static methods
 */
public class StaticCallSiteSensitive extends
        HeapAbstractionFactory<AllocationName<ContextStack<CallSiteLabel>>, ContextStack<CallSiteLabel>> {

    /**
     * Default depth of call sites to keep track of
     */
    private static final int DEFAULT_SENSITIVITY = 2;
    private final int sensitivity;

    /**
     * Create a static call site sensitive heap abstraction factory with the default depth, i.e. up to the default
     * number of static call sites are tracked
     */
    public StaticCallSiteSensitive() {
        this(DEFAULT_SENSITIVITY);
    }

    /**
     * Create a static call site sensitive heap abstraction factory with the given depth, i.e. up to depth static call
     * sites are tracked
     *
     * @param sensitivity
     *            depth of the call site stack
     */
    public StaticCallSiteSensitive(int sensitivity) {
        this.sensitivity = sensitivity;
    }


    public int getSensitivity() {
        return sensitivity;
    }

    @Override
    public AllocationName<ContextStack<CallSiteLabel>> record(AllocSiteNode allocationSite,
                                                              ContextStack<CallSiteLabel> context) {
        return AllocationName.create(context, allocationSite);
    }

    @Override
    public ContextStack<CallSiteLabel> merge(CallSiteLabel callSite,
                                             AllocationName<ContextStack<CallSiteLabel>> receiver,
                                             ContextStack<CallSiteLabel> callerContext) {
        if (!callSite.isStatic()) {
            // only track call sites to static methods.
            callSite = null;
        }
        return callerContext.push(callSite, sensitivity);
    }

    @Override
    public ContextStack<CallSiteLabel> initialContext() {
        return ContextStack.<CallSiteLabel> emptyStack();
    }

    @Override
    public String toString() {
        return "scs(" + this.getSensitivity() + ")";
    }
}
