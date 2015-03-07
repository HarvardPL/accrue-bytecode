package analysis.pointer.engine;

import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.StringVariableReplica;

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
     * Record that a string node has been read by sac
     *
     * @param node
     */
    void recordStringRead(StringVariableReplica svr, StmtAndContext sac);

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
    void recordNewContext(IMethod callee, Context calleeContext);


}
