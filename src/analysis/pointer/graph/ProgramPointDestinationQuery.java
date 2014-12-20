package analysis.pointer.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import util.WorkQueue;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.graph.ProgramPointReachability.MethodSummaryKillAndAlloc;
import analysis.pointer.registrar.MethodSummaryNodes;
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

/**
 * Class which manages several queries with the same destination, no kill set, no alloc set, and set of forbidden nodes
 */
class ProgramPointDestinationQuery {

    // Global fields
    /**
     * program point to find
     */
    private final InterProgramPointReplica dest;
    /**
     * points-to graph nodes that must not be killed on a valid path from source to destination
     */
    private final/*Set<PointsToGraphNode>*/IntSet noKill;
    /**
     * instance key that must not be allocated on a valid path from source to destination
     */
    private final/*Set<InstanceKeyRecency>*/IntSet noAlloc;
    /**
     * program points that must not be traversed on a valid path from source to destination
     */
    private final Set<InterProgramPointReplica> forbidden;
    /**
     * points-to graph
     */
    private final PointsToGraph g;
    /**
     * thread-safe class which manages dependencies and the submission of queries by the points-to analysis
     */
    private final ProgramPointReachability ppr;

    // these are not global and are reset for each subquery
    /**
     * Set of call graph nodes that are relevent to the current subquery
     */
    private Set<OrderedPair<IMethod, Context>> relevantNodes;
    /**
     * Sub-query that is currently being processed
     */
    private ProgramPointSubQuery currentSubQuery;
    /**
     * Cache of results for the current subquery
     */
    private Map<WorkItem, ReachabilityResult> resultCache;
    /**
     * Work items that are curently being processed
     */
    private Set<WorkItem> currentlyProcessing;
    /**
     * Map from work item, w, to the set of work items that must be recomputed if the results for "w" changes
     */
    private Map<WorkItem, Set<WorkItem>> workItemDependencies;

    /**
     * Work queue for dealing with a single source query
     */
    private WorkQueue<WorkItem> searchQ = new WorkQueue<>();

    /**
     * Query engine that can be reused for multiple sources
     *
     * @param destination program point to find
     * @param noKill points-to graph nodes that must not be killed on a valid path from source to destination
     * @param noAlloc instance key that must not be allocated on a valid path from source to destination
     * @param forbidden program points that must not be traversed on a valid path from source to destination
     * @param g points-to graph
     * @param ppr thread-safe class which manages dependencies and the submission of queries by the points-to analysis
     */
    ProgramPointDestinationQuery(InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill,
    /*Set<InstanceKeyRecency>*/IntSet noAlloc, Set<InterProgramPointReplica> forbidden, PointsToGraph g,
                                 ProgramPointReachability ppr) {
        this.dest = destination;
        this.noKill = noKill;
        this.noAlloc = noAlloc;
        this.forbidden = forbidden;
        this.g = g;
        this.ppr = ppr;
    }

    /**
     * Work items stored in the global queue and used to search within a single method
     */
    private static class WorkItem {
        final InterProgramPointReplica src;
        // If true then the source is the entry program point for a method that came from a known call-site,
        //      from which the search will continue when an exit program point is found
        // If false then when the search finds an exit program point it must continue from all possible call-sites
        final boolean isFromCallSite;

        public WorkItem(InterProgramPointReplica src, boolean isFromCallSite) {
            assert !isFromCallSite || src.getInterPP().getPP().isEntrySummaryNode();
            this.src = src;
            this.isFromCallSite = isFromCallSite;
        }
    }

