package analysis.pointer.analyses;

import util.print.PrettyPrinter;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteProgramPoint;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * A Type Sensitive pointer analysis (nType+mH), as described in
 * "Pick Your Contexts Well: Understanding Object-Sensitivity" by Smaragdakis, Bravenboer, and Lhotak, POPL 2011.
 * <p>
 * Not that this analysis is imprecise for static calls (each call to a static method is analyzed in the same context),
 * it is recommended that one combine this with another abstraction to recover precision for static calls (e.g.
 * StaticCallStiteSensitive)
 */
public class TypeSensitive extends HeapAbstractionFactory {

    /**
     * Number of elements to record for Calling Contexts
     */
    private final int n;
    /**
     * Number of elements to record for Heap Contexts
     */
    private final int m;

    /**
     * default is 2Type+1H.
     */
    private static final int DEFAULT_TYPE_DEPTH = 2;
    /**
     * default is 2Type+1H.
     */
    private static final int DEFAULT_HEAP_DEPTH = 1;

    /**
     * Create an nType+mH analysis
     * <p>
     * There is no benefit to having n > m + 1
     * 
     * @param n
     *            depth of calling context stack
     * @param m
     *            depth of heap context stack
     */
    public TypeSensitive(int n, int m) {
        this.n = n;
        this.m = m;
    }

    /**
     * Create a type sensitive abstraction factory with the default parameters
     */
    public TypeSensitive() {
        this(DEFAULT_TYPE_DEPTH, DEFAULT_HEAP_DEPTH);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextStack<ClassWrapper> merge(CallSiteProgramPoint callSite, InstanceKey receiver, Context callerContext) {
        if (callSite.isStatic()) {
            // this is a static method call. Return the caller's
            // context.
            return (ContextStack<ClassWrapper>) callerContext;
        }

        AllocationName<ContextStack<ClassWrapper>> rec = (AllocationName<ContextStack<ClassWrapper>>) receiver;
        return rec.getContext().push(new ClassWrapper(rec.getAllocationSite().getAllocatingClass()), n);
    }

    @SuppressWarnings("unchecked")
    @Override
    public AllocationName<ContextStack<ClassWrapper>> record(AllocSiteNode allocationSite, Context context) {
        ContextStack<ClassWrapper> allocationContext = ((ContextStack<ClassWrapper>) context).truncate(m);
        return AllocationName.create(allocationContext, allocationSite);
    }

    @Override
    public ContextStack<ClassWrapper> initialContext() {
        return ContextStack.<ClassWrapper> emptyStack();
    }

    @Override
    public String toString() {
        return n + "Type+" + m + "H";
    }

    /**
     * We want nicer printing so wrap the class in a lightweight wrapper
     */
    protected static class ClassWrapper {

        private IClass c;

        ClassWrapper(IClass c) {
            assert c != null;
            this.c = c;
        }

        @Override
        public String toString() {
            return PrettyPrinter.typeString(c);
        }

        @Override
        public int hashCode() {
            return c.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return c == ((ClassWrapper) obj).c;
        }
    }
}
