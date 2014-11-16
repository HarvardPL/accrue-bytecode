package analysis.pointer.analyses;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
 * Analysis with contexts that are the cross product of those from two child analyses
 */
public class CrossProduct extends HeapAbstractionFactory {

    private final HeapAbstractionFactory haf1;
    private final HeapAbstractionFactory haf2;
    private final CrossProductContext initial;

    public CrossProduct(HeapAbstractionFactory haf1, HeapAbstractionFactory haf2) {
        this.haf1 = haf1;
        this.haf2 = haf2;
        this.initial = memoize(new CrossProductContext(haf1.initialContext(), haf2.initialContext()),
                                        haf1.initialContext(), haf2.initialContext());
    }

    @Override
    public CrossProductInstanceKey record(AllocSiteNode allocationSite, Context context) {
        InstanceKey ik1 = haf1.record(allocationSite, ((CrossProductContext) context).c1);
        InstanceKey ik2 = haf2.record(allocationSite, ((CrossProductContext) context).c2);
        return memoize(new CrossProductInstanceKey(ik1, ik2), ik1, ik2);
    }

    @Override
    public CrossProductContext merge(CallSiteLabel callSite, InstanceKey receiver, List<InstanceKey> arguments, Context callerContext) {
        InstanceKey r1 = null;
        InstanceKey r2 = null;
        if (receiver != null) {
            r1 = ((CrossProductInstanceKey) receiver).ik1;
            r2 = ((CrossProductInstanceKey) receiver).ik2;
        }
        List<InstanceKey> args1 = new ArrayList<>(arguments.size());
        List<InstanceKey> args2 = new ArrayList<>(arguments.size());
        for (InstanceKey arg : arguments) {
            args1.add(((CrossProductInstanceKey) arg).ik1);
            args2.add(((CrossProductInstanceKey) arg).ik2);
        }

        Context c1 = haf1.merge(callSite, r1, args1, ((CrossProductContext) callerContext).c1);
        Context c2 = haf2.merge(callSite, r2, args2, ((CrossProductContext) callerContext).c2);
        return memoize(new CrossProductContext(c1, c2), c1, c2);
    }

    @Override
    public CrossProductContext initialContext() {
        return initial;
    }

    @Override
    public String toString() {
        return haf1 + " x " + haf2;
    }

    /**
     * Instance key derived from two child instance keys
     */
    private class CrossProductInstanceKey implements InstanceKey {

        protected final InstanceKey ik1;
        protected final InstanceKey ik2;

        public CrossProductInstanceKey(InstanceKey ik1, InstanceKey ik2) {
            this.ik1 = ik1;
            this.ik2 = ik2;
        }

        @Override
        public IClass getConcreteType() {
            assert ik1.getConcreteType().equals(ik2.getConcreteType());
            return ik1.getConcreteType();
        }

        @Override
        public Iterator<Pair<CGNode, NewSiteReference>> getCreationSites(CallGraph CG) {
            return ik1.getCreationSites(CG);
        }

        @Override
        public String toString() {
            return ik1 + " x " + ik2;
        }
    }

    /**
     * Context derived from two child contexts
     */
    private class CrossProductContext implements Context {

        protected final Context c1;
        protected final Context c2;

        public CrossProductContext(Context c1, Context c2) {
            this.c1 = c1;
            this.c2 = c2;
        }

        @Override
        public ContextItem get(ContextKey name) {
            return c1.get(name);
        }

        @Override
        public String toString() {
            return "(" + c1 + ") x (" + c2 + ")";
        }
    }
}
