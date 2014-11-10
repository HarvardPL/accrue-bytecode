package analysis.pointer.graph;

import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

public interface ReachabilityQueryOriginMaker {

    /**
     * Make a ReachabilityQueryOrigin that will be triggered if the reachability result changes so that src points to
     * trg at ippr.
     *
     * Note that src is a flow sensitive points to graph node.
     *
     * @param src
     * @param trg
     * @param ippr
     * @return
     */
    ReachabilityQueryOrigin makeOrigin(/*PointsToGraphNode*/int src, /*InstanceKeyRecency*/int trg,
                                       InterProgramPointReplica ippr);

}
