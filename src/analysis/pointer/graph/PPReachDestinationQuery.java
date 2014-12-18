package analysis.pointer.graph;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import util.OrderedPair;
import util.WorkQueue;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.graph.ProgramPointReachability.SubQuery;
import analysis.pointer.graph.ProgramPointReachabilityNew.ReachabilityResult;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;
import analysis.pointer.statements.ProgramPoint.PostProgramPoint;
import analysis.pointer.statements.ProgramPoint.PreProgramPoint;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.intset.IntSet;

protected class PPReachDestinationQuery {
    final InterProgramPointReplica destination;
    final/*Set<PointsToGraphNode>*/IntSet noKill;
    final/*Set<InstanceKeyRecency>*/IntSet noAlloc;
    final Set<InterProgramPointReplica> forbidden;
    final PointsToGraph g;
    final ProgramPointReachability ppr;

    WorkQueue<WorkItem> globalQ = new WorkQueue<>();

    PPReachDestinationQuery(InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill,
    /*Set<InstanceKeyRecency>*/IntSet noAlloc, Set<InterProgramPointReplica> forbidden,
 PointsToGraph g,
                            ProgramPointReachability ppr) {
        this.destination = destination;
        this.noKill = noKill;
        this.noAlloc = noAlloc;
        this.forbidden = forbidden;
        this.g = g;
        this.ppr = ppr;
    }

    private static class WorkItem {
        final InterProgramPointReplica src;
        final boolean isFromCallSite; // XXX DOCO TODO: if true, src should be an entry of a method, and ...

        public WorkItem(InterProgramPointReplica src, boolean isFromCallSite) {
            this.src = src;
            this.isFromCallSite = isFromCallSite;
        }
    }

    Set<OrderedPair<IMethod, Context>> relevantNodes;
    SubQuery currentSubQuery = null;

