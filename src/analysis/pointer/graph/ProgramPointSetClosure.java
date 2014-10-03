package analysis.pointer.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import util.OrderedPair;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;
import analysis.pointer.statements.ProgramPoint.PostProgramPoint;
import analysis.pointer.statements.ProgramPoint.PreProgramPoint;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

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

    private final/*PointsToGraphNode*/int from; // -1 if no relevant from node
    /**
     * If this is a ProgramPointSetClosure for the edge from fromBase.f to toNode, this is the fromBase. Otherwise -1.
     */
    private final/*InstanceKeyRecency*/int fromBase;
    private final/*InstanceKeyRecency*/int to;

    public ProgramPointSetClosure(/*PointsToGraphNode*/int from, /*InstanceKeyRecency*/int to, PointsToGraph g) {
        this.sources = PointsToAnalysisMultiThreaded.makeConcurrentSet();
        this.from = from;
        this.to = to;
        this.fromBase = g.baseNodeForPointsToGraphNode(from);
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
        // do a depth first search from each of the sources, to see if ippr can be reached.
        Set<InterProgramPointReplica> visited = new HashSet<>();

        for (InterProgramPointReplica src : this.getSources(g)) {
            // do a depth first search from src
            if (dfs(src, visited, ippr, g)) {
                return true;
            }
        }
        return false;

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
        if (!g.isMostRecentObject(to) && g.isTrackingMostRecentObject(to)) {
            // we need to add allocation sites of the to object, where from pointed to
            // the most recent version just before the allocation.
            List<InterProgramPointReplica> s = new ArrayList<>();
            s.addAll(this.sources);

            int mostRecentVersion = g.mostRecentVersion(this.to);

            for (ProgramPointReplica allocPP : g.getAllocationSitesOf(mostRecentVersion)) {
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

    private boolean dfs(InterProgramPointReplica i,
                        Set<InterProgramPointReplica> visited, InterProgramPointReplica target, PointsToGraph g) {
        if (i.equals(target)) {
            return true;
        }
        if (visited.contains(i)) {
            return false;
        }
        visited.add(i);

        for (InterProgramPointReplica j : successors(i, g)) {
            if (dfs(j, visited, target, g)) {
                return true;
            }
        }
        return false;
    }

    private Collection<InterProgramPointReplica> successors(InterProgramPointReplica i, PointsToGraph g) {
        InterProgramPoint ipp = i.getInterPP();
        ProgramPoint pp = ipp.getPP();
        Context context = i.getContext();
        if (ipp instanceof PreProgramPoint) {
            if (pp instanceof CallSiteProgramPoint) {
                OrderedPair<CallSiteProgramPoint, Context> caller = new OrderedPair<>((CallSiteProgramPoint) pp, context);
                Set<OrderedPair<IMethod, Context>> calleeSet = g.getCallGraphMap().get(caller);
                List<InterProgramPointReplica> l = new ArrayList<>();
                for (OrderedPair<IMethod, Context> callee: calleeSet) {
                    ProgramPoint entry = g.registrar.getMethodSummary(callee.fst()).getEntryPP();
                    l.add(InterProgramPointReplica.create(callee.snd(), entry.post()));
                }
                return l;
            }
            else if (pp.isNormalExitSummaryNode()) {
                OrderedPair<IMethod, Context> callee = new OrderedPair<>(pp.containingProcedure(), context);
                Set<OrderedPair<CallSiteProgramPoint, Context>> callerSet = g.getCallGraphReverseMap().get(callee);
                List<InterProgramPointReplica> l = new ArrayList<>();
                for (OrderedPair<CallSiteProgramPoint, Context> caller: callerSet) {
                    l.add(InterProgramPointReplica.create(caller.snd(), caller.fst().post()));
                }
                return l;
            }
            else if (pp.isExceptionExitSummaryNode()) {
                OrderedPair<IMethod, Context> callee = new OrderedPair<>(pp.containingProcedure(), context);
                Set<OrderedPair<CallSiteProgramPoint, Context>> callerSet = g.getCallGraphReverseMap().get(callee);
                List<InterProgramPointReplica> l = new ArrayList<>();
                for (OrderedPair<CallSiteProgramPoint, Context> caller: callerSet) {
                    IMethod callerContainingProcedure = caller.fst().containingProcedure();
                    ProgramPoint exceptionExit = g.registrar.getMethodSummary(callerContainingProcedure).getExceptionExitPP();
                    l.add(InterProgramPointReplica.create(caller.snd(), exceptionExit.pre()));
                }
                return l;
            }
            else {
                PointsToStatement stmt = g.registrar.getStmtAtPP(pp);
                // not a call or a return, it's just a normal statement.
                // does ipp kill this.node?
                if (from == g.lookupDictionary(stmt.killed(context, g.getHaf()))) {
                    return Collections.emptyList();
                }

                // is "to" allocated at this program point?
                int/*InstanceKeyRecency*/justAllocated = g.lookupDictionary(stmt.justAllocated(context, g.getHaf()));
                if (to == justAllocated) {
                    // The to node just got allocated, and the to node is the most recent object created by that allocation site
                    assert g.lookupInstanceKeyDictionary(to).isRecent();
                    return Collections.emptyList();
                }
                if (fromBase == justAllocated) {
                    // We are the set of program points pp such that "to \in pointsToFS(fromBase.f, pp)" is true,
                    // and at this program point, fromBase just got allocated.
                    // XXX: this case not covered yet?
                    assert g.lookupInstanceKeyDictionary(fromBase).isRecent();
                    return Collections.emptyList();
                }

                return Collections.singletonList(InterProgramPointReplica.create(context, pp.post()));
            }
        }
        else if (ipp instanceof PostProgramPoint) {
            Set<ProgramPoint> ppSuccs = g.registrar.getSucc(pp);
            List<InterProgramPointReplica> l = new ArrayList<>(ppSuccs.size());
            for (ProgramPoint succ : ppSuccs) {
                l.add(InterProgramPointReplica.create(context, succ.pre()));
            }
            return l;
        }
        else {
            throw new IllegalArgumentException("Don't know about this kind of interprogrampoint");
        }
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
