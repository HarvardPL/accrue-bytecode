package analysis.pointer.analyses;

import analysis.pointer.analyses.ContextInsensitive.AlwaysEmpty;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.ContextKey;

public class ContextInsensitive extends HeapAbstractionFactory<AllocationName<AlwaysEmpty>, AlwaysEmpty> {

    public static final AlwaysEmpty EMPTY_CONTEXT = new AlwaysEmpty();

    @Override
    public AllocationName<AlwaysEmpty> record(AllocSiteNode allocationSite, AlwaysEmpty context) {
        return AllocationName.create(context, allocationSite);
    }

    @Override
    public AlwaysEmpty merge(CallSiteLabel callSite, AllocationName<AlwaysEmpty> receiver, AlwaysEmpty callerContext) {
        return initialContext();
    }

    @Override
    public AlwaysEmpty initialContext() {
        return EMPTY_CONTEXT;
    }

    @Override
    public String toString() {
        return "ContextInsensitive";
    }

    public static class AlwaysEmpty implements Context {

        AlwaysEmpty() {
            // intentionally empty
        }

        @Override
        public ContextItem get(ContextKey name) {
            // There are no context items
            return null;
        }

        @Override
        public String toString() {
            return "[]";
        }
    }
}
