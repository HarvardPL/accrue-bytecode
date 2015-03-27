package analysis.pointer.graph;

import util.optional.Optional;
import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

public interface PointsToIterable {

    public Iterable<InstanceKey> pointsToIterable(PointsToGraphNode node, StmtAndContext originator);

    /**
     * Note that Optional.none(), does *not* indicate bottom or top elements of AString. It indicates that there is no
     * new information to be shared.
     *
     * @param x
     * @return if there is "new information", then it returns Optional.some(theNewInformation)
     */
    public Optional<AString> getAStringUpdatesFor(StringVariableReplica x);

    public abstract AString astringForPointsToGraphNode(PointsToGraphNode n, StmtAndContext originator);

}
