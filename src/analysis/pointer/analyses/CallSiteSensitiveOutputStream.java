package analysis.pointer.analyses;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteProgramPoint;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

/**
 * Analysis where the contexts are based on procedure call sites
 */
public class CallSiteSensitiveOutputStream extends HeapAbstractionFactory {

    /**
     * Default depth of call sites to keep track of
     */
    private static final int DEFAULT_SENSITIVITY = 5;
    /**
     * Depth of the call sites to keep track of
     */
    private final int sensitivity;

    private IClass printStreamClass;
    /**
     * Class loader to use when making new types to find classes for
     */
    private static final ClassLoaderReference CL = ClassLoaderReference.Primordial;
    private static final TypeReference PS_TYPE = TypeReference.findOrCreate(CL, "Ljava/io/PrintStream");
    private IClassHierarchy cha;

    /**
     * Create a call site sensitive heap abstraction factory with the default depth, i.e. up to the default number of
     * call sites are tracked
     */
    public CallSiteSensitiveOutputStream() {
        this(DEFAULT_SENSITIVITY);
    }

    /**
     * Create a call site sensitive heap abstraction factory with the given depth, i.e. up to depth call sites are
     * tracked
     *
     * @param sensitivity
     *            depth of the call site stack
     */
    public CallSiteSensitiveOutputStream(int sensitivity) {
        this.sensitivity = sensitivity;
    }

    @Override
    public String toString() {
        return "cs(" + this.sensitivity + ", " + PrettyPrinter.typeString(PS_TYPE) + ")";
    }

    public int getSensitivity() {
        return sensitivity;
    }

    @SuppressWarnings("unchecked")
    @Override
    public AllocationName<ContextStack<CallSiteProgramPoint>> record(AllocSiteNode allocationSite, Context context) {
        return AllocationName.create((ContextStack<CallSiteProgramPoint>) context, allocationSite);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextStack<CallSiteProgramPoint> merge(CallSiteProgramPoint callSite, InstanceKey receiver,
                                                    Context callerContext) {
        if (printStreamClass == null) {
            cha = AnalysisUtil.getClassHierarchy();
            printStreamClass = cha.lookupClass(PS_TYPE);
            assert printStreamClass != null;
        }

        if (receiver.getConcreteType() == null || callSite.isStatic()
                || !cha.isAssignableFrom(printStreamClass, receiver.getConcreteType())) {
            callSite = null;
        }
        return ((ContextStack<CallSiteProgramPoint>) callerContext).push(callSite, sensitivity);
    }

    @Override
    public ContextStack<CallSiteProgramPoint> initialContext() {
        return ContextStack.<CallSiteProgramPoint> emptyStack();
    }
}