    public boolean query(InterProgramPointReplica src, Set<OrderedPair<IMethod, Context>> relevantNodes) {
        this.currentSubQuery = new SubQuery(src, this.destination, this.noKill, this.noAlloc, this.forbidden);
        this.relevantNodes = relevantNodes;
        globalQ.add(new WorkItem(src, false));
        while (!globalQ.isEmpty()) {
            WorkItem wi = globalQ.poll();
            if (search(wi)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Search within the method of wi.src for the destination. As this encounters call sites and method exits, it may
     * add to the global queue.
     *
     * @param wi
     * @return
     */
    private boolean search(WorkItem wi) {
        InterProgramPointReplica src = wi.src;
        IMethod currentMethod = src.getContainingProcedure();
        Context currentContext = src.getContext();
        OrderedPair<IMethod, Context> currentCallGraphNode = new OrderedPair<>(currentMethod, currentContext);

        // Is the destination node in the same node as the source
        boolean inSameMethod = this.destination.getContainingProcedure().equals(currentMethod);

        // try searching forward from src, carefully handling calls.
        Deque<InterProgramPointReplica> q = new ArrayDeque<>();
        // Program points to delay until after we search other paths
        Deque<InterProgramPointReplica> delayedQ = new ArrayDeque<>();

        Set<InterProgramPointReplica> visited = new HashSet<>();

        // Record the exits that are reachable within the method containing the source
        ReachabilityResult reachableExits = ReachabilityResult.UNREACHABLE;

        boolean onDelayed = false; // are we processing the delayed nodes?
        q.add(src);
        while (!q.isEmpty() || !delayedQ.isEmpty()) {
            onDelayed |= q.isEmpty();
            InterProgramPointReplica ippr = q.isEmpty() ? delayedQ.poll() : q.poll();
            assert (ippr.getContainingProcedure().equals(currentMethod)) : "All nodes for a single search should be ";
            if (ippr.equals(this.destination)) {
                // Found it!
                return true;
            }

            if (this.forbidden.contains(ippr)) {
                // prune this!
                continue;
            }

            InterProgramPoint ipp = ippr.getInterPP();
            ProgramPoint pp = ipp.getPP();

            if (ipp instanceof PreProgramPoint) {
                if (pp instanceof CallSiteProgramPoint) {

                    CallSiteProgramPoint cspp = (CallSiteProgramPoint) pp;

                    // Special handling for static class initializers
                    if (cspp.isClinit()) {
                        // Whether the class initializer has been processed
                        boolean hasBeenAdded;
                        if (g.getClassInitializers() != null) {
                            // Still constructing the call graph
                            hasBeenAdded = g.getClassInitializers().contains(cspp.getClinit());
                        }
                        else {
                            try {
                                Context c = g.getHaf().initialContext();
                                CGNode clinitNode = g.getCallGraph().findOrCreateNode(cspp.getClinit(), c);
                                hasBeenAdded = g.getCallGraph().getEntrypointNodes().contains(clinitNode);
                            }
                            catch (CancelException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        if (!hasBeenAdded) {
                            // This is a class initializer that has not been added to the call graph yet.
                            // Add the post program point anyway. This is potentially imprecise, but sound.

                            // We do this since some of the clinit program points are for classes that we
                            // (imprecisely) think could be initialized (based on type information) before starting
                            // the points-to analysis, but once we have more precise information it turns out
                            // they never can be initialized.

                            InterProgramPointReplica post = pp.post().getReplica(currentContext);
                            if (visited.add(post)) {
                                q.add(post);
                            }

                            // No need to process, there will be no callees
                            continue;
                        }
                    }

                    // This is a program point for a method call
                    if (inSameMethod && !onDelayed) {
                        if (visited.add(ippr)) {
                            delayedQ.add(ippr);
                        }
                        continue;
                    }

                    ReachabilityResult res = handleCall(ippr, wi);
                    if (res == ReachabilityResult.FOUND) {
                        return true;
                    }
                    // We just analyzed a call, add the post for the caller if we found any exits
                    if (res.containsNormalExit()) {
                        InterProgramPointReplica post = cspp.post().getReplica(currentContext);
                        if (visited.add(post)) {
                            q.add(post);
                        }
                    }
                    if (res.containsExceptionExit()) {
                        InterProgramPointReplica post = cspp.getExceptionExit().post().getReplica(currentContext);
                        if (visited.add(post)) {
                            q.add(post);
                        }
                    }
                    continue;
                }
                else if (pp.isNormalExitSummaryNode() || pp.isExceptionExitSummaryNode()) {

                    if (wi.isFromCallSite) {
                        // This is from a known call-site, the return to the caller is handled at the call-site in "handleCall"

                        // Record the exit
                        if (pp.isNormalExitSummaryNode()) {
                            reachableExits.join(ReachabilityResult.NORMAL_EXIT);
                        }

                        if (pp.isExceptionExitSummaryNode()) {
                            reachableExits.join(ReachabilityResult.EXCEPTION_EXIT);
                        }
                        continue;
                    }

                    if (inSameMethod && !onDelayed) {
                        assert !wi.isFromCallSite;
                        if (visited.add(ippr)) {
                            delayedQ.add(ippr);
                        }
                        continue;
                    }

                    // We do not know the call-site we are returning to perform searches from all possible caller-sites
                    boolean found = handleMethodExitToUnknownCallSite(currentCallGraphNode,
                                                                      pp.isExceptionExitSummaryNode());
                    if (found) {
                        return true;
                    }

                    continue;
                }
                PointsToStatement stmt = g.getRegistrar().getStmtAtPP(pp);
                // not a call or a return, it's just a normal statement.
                // does ipp kill this.node?
                if (stmt != null && handlePossibleKill(stmt, currentContext)) {
                    continue;
                }
                // Path was not killed add the post PP for the pre PP
                InterProgramPointReplica post = pp.post().getReplica(currentContext);
                if (visited.add(post)) {
                    q.add(post);
                }
            } // end of "pre" program point handling
            else if (ipp instanceof PostProgramPoint) {
                Set<ProgramPoint> ppSuccs = pp.succs();
                for (ProgramPoint succ : ppSuccs) {
                    InterProgramPointReplica succIPPR = succ.pre().getReplica(currentContext);
                    if (visited.add(succIPPR)) {
                        q.add(succIPPR);
                    }
                }
            }
            else {
                throw new IllegalArgumentException("Don't know about this kind of interprogrampoint");
            }
        } // end of queue


        // we didn't find it

        // XXX RECORD CALL RESULTS
        XXX;

        return false;

    }



    /**
     * Handle a "pre" program point that may kill the current path.
     *
     * @param stmt points-to graph statement for the current program point
     * @param currentContext current context
     * @param query query being executed
     * @return whether the path was killed
     */
    private boolean handlePossibleKill(PointsToStatement stmt, Context currentContext) {
        SubQuery query = this.currentSubQuery;
        OrderedPair<Boolean, PointsToGraphNode> killed = stmt.killsNode(currentContext, g);
        if (killed != null) {
            if (!killed.fst()) {
                // not enough info available yet.
                // add a depedency since more information may change this search
                ppr.addKillDependency(query, stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
                // for the moment, assume conservatively that this statement
                // may kill a field we are interested in.
                return true;
            }
            else if (killed.snd() != null && query.noKill.contains(g.lookupDictionary(killed.snd()))) {
                // dang! we killed something we shouldn't. Prune the search.
                // add a depedency in case this changes in the future.
                ppr.addKillDependency(query, stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
                return true;
            }
            else if (killed.snd() == null) {
                // we have enough information to know that this statement does not kill a node we care about
                ppr.removeKillDependency(query, stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
            }
            // we have enough information to determine whether this statement kills a field, and it does not
            // kill anything we care about. So we can continue with the search.
            assert killed.fst() && (killed.snd() == null || !query.noKill.contains(g.lookupDictionary(killed.snd())));
        }

        // is "to" allocated at this program point?
        InstanceKeyRecency justAllocated = stmt.justAllocated(currentContext, g);
        if (justAllocated != null) {
            assert justAllocated.isRecent();
            int justAllocatedKey = g.lookupDictionary(justAllocated);
            if (g.isMostRecentObject(justAllocatedKey) && g.isTrackingMostRecentObject(justAllocatedKey)
                    && query.noAlloc.contains(g.lookupDictionary(justAllocated))) {
                // dang! we killed allocated we shouldn't. Prune the search.
                return true;
            }
        }
        return false;
    }

}
