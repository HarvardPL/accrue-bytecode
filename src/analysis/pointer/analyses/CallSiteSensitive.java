package analysis.pointer.analyses;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

/**
 * Analysis where the contexts are based on procedure call sites
 */
public class CallSiteSensitive extends
        HeapAbstractionFactory<AllocationName<ContextStack<CallSiteLabel>>, ContextStack<CallSiteLabel>> {

    /**
     * Default depth of call sites to keep track of
     */
    private static final int DEFAULT_SENSITIVITY = 2;
    /**
     * Depth of the call sites to keep track of
     */
    private final int sensitivity;

    /**
     * Create a call site sensitive heap abstraction factory with the default depth, i.e. up to the default number of
     * call sites are tracked
     */
    public CallSiteSensitive() {
        this(DEFAULT_SENSITIVITY);
    }

    /**
     * Create a call site sensitive heap abstraction factory with the given depth, i.e. up to depth call sites are
     * tracked
     *
     * @param sensitivity
     *            depth of the call site stack
     */
    public CallSiteSensitive(int sensitivity) {
        this.sensitivity = sensitivity;
    }

    @Override
    public String toString() {
        return "cs(" + this.sensitivity + ")";
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
        return callerContext.push(callSite, sensitivity);
    }

    @Override
    public ContextStack<CallSiteLabel> initialContext() {
        return ContextStack.<CallSiteLabel> emptyStack();
    }
}
