package analysis.pointer.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import util.OrderedPair;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;
import analysis.pointer.statements.ProgramPoint.PostProgramPoint;
import analysis.pointer.statements.ProgramPoint.PreProgramPoint;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntSet;

/**
 * This class answers questions about what programs are reachable from what other program points, and caches answers
 * smartly.
 */
public class ProgramPointReachability {
    private final PointsToGraph g;

    ProgramPointReachability(PointsToGraph g) {
        this.g = g;
    }
    /*
     * Can ippr be reached from any InterProgramPointReplica in sources without
     * going through a program point that kills any PointsToGraphNode in noKill, and
     * without going through a program point that allocates any InstanceKey in noAlloc?
     */
    public boolean reachable(Collection<InterProgramPointReplica> sources, InterProgramPointReplica ippr,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc) {
        // do a depth first search from each of the sources, to see if ippr can be reached.
        Set<InterProgramPointReplica> visited = new HashSet<>();

        for (InterProgramPointReplica src : sources) {
            // do a depth first search from src
            if (dfs(src, visited, ippr, this.g, noKill, noAlloc)) {
                return true;
            }
        }
        return false;

    }

    private boolean dfs(InterProgramPointReplica i, Set<InterProgramPointReplica> visited,
                        InterProgramPointReplica target, PointsToGraph g, IntSet noKill, IntSet noAlloc) {
        if (i.equals(target)) {
            return true;
        }
        if (visited.contains(i)) {
            return false;
        }
        visited.add(i);

        for (InterProgramPointReplica j : successors(i, g, noKill, noAlloc)) {
            if (dfs(j, visited, target, g, noKill, noAlloc)) {
                return true;
            }
        }
        return false;
    }

    private Collection<InterProgramPointReplica> successors(InterProgramPointReplica i, PointsToGraph g, IntSet noKill,
                                                            IntSet noAlloc) {
        InterProgramPoint ipp = i.getInterPP();
        ProgramPoint pp = ipp.getPP();
        Context context = i.getContext();
        if (ipp instanceof PreProgramPoint) {
            if (pp instanceof CallSiteProgramPoint) {
                OrderedPair<CallSiteProgramPoint, Context> caller = new OrderedPair<>((CallSiteProgramPoint) pp,
                                                                                      context);
                Set<OrderedPair<IMethod, Context>> calleeSet = g.getCallGraphMap().get(caller);
                if (calleeSet == null) {
                    return Collections.emptySet();
                }
                List<InterProgramPointReplica> l = new ArrayList<>();
                for (OrderedPair<IMethod, Context> callee : calleeSet) {
                    ProgramPoint entry = g.registrar.getMethodSummary(callee.fst()).getEntryPP();
                    l.add(InterProgramPointReplica.create(callee.snd(), entry.post()));
                }
                return l;
            }
            else if (pp.isNormalExitSummaryNode() || pp.isExceptionExitSummaryNode()) {
                // we will treat normal and exceptional exits the same. We could be
                // more precise if call sites had two different program points to return to, one for normal
                // returns, and one for exceptional returns.
                OrderedPair<IMethod, Context> callee = new OrderedPair<>(pp.containingProcedure(), context);
                Set<OrderedPair<CallSiteProgramPoint, Context>> callerSet = g.getCallGraphReverseMap().get(callee);
                if (callerSet == null) {
                    return Collections.emptySet();
                }
                List<InterProgramPointReplica> l = new ArrayList<>();
                for (OrderedPair<CallSiteProgramPoint, Context> caller : callerSet) {
                    l.add(InterProgramPointReplica.create(caller.snd(), caller.fst().post()));
                }
                return l;
            }
            else {
                PointsToStatement stmt = g.registrar.getStmtAtPP(pp);
                // not a call or a return, it's just a normal statement.
                // does ipp kill this.node?
                if (stmt != null) {
                    PointsToGraphNode killed = stmt.killed(context, g);
                    if (killed != null && noKill.contains(g.lookupDictionary(killed))) {
                        return Collections.emptyList();
                    }

                    // is "to" allocated at this program point?
                    InstanceKeyRecency justAllocated = stmt.justAllocated(context, g);
                    if (justAllocated != null) {
                        int/*InstanceKeyRecency*/justAllocatedKey = g.lookupDictionary(justAllocated);
                        if (noAlloc.contains(justAllocatedKey)) {
                            return Collections.emptyList();
                        }
                    }
                }

                return Collections.singletonList(InterProgramPointReplica.create(context, pp.post()));
            }
        }
        else if (ipp instanceof PostProgramPoint) {
            Set<ProgramPoint> ppSuccs = pp.succs();
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

}
