package analysis.pointer.graph;

import analysis.pointer.analyses.AString;
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
    public AString getAStringUpdatesFor(StringSolutionVariable x);

}
