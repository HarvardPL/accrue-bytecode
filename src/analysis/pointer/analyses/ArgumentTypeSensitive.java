package analysis.pointer.analyses;

import java.util.ArrayList;
import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

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
public class ArgumentTypeSensitive extends HeapAbstractionFactory {

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
    public ArgumentTypeSensitive(int n, int m) {
        this.n = n;
        this.m = m;
    }

    /**
     * Create a type sensitive abstraction factory with the default parameters
     */
    public ArgumentTypeSensitive() {
        this(DEFAULT_TYPE_DEPTH, DEFAULT_HEAP_DEPTH);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextStack<ArgumentWrapper> merge(CallSiteLabel callSite, InstanceKey receiver,
                                               List<InstanceKey> arguments, Context callerContext) {
        if (callSite.isStatic()) {
            // XXX If this is a static call then just use the caller context
            return (ContextStack<ArgumentWrapper>) callerContext;
        }

        assert receiver != null;
        List<IClass> argTypes = new ArrayList<>(arguments.size() + 1);
        AllocationName<ContextStack<ArgumentWrapper>> rec = (AllocationName<ContextStack<ArgumentWrapper>>) receiver;
        argTypes.add(rec.getAllocationSite().getAllocatingClass());

        for (InstanceKey arg : arguments) {
            if (arg != null) {
                AllocationName<ContextStack<ArgumentWrapper>> a = (AllocationName<ContextStack<ArgumentWrapper>>) arg;
                argTypes.add(a.getAllocationSite().getAllocatingClass());
            }
            else {
                // The argument at this position didn't point to anything
                argTypes.add(null);
            }
        }

        assert rec != null;
        return rec.getContext().push(new ArgumentWrapper(argTypes), n);
    }

    @SuppressWarnings("unchecked")
    @Override
    public AllocationName<ContextStack<ArgumentWrapper>> record(AllocSiteNode allocationSite, Context context) {
        ContextStack<ArgumentWrapper> allocationContext = ((ContextStack<ArgumentWrapper>) context).truncate(m);
        return AllocationName.create(allocationContext, allocationSite);
    }

    @Override
    public ContextStack<ArgumentWrapper> initialContext() {
        return ContextStack.<ArgumentWrapper> emptyStack();
    }

    @Override
    public String toString() {
        return n + "Type+" + m + "H";
    }

    /**
     * Wrapper around a list of types for method arguments
     */
    protected static class ArgumentWrapper {

        private List<IClass> c;

        ArgumentWrapper(List<IClass> c) {
            assert c != null;
            this.c = c;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (IClass cl : c) {
                sb.append(PrettyPrinter.typeString(cl));
                sb.append(", ");
            }
            sb.delete(sb.length() - 2, sb.length() - 1);
            sb.append("]");
            return sb.toString();
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((c == null) ? 0 : c.hashCode());
            return result;
        }


        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ArgumentWrapper other = (ArgumentWrapper) obj;
            if (c == null) {
                if (other.c != null) {
                    return false;
                }
            }
            else if (!c.equals(other.c)) {
                return false;
            }
            return true;
        }
    }
}
