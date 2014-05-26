package analysis.pointer.analyses;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * Analysis that tracks call sites of (only) static methods
 */
public class StaticCallSiteSensitive extends HeapAbstractionFactory {

    /**
     * Default depth of call sites to keep track of
     */
    private static final int DEFAULT_SENSITIVITY = 2;
    private final CallSiteSensitive delegate;

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
        this.delegate = new CallSiteSensitive(sensitivity);
    }

    @Override
    public AllocationName<ContextStack<CallSiteLabel>> record(AllocSiteNode allocationSite, Context context) {
        return delegate.record(allocationSite, context);
    }

    @Override
    public ContextStack<CallSiteLabel> merge(CallSiteLabel callSite, InstanceKey receiver, Context callerContext) {
        if (callSite.isStatic()) {
            return delegate.merge(callSite, receiver, callerContext);
        }
        // Non-static call push null
        // TODO could also not push anything here and keep the last static call site for added precision at a cost
        return delegate.merge(null, receiver, callerContext);
    }

    @Override
    public ContextStack<CallSiteLabel> initialContext() {
        // TODO Auto-generated method stub
        return delegate.initialContext();
    }

    @Override
    public String toString() {
        return "scs(" + delegate.getSensitivity() + ")";
    }
}
