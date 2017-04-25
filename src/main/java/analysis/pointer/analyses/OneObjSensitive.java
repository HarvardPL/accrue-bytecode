package analysis.pointer.analyses;

import java.util.Iterator;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.collections.Pair;

/**
 * A 1-object sensitive analysis, as described in "Hybrid Context-Sensitivity for Points-To Analysis" by Kastrinis and
 * Smaragdakis, PLDI 2013.
 * <p>
 * Not that this analysis is imprecise for static calls: the context for the static call is the context of the caller.
 * It is recommended that one combine this with another abstraction to recover precision for static calls (e.g.
 * StaticCallStiteSensitive)
 */
public class OneObjSensitive extends HeapAbstractionFactory {

    @Override
    public InstanceKeyContext merge(CallSiteLabel callSite,
            InstanceKey receiver, Context callerContext) {
        if (callSite.isStatic()) {
            // this is a static method call return the caller's context
            return (InstanceKeyContext) callerContext;
        }

        return InstanceKeyContext.create(receiver);
    }

    @Override
    public SingleSiteHeapContext record(AllocSiteNode allocationSite, Context context) {
        return SingleSiteHeapContext.create(allocationSite);
    }

    @Override
    public InstanceKeyContext initialContext() {
        return INITIAL;
    }

    private static final InstanceKeyContext INITIAL = InstanceKeyContext.create(null);

    @Override
    public String toString() {
        return "OneObjSens";
    }

    private static class InstanceKeyContext implements Context {
        private final InstanceKey ik;

        private InstanceKeyContext(InstanceKey ik) {
            this.ik = ik;
        }

        public static InstanceKeyContext create(InstanceKey ik) {
            return memoize(new InstanceKeyContext(ik), ik);
        }

        @Override
        public ContextItem get(ContextKey name) {
            return null;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public String toString() {
            return String.valueOf(ik);
        }
    }

    private static class SingleSiteHeapContext implements InstanceKey {
        private final AllocSiteNode allocationSite;

        private SingleSiteHeapContext(AllocSiteNode allocationSite) {
            this.allocationSite = allocationSite;
        }

        public static SingleSiteHeapContext create(AllocSiteNode allocationSite) {
            return memoize(new SingleSiteHeapContext(allocationSite), allocationSite);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public String toString() {
            return String.valueOf(allocationSite);
        }

        @Override
        public IClass getConcreteType() {
            return allocationSite.getAllocatedClass();
        }

        @Override
        public Iterator<Pair<CGNode, NewSiteReference>> getCreationSites(CallGraph CG) {
            return null;
        }
    }
}
