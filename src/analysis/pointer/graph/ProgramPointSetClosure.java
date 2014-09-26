package analysis.pointer.graph;

import java.util.Set;

import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

/*
 * Represents a set of InterProgramPointReplicas. The set is represented
 * by a set of sources and a points to graph node, and implicitly represents
 * all program points that are reachable from a source without going through a
 * program point that kills the node.
 *
 * TODO: need to add a reference to whatever datastructure(s) allows us to determine successor relations over program points.
 */
public class ProgramPointSetClosure {
    /**
     * This is the set of program points that were added. Implicitly, all program points that are reachable from a
     * source, without killing the node, are also in the set.
     */
    private final Set<InterProgramPointReplica> sources;

    /**
     * If this is a ProgramPointSetClosure for the edge from fromBase.f to toNode, this is the fromBase. Otherwise -1.
     */
    private final/*InstanceKeyRecency*/int fromBase; // -1 if no relevant from node
    private final/*PointsToGraphNode*/int toNode;

    public ProgramPointSetClosure(/*InstanceKeyRecency*/int fromBase, /*PointsToGraphNode*/int toNode) {
        this.sources = PointsToAnalysisMultiThreaded.makeConcurrentSet();
        this.fromBase = fromBase;
        this.toNode = toNode;
    }

    public boolean add(InterProgramPointReplica ippr) {
        return this.sources.add(ippr);
    }

    public boolean addAll(ExplicitProgramPointSet toAdd) {
        boolean changed = false;
        for (InterProgramPointReplica ippr : toAdd) {
            changed |= this.sources.add(ippr);
        }
        return changed;
    }

    public boolean addAll(ProgramPointSetClosure toAdd) {
        assert (toAdd.toNode == this.toNode);
        return this.sources.addAll(toAdd.sources);
    }


    /**
     * Does this set contain of the program points ippr?
     */
    public boolean contains(InterProgramPointReplica ippr) {
        // TODO: XXX. Monica: change this implementation so that it checks if ippr is reachable from a source without going through a program point that kills this.node.
        return this.sources.contains(ippr);
    }


    public boolean isEmpty() {
        return sources.isEmpty();
    }

    public boolean containsAll(ExplicitProgramPointSet pps) {
        for (InterProgramPointReplica ippr : pps) {
            if (!this.contains(ippr)) {
                return false;
            }
        }
        return true;
    }

    public boolean containsAll(ProgramPointSetClosure pps) {
        assert this.toNode == pps.toNode;
        for (InterProgramPointReplica ippr : pps.sources) {
            if (!this.sources.contains(ippr) && !this.contains(ippr)) {
                return false;
            }
        }
        return true;
    }

    public /*InstanceKeyRecency*/int getFromBase() {
        return fromBase;
    }

}
