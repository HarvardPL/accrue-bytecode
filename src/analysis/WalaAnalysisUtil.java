package analysis;

import java.util.Iterator;

import signatures.Signatures;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.impl.FakeRootMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Global instances of WALA classes and global constants
 */
public class WalaAnalysisUtil {
    /**
     * Cache containing and managing SSA IR we are analyzing
     */
    private final AnalysisCache cache;
    /**
     * Options for the analysis (e.g. entry points)
     */
    private final AnalysisOptions options;
    /**
     * Class hierarchy for the code being analyzed
     */
    private final IClassHierarchy cha;
    /**
     * WALA's fake root method (calls the entry points)
     */
    private final FakeRootMethod fakeRoot;
    /**
     * WALA representation of java.lang.String
     */
    private final IClass stringClass;
    /**
     * WALA representation of the class for the value field of a string
     */
    private final IClass stringValueClass;
    /**
     * WALA representation of the class for java.lang.Throwable
     */
    private final IClass throwableClass;

    /**
     * Create a pass which will generate points-to statements
     * 
     * @param cha
     *            class hierarchy
     * @param cache
     *            contains the SSA IR
     * @param options
     *            entry points and other WALA options
     */
    public WalaAnalysisUtil(IClassHierarchy cha, AnalysisCache cache, AnalysisOptions options) {
        this.options = options;
        this.cache = cache;
        this.cha = cha;
        // Set up the entry points
        fakeRoot = new FakeRootMethod(cha, options, cache);
        for (Iterator<? extends Entrypoint> it = options.getEntrypoints().iterator(); it.hasNext();) {
            Entrypoint e = it.next();
            // Add in the fake root method that sets up the call to main
            SSAAbstractInvokeInstruction call = e.addCall(fakeRoot);

            if (call == null) {
                throw new RuntimeException("Missing entry point " + e);
            }
        }
        // Have to add return to maintain the invariant that two basic blocks
        // have more than one edge between them. Otherwise the exit basic block
        // could have an exception edge and normal edge from the same basic
        // block.
        fakeRoot.addReturn(-1, false);
        stringClass = cha.lookupClass(TypeReference.JavaLangString);
        stringValueClass = cha.lookupClass(TypeReference.JavaLangObject);
        throwableClass = cha.lookupClass(TypeReference.JavaLangThrowable);
    }

    /**
     * Cache of various analysis artifacts, contains the SSA IR
     * 
     * @return WALA analysis cache
     */
    public AnalysisCache getCache() {
        return cache;
    }

    /**
     * WALA analysis options, contains the entry-point
     * 
     * @return WALA analysis options
     */
    public AnalysisOptions getOptions() {
        return options;
    }

    /**
     * WALA's class hierarchy
     * 
     * @return class hierarchy
     */
    public IClassHierarchy getClassHierarchy() {
        return cha;
    }

    /**
     * The root method that calls the entry-points
     * 
     * @return WALA fake root method (sets up and calls actual entry points)
     */
    public FakeRootMethod getFakeRoot() {
        return fakeRoot;
    }

    /**
     * Get the IR for the given method, do not call for native methods
     * 
     * @param resolvedMethod
     *            method to get the IR for
     * @return the code for the given method
     */
    public IR getIR(IMethod resolvedMethod) {
        IR sigIR = Signatures.getSignatureIR(resolvedMethod, this);
        if (sigIR != null) {
            return sigIR;
        }

        if (resolvedMethod.isNative()) {
            // Native method with no signature
            return null;
        }

        return cache.getSSACache().findOrCreateIR(resolvedMethod, Everywhere.EVERYWHERE, options.getSSAOptions());
    }

    public IClass getStringClass() {
        return stringClass;
    }

    public IClass getStringValueClass() {
        return stringValueClass;
    }

    public IClass getThrowableClass() {
        return throwableClass;
    }
}
