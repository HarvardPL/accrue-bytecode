package analysis.pointer.analyses.recency;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteProgramPoint;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

public class RecencyHeapAbstractionFactory extends HeapAbstractionFactory {
    private final HeapAbstractionFactory haf;

    public RecencyHeapAbstractionFactory(HeapAbstractionFactory haf) {
        this.haf = haf;
    }
    @Override
    public InstanceKeyRecency record(AllocSiteNode allocationSite, Context context) {
        InstanceKey ik = haf.record(allocationSite, context);
        if (allocationSite.getAllocatedClass().isArrayClass()) {
            // we don't bother using the recency abstraction for  arrays
            return create(ik, false, false);
        }
        // A newly created object is always the most recent one...
        return create(ik, true, true);

    }

    static InstanceKeyRecency create(InstanceKey ik, boolean recent, boolean isTrackingMostRecent) {
        InstanceKeyRecency ikr = new InstanceKeyRecency(ik, recent, isTrackingMostRecent);
        return HeapAbstractionFactory.memoize(ikr, ik, "analysis.pointer.analyses.recency", Boolean.valueOf(recent));
    }

    @Override
    public Context merge(CallSiteProgramPoint callSite, InstanceKey receiver, Context callerContext) {
        InstanceKeyRecency receiverRecency = (InstanceKeyRecency) receiver;
        return haf.merge(callSite, receiverRecency == null ? null : receiverRecency.baseInstanceKey(), callerContext);
    }

    @Override
    public Context initialContext() {
        return haf.initialContext();
    }

    @Override
    public String toString() {
        return "RecencyHAF(" + haf.toString() + ")";
    }

}
