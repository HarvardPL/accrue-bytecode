package analysis.pointer.engine;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

public interface DependencyRecorder<IK extends InstanceKey, C extends Context> {

    /**
     * Record that node has been read by sac
     *
     * @param node
     */
    void recordRead(/*PointsToGraphNode*/int node, StmtAndContext<IK, C> sac);

    /**
     * Record that node n has started to be collapsed, and will be represented by node rep.
     *
     * @param n
     * @param rep
     */
    void startCollapseNode(int n, int rep);

    /**
     * Record that we have finished collapsing n and it will now be represented by rep.
     *
     * @param n
     * @param rep
     */
    void finishCollapseNode(int n, int rep);

    /**
     * Record that callee is now called in calleeContext.
     *
     * @param callee
     * @param calleeContext
     */
    void recordNewContext(IMethod callee, C calleeContext);


}
