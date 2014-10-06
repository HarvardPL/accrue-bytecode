package analysis.pointer.analyses;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

/**
 * This analysis wraps around another {@link HeapAbstractionFactory} and only records allocation sites for objects that
 * are subclasses of java.lang.AbstractStringBuilder
 */
public class FilterStringBuilderSpecialized extends HeapAbstractionFactory {

    /**
     * Object sensitivity parameter
     */
    private final int n;

    /**
     * default is 2 Object sensitive + 1H.
     */
    private static final int DEFAULT_DEPTH = 2;

    private IClass abstractStringBuilderClass;
    /**
     * Class loader to use when making new types to find classes for
     */
    private static final ClassLoaderReference CL = ClassLoaderReference.Primordial;
    private static final TypeReference ASB_TYPE = TypeReference.findOrCreate(CL, "Ljava/lang/AbstractStringBuilder");
    private IClassHierarchy cha;

    public FilterStringBuilderSpecialized() {
        this.n = DEFAULT_DEPTH;
    }

    public FilterStringBuilderSpecialized(int n) {
        this.n = n;
    }

    @Override
    public InstanceKey record(AllocSiteNode allocationSite, Context context) {
        AllocationNameContext c = (AllocationNameContext) context;
        AllocationName<ContextStack<AllocSiteNode>> an = c.allocationName();
        ContextStack<AllocSiteNode> stack;
        if (an == null) {
            stack = ContextStack.emptyStack();
        }
        else {
            if (abstractStringBuilderClass == null) {
                cha = AnalysisUtil.getClassHierarchy();
                abstractStringBuilderClass = cha.lookupClass(ASB_TYPE);
            }

            if (!cha.isAssignableFrom(abstractStringBuilderClass, allocationSite.getAllocatedClass())) {
                stack = an.getContext().push(null, n);
            }
            else {
                stack = an.getContext().push(an.getAllocationSite(), n);
            }
        }
        return AllocationName.create(stack, allocationSite);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Context merge(CallSiteLabel callSite, InstanceKey receiver, Context callerContext) {
        if (callSite.isStatic()) {
            // this is a static method call return the caller's context
            return callerContext;
        }

        AllocationName<ContextStack<AllocSiteNode>> rec = (AllocationName<ContextStack<AllocSiteNode>>) receiver;
        return AllocationNameContext.create(rec);
    }

    @Override
    public Context initialContext() {
        return AllocationNameContext.create(null);
    }

    @Override
    public String toString() {
        return "filter(" + PrettyPrinter.typeString(ASB_TYPE) + ")";
    }

    public static class AllocationNameContext implements Context {
        private final AllocationName<ContextStack<AllocSiteNode>> an;

        AllocationNameContext(AllocationName<ContextStack<AllocSiteNode>> an) {
            this.an = an;
        }

        public static AllocationNameContext create(AllocationName<ContextStack<AllocSiteNode>> an) {
            // XXX ANDREW: maybe make this memoize. Steve: Yes, in the meantime ensure we have equality defined.

            return new AllocationNameContext(an);
        }

        @Override
        public ContextItem get(ContextKey name) {
            return null;
        }

        public AllocationName<ContextStack<AllocSiteNode>> allocationName() {
            return an;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (an == null ? 0 : an.hashCode());
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
            if (!(obj instanceof AllocationNameContext)) {
                return false;
            }
            AllocationNameContext other = (AllocationNameContext) obj;
            if (an == null) {
                if (other.an != null) {
                    return false;
                }
            }
            else if (!an.equals(other.an)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return String.valueOf(an);
        }
    }
}
