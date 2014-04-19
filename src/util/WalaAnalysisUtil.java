package util;

import java.util.Iterator;

import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.FakeRootMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;

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
     * If true then implicit errors should be handled by all analyses
     */
    public static final boolean INCLUDE_IMPLICIT_ERRORS = false;
    
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
            Entrypoint e = (Entrypoint) it.next();
            // Add in the fake root method that sets up the call to main
            SSAAbstractInvokeInstruction call = e.addCall(fakeRoot);

            if (call == null) {
                throw new RuntimeException("Missing entry point " + e);
            }
        }
    }

    /**
     * 
     * @return contains the SSA IR
     */
    public AnalysisCache getCache() {
        return cache;
    }

    /**
     * 
     * @return entry points and other WALA options
     */
    public AnalysisOptions getOptions() {
        return options;
    }

    /**
     * 
     * @return class hierarchy
     */
    public IClassHierarchy getClassHierarchy() {
        return cha;
    }
    
    /**
     * 
     * @return WALA fake root method (sets up and calls actual entry points)
     */
    public FakeRootMethod getFakeRoot() {
        return fakeRoot;
    }
}
