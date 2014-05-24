package analysis.pointer.analyses;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * Analysis where the contexts are based on procedure call sites
 */
public class CallSiteSensitive extends HeapAbstractionFactory {

    /**
     * Default depth of call sites to keep track of
     */
    private static final int DEFAULT_SENSITIVITY = 2;
    /**
     * Depth of the call sites to keep track of
     */
    private final int sensitivity;

    /**
     * Create a call site sensitive heap abstraction factory with the default depth
     */
    public CallSiteSensitive() {
        this(DEFAULT_SENSITIVITY);
    }

    /**
     * Create a call site sensitive heap abstraction factory with the given depth
     * 
     * @param sensitivity
     *            depth of the call site stack
     */
    public CallSiteSensitive(int sensitivity) {
        this.sensitivity = sensitivity;
    }

    @Override
    public String toString() {
        return "Context(" + this.sensitivity + ")";
    }

    @SuppressWarnings("unchecked")
    @Override
    public AllocationName<ContextStack<CallSiteLabel>> record(AllocSiteNode allocationSite, Context context) {
        return AllocationName.create((ContextStack<CallSiteLabel>) context, allocationSite);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextStack<CallSiteLabel> merge(CallSiteLabel callSite, InstanceKey receiver, Context callerContext) {
        return ((ContextStack<CallSiteLabel>) callerContext).push(callSite, sensitivity);
    }

    @Override
    public ContextStack<CallSiteLabel> initialContext() {
        return ContextStack.<CallSiteLabel> emptyStack();
    }
}
