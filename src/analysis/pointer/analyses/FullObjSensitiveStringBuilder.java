package analysis.pointer.analyses;

import analysis.AnalysisUtil;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

/**
 * A full object sensitive analysis, as described in "Pick Your Contexts Well: Understanding Object-Sensitivity" by
 * Smaragdakis, Bravenboer, and Lhotak, POPL 2011.
 * <p>
 * Not that this analysis is imprecise for static calls: the context for the static call is the context of the caller.
 * It is recommended that one combine this with another abstraction to recover precision for static calls (e.g.
 * StaticCallStiteSensitive)
 */
public class FullObjSensitiveStringBuilder extends
        HeapAbstractionFactory<AllocationName<ContextStack<AllocSiteNode>>, AllocationNameContext> {

    /**
     * Object sensitivity parameter
     */
    private final int n;

    /**
     * default is 2 Object sensitive + 1H.
     */
    private static final int DEFAULT_DEPTH = 2;

    /**
     * Create an n-object-sensitive analysis
     *
     * @param n depth of calling context stack
     */
    public FullObjSensitiveStringBuilder(int n) {
        this.n = n;
    }

    /**
     * Create a full object sensitive abstraction factory with the default parameters
     */
    public FullObjSensitiveStringBuilder() {
        this(DEFAULT_DEPTH);
    }

    /**
     * Is the given class is a string builder
     *
     * @param c class to check
     * @return true if <code>c</code> is a string builder
     */
    private static boolean isStringBuilder(IClass c) {
        TypeReference abs = TypeReference.findOrCreate(ClassLoaderReference.Primordial,
                                                       "Ljava/lang/AbstractStringBuilder");
        assert AnalysisUtil.getClassHierarchy().lookupClass(abs) != null : abs;
        return c.getReference() == TypeReference.JavaLangStringBuffer
                || c.getReference() == TypeReference.JavaLangStringBuilder || c.getReference() == abs
                || c.getReference() == TypeReference.JavaLangString;
    }

    @Override
    public AllocationNameContext merge(CallSiteLabel callSite, AllocationName<ContextStack<AllocSiteNode>> receiver,
                                       AllocationNameContext callerContext) {
        if (!callSite.isStatic() && isStringBuilder(receiver.getConcreteType())) {
            return AllocationNameContext.create(receiver);
        }
        return initialContext();
    }

    @Override
    public AllocationName<ContextStack<AllocSiteNode>> record(AllocSiteNode allocationSite,
                                                              AllocationNameContext context) {
        if (isStringBuilder(allocationSite.getAllocatedClass()) || isStringBuilder(allocationSite.getAllocatingClass())) {
            AllocationNameContext c = context;
            AllocationName<ContextStack<AllocSiteNode>> an = c.allocationName();
            ContextStack<AllocSiteNode> stack;
            if (an == null) {
                stack = ContextStack.emptyStack();
            }
            else {
                stack = an.getContext().push(an.getAllocationSite(), n);
            }
            return AllocationName.create(stack, allocationSite);
        }
        ContextStack<AllocSiteNode> stack = ContextStack.emptyStack();
        return AllocationName.create(stack, allocationSite);
    }

    @Override
    public AllocationNameContext initialContext() {
        return AllocationNameContext.create(null);
    }

    @Override
    public String toString() {
        return n + "StringBuilderFullObjSens+1H";
    }
}
