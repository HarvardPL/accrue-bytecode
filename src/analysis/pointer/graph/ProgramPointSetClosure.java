package analysis.pointer.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import analysis.AnalysisUtil;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.util.intset.EmptyIntSet;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.SparseIntSet;

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

    private final/*PointsToGraphNode*/int from;
    /**
     * If this is a ProgramPointSetClosure for the edge from fromBase.f to toNode, this is the fromBase. Otherwise -1.
     */
    private final/*InstanceKeyRecency*/int fromBase;
    private final/*InstanceKeyRecency*/int to;

    public ProgramPointSetClosure(/*PointsToGraphNode*/int from, /*InstanceKeyRecency*/int to, PointsToGraph g) {
        this.sources = AnalysisUtil.createConcurrentSet();
        this.from = from;
        this.to = to;
        assert from >= 0 && to >= 0;

        this.fromBase = g.baseNodeForPointsToGraphNode(from);
        assert (fromBase == -1 || g.isMostRecentObject(fromBase)) : "If we have a fromBase, it should be a most recent object, since these are the only ones we track flow sensitively.";
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
        assert (toAdd.to == this.to);
        return this.sources.addAll(toAdd.sources);
    }


    /**
     * Does this set contain of the program points ippr?
     */
    public boolean contains(InterProgramPointReplica ippr, PointsToGraph g) {
        return g.programPointReachability().reachable(this.getSources(g), ippr, this.noKill(), this.noAlloc(g));
    }


    private IntSet noAlloc(PointsToGraph g) {
        if (g.isMostRecentObject(this.to)) {
            // to will be in the set!
            if (this.fromBase >= 0) {
                // fromBase is in the set, as is to.
                return SparseIntSet.pair(this.to, this.fromBase);
            }
            else {
                return SparseIntSet.singleton(this.to);
            }

        }
        else {
            // to will not be in the set.
            if (this.fromBase >= 0) {
                // fromBase is in the set
                return SparseIntSet.singleton(this.fromBase);
            }
            else {
                // nothing in the set
                return EmptyIntSet.instance;
            }
        }

    }

    private IntSet noKill() {
        return SparseIntSet.singleton(this.from);
    }

    /**
     * Return all sources where the "from" PointsToGraphNode starts pointing to the "to" InstanceKeyRecency.
     *
     * This is the explict set "sources", and in addition, if this is not the most recent object, but we are tracking
     * the most recent object, then it contains any allocation site for the object where the "from" PointsToGraphNode
     * pointed to the most recent "to" InstanceKeyRecency immediately before the allocation.
     *
     * @return
     */
    private Collection<InterProgramPointReplica> getSources(PointsToGraph g) {
        // XXX TODO turn this into an iterator, so that we lazily look at these allocation sites.
        if (!g.isMostRecentObject(to) && g.isTrackingMostRecentObject(to)) {
            List<InterProgramPointReplica> s = new ArrayList<>();
            s.addAll(this.sources);
            // we need to add allocation sites of the to object, where from pointed to
            // the most recent version just before the allocation.
            int mostRecentVersion = g.mostRecentVersion(this.to);

            for (ProgramPointReplica allocPP : g.getAllocationSitesOf(mostRecentVersion)) {
                //                if (originator != null) {
                //                    g.recordRead(this.from, originator);
                //                }
                if (g.pointsTo(this.from, mostRecentVersion, allocPP.pre())) {
                    // the from node pointed to the most recent version before the allocation,
                    // so the from node points to the non-most recent version (i.e., "this.to")
                    // after the allocation
                    s.add(allocPP.post());
                }
            }
            return s;
        }
        return this.sources;
    }


    public boolean isEmpty() {
        return sources.isEmpty();
    }

    public boolean containsAll(ExplicitProgramPointSet pps, PointsToGraph g) {
        for (InterProgramPointReplica ippr : pps) {
            if (!this.contains(ippr, g)) {
                return false;
            }
        }
        return true;
    }

    public boolean containsAll(ProgramPointSetClosure pps, PointsToGraph g) {
        assert this.to == pps.to;
        for (InterProgramPointReplica ippr : pps.sources) {
            if (!this.sources.contains(ippr) && !this.contains(ippr, g)) {
                return false;
            }
        }
        return true;
    }

    public/*PointsToGraphNode*/int getFrom() {
        return from;
    }

}
