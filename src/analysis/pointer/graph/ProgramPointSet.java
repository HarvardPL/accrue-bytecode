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
}
