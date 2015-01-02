package analysis.pointer.analyses;

import java.util.Iterator;

import analysis.pointer.analyses.CrossProduct.CrossProductContext;
import analysis.pointer.analyses.CrossProduct.CrossProductInstanceKey;
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
public class CrossProduct<C1 extends Context, C2 extends Context, IK1 extends InstanceKey, IK2 extends InstanceKey>
        extends HeapAbstractionFactory<CrossProductInstanceKey<IK1, IK2>, CrossProductContext<C1, C2>> {

    private final HeapAbstractionFactory<IK1, C1> haf1;
    private final HeapAbstractionFactory<IK2, C2> haf2;
    private final CrossProductContext<C1, C2> initial;

    private CrossProduct(HeapAbstractionFactory<IK1, C1> haf1, HeapAbstractionFactory<IK2, C2> haf2) {
        this.haf1 = haf1;
        this.haf2 = haf2;
        this.initial = memoize(new CrossProductContext<>(haf1.initialContext(), haf2.initialContext()),
                               haf1.initialContext(),
                               haf2.initialContext());
    }

    /**
     * Create a new cross product heap abstraction factory from two regular heap abstraction factories
     * 
     * @param haf1 first heap abstraction factory
     * @param haf2 second heap abstraction factory
     * @return new cross product heap abstraction factory
     */
    public static <C1 extends Context, C2 extends Context, IK1 extends InstanceKey, IK2 extends InstanceKey> CrossProduct<C1, C2, IK1, IK2> create(HeapAbstractionFactory<IK1, C1> haf1,
                                                                                                                                                   HeapAbstractionFactory<IK2, C2> haf2) {
        return new CrossProduct<>(haf1, haf2);
    }

    @Override
    public CrossProductInstanceKey<IK1, IK2> record(AllocSiteNode allocationSite, CrossProductContext<C1, C2> context) {
        IK1 ik1 = haf1.record(allocationSite, context.c1);
        IK2 ik2 = haf2.record(allocationSite, context.c2);
        return memoize(new CrossProductInstanceKey<>(ik1, ik2), ik1, ik2);
    }

    @Override
    public CrossProductContext<C1, C2> merge(CallSiteLabel callSite, CrossProductInstanceKey<IK1, IK2> receiver,
                                             CrossProductContext<C1, C2> callerContext) {
        IK1 r1 = null;
        IK2 r2 = null;
        if (receiver != null) {
            r1 = receiver.ik1;
            r2 = receiver.ik2;
        }
        C1 c1 = haf1.merge(callSite, r1, callerContext.c1);
        C2 c2 = haf2.merge(callSite, r2, callerContext.c2);
        return memoize(new CrossProductContext<>(c1, c2), c1, c2);
    }

    @Override
    public CrossProductContext<C1, C2> initialContext() {
        return initial;
    }

    @Override
    public String toString() {
        return haf1 + " x " + haf2;
    }

    /**
     * Instance key derived from two child instance keys
     */
    protected static class CrossProductInstanceKey<IK1 extends InstanceKey, IK2 extends InstanceKey> implements
            InstanceKey {

        protected final IK1 ik1;
        protected final IK2 ik2;

        public CrossProductInstanceKey(IK1 ik1, IK2 ik2) {
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
    protected static class CrossProductContext<C1 extends Context, C2 extends Context> implements Context {

        protected final C1 c1;
        protected final C2 c2;

        public CrossProductContext(C1 c1, C2 c2) {
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
