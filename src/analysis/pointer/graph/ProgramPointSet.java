package analysis.pointer.graph;

import java.util.HashSet;
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

    public boolean add(InterProgramPointReplica ippr) {
        return this.s.add(ippr);
    }

    /**
     * Add all of the program points in pps to this set. Return true if a change was made to this set.
     */
    public boolean addAll(ProgramPointSet pps) {
        return this.s.addAll(pps.s);
    }

    /**
     * Does this set contain all of the program points in pps?
     */
    public boolean containsAll(ProgramPointSet pps) {
        return this.s.containsAll(pps.s);
    }

    /**
     * Does this set contain of the program points ippr?
     */
    public boolean contains(InterProgramPointReplica ippr) {
        return this.s.contains(ippr);
    }

    public static ProgramPointSet singleton(InterProgramPointReplica ippr) {
        ProgramPointSet x = new ProgramPointSet();
        x.add(ippr);
        return x;
    }

    public boolean isEmpty() {
        return s.isEmpty();
    }

    public boolean containsAny(ProgramPointSet pps) {
        Set<InterProgramPointReplica> t = new HashSet<>(s);
        t.retainAll(pps.s);
        return !t.isEmpty(); // intersection is non empty
    }

}
