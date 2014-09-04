package analysis.pointer.graph;

import java.util.Set;

import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

/*
 * Represents a set of InterProgramPointReplicas
 * TODO: make this more efficient
 */
public class ProgramPointSet {
    private final Set<InterProgramPointReplica> s;
    public ProgramPointSet() {
        this.s = PointsToAnalysisMultiThreaded.makeConcurrentSet();
    }

    /**
     * Add all of the program points in pps to this set. Return true if a change was made to this set.
     */
    public boolean addAll(ProgramPointSet pps) {
    }

    /**
     * Does this set contain all of the program points in pps?
     */
    public boolean containsAll(ProgramPointSet pps) {
    }
}