    /**
     * Execute a subquery for a single source
     *
     * @param src source to search from
     * @param relevantNodes call graph nodes that must be inspected and cannot be summarized
     * @return whether the destination was found
     */
    public boolean executeSubQuery(InterProgramPointReplica src, Set<OrderedPair<IMethod, Context>> relevantNodes) {
        this.currentSubQuery = new ProgramPointSubQuery(src, this.dest, this.noKill, this.noAlloc, this.forbidden);
        this.relevantNodes = relevantNodes;

        // Reinitialize the per-query data structures
        this.currentlyProcessing = new HashSet<>();
        this.resultCache = new HashMap<>();
        this.workItemDependencies = new HashMap<>();

        searchQ.add(new WorkItem(src, false));
        while (!searchQ.isEmpty()) {
            WorkItem wi = searchQ.poll();
            if (search(wi)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Search within the call graph node of wi.src for the destination. As this encounters call sites and method exits,
     * it may add to the search queue.
     *
     * @param wi search start program point and whether this search is from a call site
     *
     * @return true if the destination was found
     */
    private boolean search(WorkItem wi) {
        InterProgramPointReplica src = wi.src;
        IMethod currentMethod = src.getContainingProcedure();
        Context currentContext = src.getContext();
        OrderedPair<IMethod, Context> currentCGNode = new OrderedPair<>(currentMethod, currentContext);

        // Is the destination node in the same node as the source
        boolean inSameMethod = this.dest.getContainingProcedure().equals(currentMethod);

        // try searching forward from src, carefully handling calls.
        WorkQueue<InterProgramPointReplica> q = new WorkQueue<>();
        // Program points to delay until after we search other paths
        WorkQueue<InterProgramPointReplica> delayedQ = new WorkQueue<>();

        Set<InterProgramPointReplica> visited = new HashSet<>();

        // Record the exits that are reachable within the method containing the source
        ReachabilityResult reachableExits = ReachabilityResult.UNREACHABLE;

        boolean onDelayed = false; // are we processing the delayed nodes?
        q.add(src);
        while (!q.isEmpty() || !delayedQ.isEmpty()) {
            onDelayed |= q.isEmpty();
            // pull from the regular queue first
            InterProgramPointReplica ippr = q.isEmpty() ? delayedQ.poll() : q.poll();
            assert (ippr.getContainingProcedure().equals(currentMethod)) : "All nodes for a single search should be ";
            if (ippr.equals(this.dest)) {
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
                    boolean found = handleMethodExitToUnknownCallSite(currentCGNode,
                                                                      pp.isExceptionExitSummaryNode(),
                                                                      wi);
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
        // RECORD CALL RESULTS
        recordResults(wi, reachableExits);
        return false;

    }

    /**
     * Handle a method call, get the results of searching all possible callees
     *
     * @param ippr call site program point replica
     * @param trigger search that requires the results for this call-site
     *
     * @return the results of searching through all the possible callees
     */
    private ReachabilityResult handleCall(InterProgramPointReplica ippr, WorkItem trigger) {
        CallSiteProgramPoint pp = (CallSiteProgramPoint) ippr.getInterPP().getPP();

        // This is a method call! Register the dependency.
        ppr.addCalleeDependency(this.currentSubQuery, pp.getReplica(ippr.getContext()));

        Set<OrderedPair<IMethod, Context>> calleeSet = g.getCalleesOf(pp.getReplica(ippr.getContext()));

        // The exit nodes that are reachable from this call-site, initialize to UNREACHABLE
        ReachabilityResult reachableExits = ReachabilityResult.UNREACHABLE;
        for (OrderedPair<IMethod, Context> callee : calleeSet) {
            MethodSummaryNodes calleeSummary = g.getRegistrar().getMethodSummary(callee.fst());
            InterProgramPointReplica calleeEntryIPPR = calleeSummary.getEntryPP().post().getReplica(callee.snd());
            ReachabilityResult res = ReachabilityResult.UNREACHABLE;
            if (relevantNodes.contains(callee)) {
                // this is a relevant node get the results for the callee
                res = getResults(calleeEntryIPPR, true, trigger);
                if (res == ReachabilityResult.FOUND) {
                    return ReachabilityResult.FOUND;
                }
                reachableExits.join(res);
            }

            // If both exit types are not already accounted for then get summary results for the irrelevent callee
            else if (reachableExits != ReachabilityResult.NORMAL_AND_EXCEPTION_EXIT) {
                ppr.addMethodDependency(this.currentSubQuery, callee);
                MethodSummaryKillAndAlloc calleeResults = ppr.getReachabilityForMethod(callee.fst(), callee.snd());
                KilledAndAlloced normalRet = calleeResults.getResult(calleeEntryIPPR,
                                                                     calleeSummary.getNormalExitPP()
                                                                                  .pre()
                                                                                  .getReplica(callee.snd()));
                KilledAndAlloced exRet = calleeResults.getResult(calleeEntryIPPR,
                                                                 calleeSummary.getExceptionExitPP()
                                                                              .pre()
                                                                              .getReplica(callee.snd()));

                if (normalRet.allows(this.noKill, this.noAlloc, this.g)) {
                    // we don't kill things we aren't meant to, not allocated things we aren't meant to
                    //    on a path to the normal exit of the callee
                    reachableExits.join(ReachabilityResult.NORMAL_EXIT);
                }

                if (exRet.allows(this.noKill, this.noAlloc, this.g)) {
                    // we don't kill things we aren't meant to, not allocated things we aren't meant to
                    //    on a path to the exceptional exit of the callee
                    reachableExits.join(ReachabilityResult.EXCEPTION_EXIT);
                }
            }
        }
        return reachableExits;
    }

    /**
     * Handle a "pre" program point that may kill the current path.
     *
     * @param stmt points-to graph statement for the current program point
     * @param currentContext analysis context for the call graph node currently being searched
     */
    private boolean handlePossibleKill(PointsToStatement stmt, Context currentContext) {
        OrderedPair<Boolean, PointsToGraphNode> killed = stmt.killsNode(currentContext, g);
        if (killed != null) {
            if (!killed.fst()) {
                // not enough info available yet.
                // add a depedency since more information may change this search
                ppr.addKillDependency(this.currentSubQuery,
                                      stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
                // for the moment, assume conservatively that this statement
                // may kill a field we are interested in.
                return true;
            }
            else if (killed.snd() != null && noKill.contains(g.lookupDictionary(killed.snd()))) {
                // dang! we killed something we shouldn't. Prune the search.
                // add a depedency in case this changes in the future.
                ppr.addKillDependency(this.currentSubQuery,
                                      stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
                return true;
            }
            else if (killed.snd() == null) {
                // we have enough information to know that this statement does not kill a node we care about
                ppr.removeKillDependency(this.currentSubQuery,
                                         stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
            }
            // we have enough information to determine whether this statement kills a field, and it does not
            // kill anything we care about. So we can continue with the search.
            assert killed.fst() && (killed.snd() == null || !noKill.contains(g.lookupDictionary(killed.snd())));
        }

        // is "to" allocated at this program point?
        InstanceKeyRecency justAllocated = stmt.justAllocated(currentContext, g);
        if (justAllocated != null) {
            assert justAllocated.isRecent();
            int justAllocatedKey = g.lookupDictionary(justAllocated);
            if (g.isMostRecentObject(justAllocatedKey) && g.isTrackingMostRecentObject(justAllocatedKey)
                    && noAlloc.contains(g.lookupDictionary(justAllocated))) {
                // dang! we killed allocated we shouldn't. Prune the search.
                return true;
            }
        }
        return false;
    }

    /**
     * Handle a the exit from a method when the precise call-site program point of the caller is unknown, check whether
     * the destination is reachable from the exit (by checking all possible callers)
     *
     * @param currentCallGraphNode call graph node we are exiting
     * @param isExceptionExit whether the method is exiting via exception
     * @param src source of the work item currently being processed
     * @return true if the destination program point was found
     */
    private boolean handleMethodExitToUnknownCallSite(OrderedPair<IMethod, Context> currentCallGraphNode,
                                                      boolean isExceptionExit, WorkItem src) {
        // Register a dependency i.e., the query may need to be rerun if a new caller is added
        ppr.addCallerDependency(this.currentSubQuery, currentCallGraphNode);

        Set<OrderedPair<CallSiteProgramPoint, Context>> callers = g.getCallersOf(currentCallGraphNode);
        if (callers == null) {
            // no callers
            return false;
        }

        for (OrderedPair<CallSiteProgramPoint, Context> callerSite : callers) {
            CallSiteProgramPoint cspp = callerSite.fst();
            OrderedPair<IMethod, Context> caller = new OrderedPair<>(callerSite.fst().getContainingProcedure(),
                                                                     callerSite.snd());
            if (relevantNodes.contains(caller)) {
                // this is a relevant node, and we need to dig into it.
                InterProgramPointReplica callerSiteReplica;
                if (isExceptionExit) {
                    callerSiteReplica = cspp.getExceptionExit().post().getReplica(callerSite.snd());
                }
                else {
                    callerSiteReplica = cspp.post().getReplica(callerSite.snd());
                }

                // let's explore the caller now.
                ReachabilityResult res = getResults(callerSiteReplica, false, src);
                if (res == ReachabilityResult.FOUND) {
                    // we found the destination!
                    return true;
                }
            }
            else {
                // not a relevant node, so no need to pursue it.
            }
        }
        // Not found
        return false;
    }

    /**
     * Get the result of running a query starting at newSrc
     *
     * @param newSrc source to get results for
     * @param isKnownCallSite whether the query starting at <code>newSrc</code> was triggered by a call from a known
     *            call-site
     * @param trigger source of the query for which the results from newSrc are needed
     * @return
     */
    private ReachabilityResult getResults(InterProgramPointReplica newSrc, boolean isKnownCallSite, WorkItem trigger) {
        WorkItem newWI = new WorkItem(newSrc, isKnownCallSite);
        ReachabilityResult res = resultCache.get(newWI);
        if (res != null) {
            return res;
        }

        // Set the default results
        resultCache.put(newWI, ReachabilityResult.UNREACHABLE);

        if (currentlyProcessing.contains(newWI)) {
            // add this work item to the queue for reprocessing and return the default value
            searchQ.add(newWI);
            return ReachabilityResult.UNREACHABLE;
        }

        if (search(newWI)) {
            // The destination was found
            resultCache.put(newWI, ReachabilityResult.FOUND);
            return ReachabilityResult.FOUND;
        }

        // make sure to reprocess the trigger if the results for the requested search change
        addDependency(trigger, newWI);

        return resultCache.get(newWI);
    }

    /**
     * If the results of the depencency search changes then the query <code>toRerun</code> needs to be re-executed
     *
     * @param toRerun dependant query
     * @param dependency query that triggers re-execution on change
     */
    private void addDependency(WorkItem toRerun, WorkItem dependency) {
        Set<WorkItem> s = workItemDependencies.get(dependency);
        if (s == null) {
            s = new LinkedHashSet<>();
            workItemDependencies.put(dependency, s);
        }
        s.add(toRerun);
    }

    /**
     * Record the results of running the query described by the work item, wi
     *
     * @param wi work item to record the results for
     * @param res new results for wi
     */
    private void recordResults(WorkItem wi, ReachabilityResult res) {
        ReachabilityResult previous = resultCache.put(wi, res);
        assert previous != null : "null results for " + wi;
        if (previous != res && workItemDependencies.containsKey(wi)) {
            // result changed, add any dependencies back onto the queue
            Set<WorkItem> deps = workItemDependencies.get(wi);
            for (WorkItem dep : deps) {
                searchQ.add(dep);
            }
        }
    }

    /**
     * Exits that can be reached from the source node of a reachablity query. Values of this Enum form a lattice with
     * the following structure.
     *
     * <pre>
     *           FOUND
     *             |
     *   NORMAL_AND_EXCEPTION_EXIT
     *         /         \
     * NORMAL_EXIT    EXCEPTION_EXIT
     *         \         /
     *         UNREACHABLE
     * </pre>
     */
    private static enum ReachabilityResult {
        /**
         * No exits are reachable
         */
        UNREACHABLE,
        /**
         * The destination was found
         */
        FOUND,
        /**
         * The normal exit program point is reachable
         */
        NORMAL_EXIT,
        /**
         * The exception exit program point is reachable
         */
        EXCEPTION_EXIT,
        /**
         * Both the exception exit and normal exit program points are reachable
         */
        NORMAL_AND_EXCEPTION_EXIT;

        /**
         * This is a lattice, join this with the given result
         *
         * <pre>
         *           FOUND
         *             |
         *   NORMAL_AND_EXCEPTION_EXIT
         *         /         \
         * NORMAL_EXIT    EXCEPTION_EXIT
         *         \         /
         *         UNREACHABLE
         *
         * </pre>
         *
         * @param other result to join with this one
         * @return The the result of joining the this with other
         */
        ReachabilityResult join(ReachabilityResult other) {
            assert other != null;
            if (this == other) {
                return this;
            }
            if (this == FOUND || other == FOUND) {
                return FOUND;
            }
            if (this == NORMAL_AND_EXCEPTION_EXIT || other == NORMAL_AND_EXCEPTION_EXIT) {
                return NORMAL_AND_EXCEPTION_EXIT;
            }
            if (this == UNREACHABLE) {
                return other;
            }
            if (other == UNREACHABLE) {
                return this;
            }

            if ((this == NORMAL_EXIT && other == EXCEPTION_EXIT) || (other == NORMAL_EXIT && this == EXCEPTION_EXIT)) {
                return NORMAL_AND_EXCEPTION_EXIT;
            }
            throw new RuntimeException("Forgot a combination " + this + ".join(" + other + ")");
        }

        /**
         * Can a normal exit be reached?
         *
         * @return whether a normal exit (from one of the callees) is reachable from the call-site
         */
        boolean containsNormalExit() {
            if (this == NORMAL_EXIT || this == NORMAL_AND_EXCEPTION_EXIT) {
                return true;
            }
            return false;
        }

        /**
         * Can an exceptional exit be reached?
         *
         * @return whether an exceptional exit (from one of the callees) is reachable from the call-site
         */
        boolean containsExceptionExit() {
            if (this == EXCEPTION_EXIT || this == NORMAL_AND_EXCEPTION_EXIT) {
                return true;
            }
            return false;
        }
    }
}