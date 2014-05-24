package analysis.pointer.analyses;

import java.util.Iterator;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.collections.Pair;

/**
 * Heap context that is the allocation site and the context for the allocation site
 */
public class AllocationName<CC extends Context> implements InstanceKey {

    /**
     * Allocation site
     */
    private final AllocSiteNode asn;
    /**
     * Context for the allocation
     */
    private final CC context;

    /**
     * Unique name for an allocation site with the given context and allocation site node
     * 
     * @param allocationContext
     *            context in which the allocation occurs
     * @param asn
     *            allocation site
     * 
     * @return allocation site name
     */
    public static <CC extends Context> AllocationName<CC> create(CC allocationContext, AllocSiteNode asn) {
        return HeapAbstractionFactory.memoize(new AllocationName<>(allocationContext, asn), allocationContext, asn);
    }

    /**
     * New allocation site
     * 
     * @param allocationContext
     *            context in which the allocation occurs
     * @param asn
     *            allocation site
     */
    private AllocationName(CC allocationContext, AllocSiteNode asn) {
        this.context = allocationContext;
        this.asn = asn;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }

    @Override
    public String toString() {
        return asn + " in " + context;
    }

    @Override
    public IClass getConcreteType() {
        return asn.getAllocatedClass();
    }

    @Override
    public Iterator<Pair<CGNode, NewSiteReference>> getCreationSites(CallGraph CG) {
        throw new UnsupportedOperationException();
    }

    public AllocSiteNode getAllocationSite() {
        return asn;
    }

    public CC getContext() {
        return context;
    }
}
