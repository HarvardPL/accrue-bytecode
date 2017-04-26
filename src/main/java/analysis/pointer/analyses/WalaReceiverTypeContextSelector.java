package analysis.pointer.analyses;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.analysis.reflection.JavaTypeContext;
import com.ibm.wala.analysis.typeInference.PointType;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.ReceiverTypeContextSelector;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXInstanceKeys;

/**
 * Heap abstraction factory that emulates WALA's built in {@link ReceiverTypeContextSelector} with the
 * {@link ZeroXInstanceKeys}.ALLOCATIONS flag set on the {@link InstanceKeyFactory}. In order to compare apple to apples
 * make sure all the flags are the same for both analyses. Our analysis with no flags set for singleton abstract object
 * should be
 * {@link com.ibm.wala.ipa.callgraph.impl.Util#makeVanillaZeroOneCFABuilder(com.ibm.wala.ipa.callgraph.AnalysisOptions, com.ibm.wala.ipa.callgraph.AnalysisCache, com.ibm.wala.ipa.cha.IClassHierarchy, com.ibm.wala.ipa.callgraph.AnalysisScope, com.ibm.wala.ipa.callgraph.ContextSelector, com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter)}
 * .
 * <p>
 * There may still be minor differences between the two analyses, but these should be close.
 */
public class WalaReceiverTypeContextSelector extends HeapAbstractionFactory {

    @Override
    public InstanceKey record(AllocSiteNode allocationSite, Context context) {
        return AllocationName.create(context, allocationSite);
    }

    @Override
    public Context merge(CallSiteLabel callSite, InstanceKey receiver, Context callerContext) {
        if (callSite.isStatic()) {
            return Everywhere.EVERYWHERE;
        }
        PointType P = new PointType(receiver.getConcreteType());
        return new JavaTypeContext(P);
    }

    @Override
    public Context initialContext() {
        return Everywhere.EVERYWHERE;
    }

    @Override
    public String toString() {
        return "WalaReceiverTypeContextSelector";
    }
}
