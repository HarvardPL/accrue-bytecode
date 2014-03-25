package analysis;

import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.cha.IClassHierarchy;

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
}
