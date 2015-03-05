package analysis.pointer.graph;

import analysis.pointer.analyses.StringInstanceKey;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

public interface PointsToIterable {

    public Iterable<InstanceKey> pointsToIterable(PointsToGraphNode node, StmtAndContext originator);

    public StringInstanceKey getSIKForSVR(StringVariableReplica x);

}
