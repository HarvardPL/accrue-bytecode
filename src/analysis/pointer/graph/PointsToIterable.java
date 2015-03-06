package analysis.pointer.graph;

import util.optional.Optional;
import analysis.pointer.analyses.AString;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

public interface PointsToIterable {

    public Iterable<InstanceKey> pointsToIterable(PointsToGraphNode node, StmtAndContext originator);

    public Optional<AString> getAStringFor(StringVariableReplica x);

}
