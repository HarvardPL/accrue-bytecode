package analysis.pointer.analyses;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

/**
 * This analysis wraps around another {@link HeapAbstractionFactory} and only records allocation sites for objects that
 * are subclasses of java.lang.AbstractStringBuilder
 */
public class FilterStringBuilderSpecialized extends
        HeapAbstractionFactory<AllocationName<ContextStack<AllocSiteNode>>, AllocationNameContext> {

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
    public AllocationName<ContextStack<AllocSiteNode>> record(AllocSiteNode allocationSite,
                                                              AllocationNameContext context) {
        AllocationNameContext c = context;
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

    @Override
    public AllocationNameContext merge(CallSiteLabel callSite, AllocationName<ContextStack<AllocSiteNode>> receiver,
                                       AllocationNameContext callerContext) {
        if (callSite.isStatic()) {
            // this is a static method call return the caller's context
            return callerContext;
        }

        AllocationName<ContextStack<AllocSiteNode>> rec = receiver;
        return AllocationNameContext.create(rec);
    }

    @Override
    public AllocationNameContext initialContext() {
        return AllocationNameContext.create(null);
    }

    @Override
    public String toString() {
        return "filter(" + PrettyPrinter.typeString(ASB_TYPE) + ")";
    }
}
