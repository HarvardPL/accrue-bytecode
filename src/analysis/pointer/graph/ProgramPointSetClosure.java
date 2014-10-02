package analysis.pointer.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Context;

import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;
import analysis.pointer.statements.ProgramPoint.PostProgramPoint;
import analysis.pointer.statements.ProgramPoint.PreProgramPoint;

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

    private final/*PointsToGraphNode*/int node;

    public ProgramPointSetClosure(/*PointsToGraphNode*/int node) {
        this.sources = PointsToAnalysisMultiThreaded.makeConcurrentSet();
        this.node = node;
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
        assert (toAdd.node == this.node);
        return this.sources.addAll(toAdd.sources);
    }


    /**
     * Does this set contain of the program points ippr?
     */
    public boolean contains(InterProgramPointReplica ippr) {
        // do a depth first search from each of the sources, to see if ippr can be reached.

        Set<InterProgramPointReplica> visited = new HashSet<>();
        ArrayList<InterProgramPointReplica> stack = new ArrayList<>();

        for (InterProgramPointReplica src : this.sources) {
            // do a depth first search from src
            if (dfs(src, stack, visited, ippr)) {
                return true;
            }
        }
        return false;

    }


    private boolean dfs(InterProgramPointReplica i, ArrayList<InterProgramPointReplica> stack,
                        Set<InterProgramPointReplica> visited, InterProgramPointReplica target, PointsToGraph g) {
        if (i.equals(target)) {
            return true;
        }
        if (visited.contains(i)) {
            return false;
        }
        visited.add(i);

        // push i onto the stack.
        stack.add(i);
        for (InterProgramPointReplica j : successors(i, g)) {
            if (dfs(j, stack, visited, target, g)) {
                return true;
            }
        }
        // remove i from the top of the stack.
        stack.remove(stack.size() - 1);
        return false;
    }

    private Collection<InterProgramPointReplica> successors(InterProgramPointReplica i, PointsToGraph g) {
        InterProgramPoint ipp = i.getInterPP();
        ProgramPoint pp = ipp.getPP();
        Context context = i.getContext();
        if (ipp instanceof PreProgramPoint) {
            if (pp instanceof CallSiteProgramPoint) {
                CallSiteProgramPoint cspp = (CallSiteProgramPoint) pp;
                HafCallGraph cg = g.getCallGraph();
                CallSiteReference ref = cspp.getReference();
                CGNode n = cg.findOrCreateNode(cspp.getCaller(),context);
                for(CGNode callee : cg.getPossibleTargets(n,ref)) {
                    callee.
                }

                return a list of the entry points to all the callees.
            }
            else if (pp.isNormalExitSummaryNode()) {
                return a list of all the normal returns of the call sties of the method
            }
            else if (pp.isExceptionExitSummaryNode()) {
                return a list of all the exceptional returns of the call sites of the method
            }
            else {
                // we are not a call or a return, it's just a normal statement.
                // does ipp kill this.node?
                boolean killsNode = node == g.lookupDictionary(pp.stmt().killed(context));
                if (killsNode) {
                    return Collections.emptyList();
                }
                else {
                    return Collections.singletonList(InterProgramPointReplica.create(i.getContext(), pp.post()));
                }
            }
        }
        else if (ipp instanceof PostProgramPoint) {
            Set<ProgramPoint> ppSuccs = ipp.getPP().successors();
            List<InterProgramPointReplica> l = new ArrayList<>(ppSuccs.size());
            for (ProgramPoint succ : ppSuccs) {
                l.add(InterProgramPointReplica.create(context, succ.pre()));
            }
            return l;
        }
        else {
            assert false: "Don't know about this kind of interprogrampoint";
        }
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
        assert this.node == pps.node;
        for (InterProgramPointReplica ippr : pps.sources) {
            if (!this.sources.contains(ippr) && !this.contains(ippr)) {
                return false;
            }
        }
        return true;
    }

}
