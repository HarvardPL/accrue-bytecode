package analysis.pointer.analyses;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteProgramPoint;

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

    @SuppressWarnings("unchecked")
    @Override
    public AllocationName<ContextStack<CallSiteProgramPoint>> record(AllocSiteNode allocationSite, Context context) {
        return AllocationName.create((ContextStack<CallSiteProgramPoint>) context, allocationSite);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextStack<CallSiteProgramPoint> merge(CallSiteProgramPoint callSite, InstanceKey receiver, Context callerContext) {
        return ((ContextStack<CallSiteProgramPoint>) callerContext).push(callSite, sensitivity);
    }

    @Override
    public ContextStack<CallSiteProgramPoint> initialContext() {
        return ContextStack.<CallSiteProgramPoint> emptyStack();
    }
}
