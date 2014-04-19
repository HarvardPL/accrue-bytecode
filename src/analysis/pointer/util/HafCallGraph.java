package analysis.pointer.util;

import util.WalaAnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.impl.ExplicitCallGraph;
import com.ibm.wala.ipa.callgraph.impl.FakeRootMethod;
import com.ibm.wala.util.CancelException;

/**
 * Call graph where new contexts are created using a
 * {@link HeapAbstractionFactory}
 */
public class HafCallGraph extends ExplicitCallGraph {

    /**
     * Heap abstraction factory defining how contexts are created
     */
    private final HeapAbstractionFactory haf;
    /**
     * WALA's fake root method that calls entry points
     */
    private final FakeRootMethod fakeRoot;

    /**
     * Create and initialize a new call graph where contexts are created using
     * the given {@link HeapAbstractionFactory}
     * 
     * @param util
     *            Utility classes
     * @param haf
     *            Heap abstraction factory
     */
    public HafCallGraph(WalaAnalysisUtil util, HeapAbstractionFactory haf) {
        super(util.getClassHierarchy(), util.getOptions(), util.getCache());
        this.haf = haf;
        this.fakeRoot = util.getFakeRoot();
        try {
            this.setInterpreter(new SimpleContextInterpreter(util.getOptions(), util.getCache()));
            this.init();
        } catch (CancelException e) {
            throw new RuntimeException("WALA CancelException initializing call graph. " + e.getMessage());
        }
    }

    @Override
    protected CGNode makeFakeWorldClinitNode() throws CancelException {
        // We handle class initialization elsewhere.
        return null;
    }

    @Override
    protected CGNode makeFakeRootNode() throws CancelException {
        return findOrCreateNode(fakeRoot, haf.initialContext());
    }
}
