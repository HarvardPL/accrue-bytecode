package analysis.pointer.engine;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public interface DependencyRecorder {

    /**
     * Record that node has been read by sac
     *
     * @param node
     */
    void recordRead(/*PointsToGraphNode*/int node, StmtAndContext sac);

    /**
     * Record that node n has been collpased and is now represented by node rep.
     *
     * @param n
     * @param rep
     */
    void recordCollapsedNodes(int n, int rep);

    /**
     * Record that callee is now called in calleeContext.
     * 
     * @param callee
     * @param calleeContext
     */
    void recordNewContext(IMethod callee, Context calleeContext);

}
