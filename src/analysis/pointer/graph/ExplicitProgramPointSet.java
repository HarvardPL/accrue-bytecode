package analysis.pointer.graph;

import java.util.Iterator;
import java.util.Set;

import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

/*
 * Represents an explicit set of InterProgramPointReplicas.
 */
public class ExplicitProgramPointSet implements Iterable<InterProgramPointReplica> {
    private final Set<InterProgramPointReplica> points;


    public ExplicitProgramPointSet() {
        this.points = PointsToAnalysisMultiThreaded.makeConcurrentSet();
    }

    public boolean add(InterProgramPointReplica ippr) {
        return this.points.add(ippr);
    }

    /**
     * Add all of the program points in pps to this set. Return true if a change was made to this set.
     */
    public boolean addAll(ExplicitProgramPointSet pps) {
        return this.points.addAll(pps.points);
    }

    /**
     * Does this set contain of the program points ippr?
     */
    public boolean contains(InterProgramPointReplica ippr) {
        return this.points.contains(ippr);
    }

    public static ExplicitProgramPointSet singleton(InterProgramPointReplica ippr) {
        assert ippr != null;
        ExplicitProgramPointSet x = new ExplicitProgramPointSet();
        x.add(ippr);
        return x;
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public boolean containsAny(ExplicitProgramPointSet pps) {
        for (InterProgramPointReplica ippr : pps.points) {
            if (this.contains(ippr)) {
                return true;
            }
        }
        return false;

    }

    @Override
    public Iterator<InterProgramPointReplica> iterator() {
        return this.points.iterator();
    }

}
