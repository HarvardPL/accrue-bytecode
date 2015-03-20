package analysis.pointer.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.MethodReachability.MethodSummaryKillAndAlloc;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.LocalToFieldStatement;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;
import analysis.pointer.statements.ProgramPoint.PostProgramPoint;
import analysis.pointer.statements.ProgramPoint.PreProgramPoint;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;

/**
 * Class which manages several queries with the same destination, no kill set, no alloc set, and set of forbidden nodes
 */
public final class ProgramPointDestinationQuery {

    /**
     * Print debug info
     */
    public static final boolean DEBUG = ProgramPointReachability.DEBUG;
    /**
     * Print more debug info
     */
    public static final boolean DEBUG2 = ProgramPointReachability.DEBUG2;

    /**
     * Should we use tunnels to the destination?
     */
    private static final boolean USE_TUNNELS = ProgramPointReachability.USE_TUNNELS;

    /////////////////////
    //
    //  Fields maintained across subqueries
    //
    ////////////////////
    /**
     * program point to find
     */
    private final InterProgramPointReplica dest;
    /**
     * Call graph node containing the destination program point
     */
    private final/*OrderedPair<IMethod,Context>*/int destinationCGNode;

    private final InterProgramPointReplica destinationEntryIPPR;

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

    /////////////////////
    //
    //  These fields are reset for each subquery
    //
    ////////////////////
    /**
     * Sub-query that is currently being processed
     */
    private int currentSubQuery;

    /**
     * Has the destination CG node entry program point been reached yet? If so, there is no need to explore callees, we
     * just really need to explore callers...
     */
    private boolean destCGNodeEntryReached;
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
    ProgramPointDestinationQuery(InterProgramPointReplica destination, /* Set<PointsToGraphNode> */IntSet noKill,
    /* Set<InstanceKeyRecency> */IntSet noAlloc, Set<InterProgramPointReplica> forbidden, PointsToGraph g,
                                 ProgramPointReachability ppr) {
        this.dest = destination;
        this.noKill = noKill;
        this.noAlloc = noAlloc;
        this.forbidden = forbidden;
        this.g = g;
        this.ppr = ppr;
        OrderedPair<IMethod, Context> destCGNode = new OrderedPair<>(dest.getContainingProcedure(), dest.getContext());
        this.destinationCGNode = g.lookupCallGraphNodeDictionary(destCGNode);
        MethodSummaryNodes destinationSummary = g.getRegistrar().getMethodSummary(dest.getContainingProcedure());
        this.destinationEntryIPPR = destinationSummary.getEntryPP().post().getReplica(dest.getContext());

    }

    /**
     * Execute a subquery for a single source
     *
     * @param src source to search from
     * @param relevantNodes call graph nodes that must be inspected and cannot be summarized
     * @return whether the destination was found
     */
    public boolean executeSubQuery(InterProgramPointReplica src) {
        if (DEBUG) {
            System.err.println("PPDQ%% \nEXECUTING " + this.currentSubQuery);
            IntIterator iter = this.noAlloc.intIterator();
            System.err.println("PPDQ%% NO ALLOC: ");
            while (iter.hasNext()) {
                int i = iter.next();
                System.err.println("PPDQ%% \t" + i + " " + g.lookupInstanceKeyDictionary(i));
            }
            System.err.println("PPDQ%% NO KILL: ");
            iter = this.noKill.intIterator();
            while (iter.hasNext()) {
                int i = iter.next();
                System.err.println("PPDQ%% \t" + i + " " + g.lookupPointsToGraphNodeDictionary(i));
            }
        }

        // Reinitialize the per-query data structures
        this.destCGNodeEntryReached = false;
        this.currentlyProcessing = new HashSet<>();
        this.resultCache = new HashMap<>();
        this.workItemDependencies = new HashMap<>();

        //for (InterProgramPointReplica src : sources) {
            this.currentSubQuery = ProgramPointSubQuery.lookupDictionary(new ProgramPointSubQuery(src,
                                                                                                  this.dest,
                                                                                                  this.noKill,
                                                                                                  this.noAlloc,
                                                                                                  this.forbidden));
            searchQ.add(new WorkItem(src, false));
            while (!searchQ.isEmpty()) {
                WorkItem wi = searchQ.poll();
            if (search(wi)) {
                    if (DEBUG) {
                        System.err.println("PPDQ%% FOUND DESTINATION " + this.currentSubQuery + "\n");
                    }
                    return true;
                }
            }
        //}
        if (DEBUG) {
            System.err.println("PPDQ%% DID NOT FIND DEST " + this.currentSubQuery + "\n");
        }
        return false;
    }

    /**
     * Search within the call graph node of wi.src for the destination. As this encounters call sites and method exits,
     * it may add to the search queue.
     *
     * @param wi search start program point and whether this search is from a call site
     * @param visited Set of previously visited program points
     *
     * @return true if the destination was found
     */
    private boolean search(WorkItem wi /*Set<InterProgramPointReplica> visited*/) {
        if (DEBUG) {
            System.err.println("PPDQ%% \tSearching from " + wi + " in "
                    + PrettyPrinter.methodString(wi.src.getContainingProcedure()) + " in " + wi.src.getContext());
            System.err.println("PPDQ%% \t\tto " + dest + " in "
                    + PrettyPrinter.methodString(dest.getContainingProcedure())
                    + " in " + dest.getContext());
        }
        final InterProgramPointReplica src = wi.src;
        final IMethod currentMethod = src.getContainingProcedure();
        final Context currentContext = src.getContext();
        final/*OrderedPair<IMethod, Context>*/int currentCGNode = g.lookupCallGraphNodeDictionary(new OrderedPair<>(currentMethod,
                                                                                                                currentContext));

        // Is the destination node in the same node as the source
        final boolean inSameMethod = (currentCGNode == this.destinationCGNode);
        Set<InterProgramPoint> visited = new HashSet<>();

        // try searching forward from src, carefully handling calls.
        WorkQueue<InterProgramPoint> q = new WorkQueue<>();
        // Program points to delay until after we search other paths
        WorkQueue<InterProgramPoint> delayedQ = new WorkQueue<>();

        Set<InterProgramPoint> alreadyDelayed = new HashSet<>();

        // Record the exits that are reachable within the method containing the source
        ReachabilityResult reachableExits = ReachabilityResult.UNREACHABLE;

        // Have we added a dependency for currentSubQuery to be re-run when new
        // callees are added to currentCGNode?
        boolean addedCalleeDependency = false;

        // Have we added a dependency for currentSubQuery to be re-run when new
        // callers are added to currentCGNode?
        boolean addedCallerDependency = false;

        boolean onDelayed = false; // are we processing the delayed nodes?
        q.add(src.getInterPP());
        while (!q.isEmpty() || !delayedQ.isEmpty()) {
            onDelayed |= q.isEmpty();
            boolean isDelayed = q.isEmpty();
            boolean anyDelayed = !delayedQ.isEmpty();

            // pull from the regular queue first
            InterProgramPoint ipp = q.isEmpty() ? delayedQ.poll() : q.poll();
            if (DEBUG) {
                System.err.println("PPDQ%% \t\tQ " + ipp + " isDelayed? " + isDelayed + " anyDelayed? " + anyDelayed
                        + " " + ipp.getClass());
            }
            assert (ipp.getPP().getContainingProcedure().equals(currentMethod)) : "All nodes for a single search should be ";
            if (inSameMethod && ipp.equals(this.dest.getInterPP())) {
                // Found it!
                return true;
            }

            if (!this.forbidden.isEmpty() && this.forbidden.contains(ipp.getReplica(currentContext))) {
                // prune this!
                continue;
            }

            ProgramPoint pp = ipp.getPP();

            if (ipp instanceof PreProgramPoint) {
                if (pp instanceof CallSiteProgramPoint) {

                    if (DEBUG2) {
                        System.err.println("PPDQ%% \t\t\tCALL SITE: " + ipp);
                    }
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

                            InterProgramPoint post = pp.post();
                            if (visited.add(post)) {
                                q.add(post);
                            }

                            // No need to process, there will be no callees
                            continue;
                        }
                    }

                    // This is a program point for a method call
                    if (inSameMethod && !onDelayed) {
                        if (DEBUG2) {
                            System.err.println("PPDQ%% \t\t\tDelayed: " + pp + " inSameMethod? " + inSameMethod);
                        }
                        if (alreadyDelayed.add(ipp)) {
                            delayedQ.add(ipp);
                        }
                        continue;
                    }

                    if (!addedCalleeDependency) {
                        // we haven't already registered the dependency, so do so now.
                        addedCalleeDependency = true;
                        ppr.addCalleeQueryDependency(currentSubQuery, currentCGNode);
                    }
                    ReachabilityResult res = handleCall(ipp, currentContext, wi);
                    if (res == ReachabilityResult.FOUND) {
                        return true;
                    }
                    // We just analyzed a call, add the post for the caller if we found any exits
                    if (res.containsNormalExit()) {
                        InterProgramPoint post = cspp.getNormalExit().post();
                        if (visited.add(post)) {
                            q.add(post);
                        }
                    }
                    if (res.containsExceptionExit()) {
                        InterProgramPoint post = cspp.getExceptionExit().post();
                        if (visited.add(post)) {
                            q.add(post);
                        }
                    }
                    continue;
                }
                else if (pp.isNormalExitSummaryNode() || pp.isExceptionExitSummaryNode()) {
                    if (DEBUG2) {
                        System.err.println("PPDQ%% \t\t\tEXIT: " + pp + " isFromCallSite? " + wi.isFromCallSite);
                    }
                    if (wi.isFromCallSite) {
                        // This is from a known call-site, the return to the caller is handled at the call-site in "handleCall"

                        // Record the exit
                        if (pp.isNormalExitSummaryNode()) {
                            reachableExits = reachableExits.join(ReachabilityResult.NORMAL_EXIT);
                        }

                        if (pp.isExceptionExitSummaryNode()) {
                            reachableExits = reachableExits.join(ReachabilityResult.EXCEPTION_EXIT);
                        }
                        continue;
                    }

                    if (inSameMethod && !onDelayed) {
                        assert !wi.isFromCallSite;
                        if (DEBUG2) {
                            System.err.println("PPDQ%% \t\t\tDelayed: " + pp + " inSameMethod? " + inSameMethod);
                        }
                        if (alreadyDelayed.add(ipp)) {
                            delayedQ.add(ipp);
                        }
                        continue;
                    }

                    // We do not know the call-site we are returning to perform searches from all possible caller-sites

                    // Register a dependency i.e., the query may need to be rerun if a new caller is added
                    if (!addedCallerDependency) {
                        // we haven't already registered the dependency, so do so now.
                        addedCallerDependency = true;
                        ppr.addCallerQueryDependency(this.currentSubQuery, currentCGNode);
                    }

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
                    if (DEBUG2) {
                        System.err.println("PPDQ%% \t\t\tKILLED by: " + stmt + " type: " + stmt.getClass());
                    }
                    continue;
                }

                // Path was not killed add the post PP for the pre PP
                InterProgramPoint post = pp.post();
                if (DEBUG2) {
                    System.err.println("PPDQ%% \t\t\tNOT KILLED  by " + stmt + " adding post " + post);
                }
                if (visited.add(post)) {
                    q.add(post);
                }
            } // end of "pre" program point handling
            else if (ipp instanceof PostProgramPoint) {
                if (DEBUG2) {
                    System.err.println("PPDQ%% \t\t\tPOST: " + ipp);
                }
                Set<ProgramPoint> ppSuccs = pp.succs();
                for (ProgramPoint succ : ppSuccs) {
                    InterProgramPoint succIPP = succ.pre();
                    if (visited.add(succIPP)) {
                        q.add(succIPP);
                    }
                }
            }
            else {
                throw new IllegalArgumentException("Don't know about this kind of interprogrampoint");
            }
        } // end of queue

        // we didn't find it
        // RECORD CALL RESULTS
        if (DEBUG) {
            System.err.println("PPDQ%% \t" + reachableExits + " from " + wi);
        }
        recordResults(wi, reachableExits);
        return false;

    }

    /**
     * Handle a method call, get the results of searching all possible callees
     *
     * @param ippr call site program point replica
     * @param trigger search that requires the results for this call-site
     * @param visited
     *
     * @return the results of searching through all the possible callees
     */
    private ReachabilityResult handleCall(InterProgramPoint ipp, Context context, WorkItem trigger) {
        if (DEBUG) {
            System.err.println("PPDQ%% \t\t\tHANDLING CALL " + ipp + " " + context);
        }

        CallSiteProgramPoint pp = (CallSiteProgramPoint) ipp.getPP();

        /*ProgramPointReplica*/int callSite = g.lookupCallSiteReplicaDictionary(pp.getReplica(context));
        /*Set<OrderedPair<IMethod, Context>>*/IntSet calleeSet = g.getCalleesOf(callSite);
        if (DEBUG) {
            if (calleeSet.isEmpty()) {
                System.err.println("PPDQ%% \t\t\tNO CALLEES for " + pp);
            }
        }

        if (ppr.getApproximateCallSitesAndFieldAssigns().isApproximate(callSite)) {
            if (!calleeSet.isEmpty()) {
                if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
                    ProgramPointReachability.nonEmptyApproximatedCallSites.add(callSite);
                }
                if (PointsToAnalysis.outputLevel > 0) {
                    System.err.println("APPROXIMATING non-empty call site "
                            + g.lookupCallSiteReplicaDictionary(callSite));
                }
            }
            // This is a call site with no callees that we approximate by assuming it can terminate normally
            return ReachabilityResult.NORMAL_AND_EXCEPTION_EXIT;
        }


        // The exit nodes that are reachable from this call-site, initialize to UNREACHABLE
        ReachabilityResult reachableExits = ReachabilityResult.UNREACHABLE;
        IntIterator calleeIter = calleeSet.intIterator();
        while (calleeIter.hasNext()) {
            if (this.destCGNodeEntryReached && reachableExits == ReachabilityResult.NORMAL_AND_EXCEPTION_EXIT) {
                // we already can reach the destination call graph node, and we know that the normal and exceptional
                // exit for this call site can be reached, so don't bother continuing, we've got everything we can
                // from this call site...
                break;
            }
            /*OrderedPair<IMethod, Context>*/int calleeInt = calleeIter.next();
            OrderedPair<IMethod, Context> callee = g.lookupCallGraphNodeDictionary(calleeInt);
            MethodSummaryNodes calleeSummary = g.getRegistrar().getMethodSummary(callee.fst());
            long startCG = 0L;
            if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
                startCG = System.currentTimeMillis();
            }

            MethodSummaryKillAndAlloc calleeResults = this.ppr.getReachabilityForMethod(calleeInt);


            if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
                ppr.callGraphReachabilityTime.addAndGet(System.currentTimeMillis() - startCG);
            }
            if (USE_TUNNELS) {
                if (!this.destCGNodeEntryReached) {
                    // we haven't reached the destination CG node yet, so keep trying to find it.
                    KilledAndAlloced tunnelToDestination = calleeResults.tunnel.get(this.destinationCGNode);
                    if (tunnelToDestination == null || tunnelToDestination.isUnreachable()
                            || !tunnelToDestination.allows(this.noKill, this.noAlloc, this.g)) {
                        // need to record a dependency before reading a false result, in case the false result changes later.
                        // Otherwise, if the result is true, we don't need to be notified if it changes.
                        ppr.addMethodTunnelQueryDependency(this.currentSubQuery, calleeInt, this.destinationCGNode);
                        tunnelToDestination = calleeResults.tunnel.get(this.destinationCGNode);
                    }
                    if (tunnelToDestination != null && !tunnelToDestination.isUnreachable()
                            && tunnelToDestination.allows(this.noKill, this.noAlloc, this.g)) {
                        this.destCGNodeEntryReached = true;
                        if (getResults(destinationEntryIPPR, true, trigger) == ReachabilityResult.FOUND) {
                            return ReachabilityResult.FOUND;
                        }
                    }
                }
            }
            else {
                // NOT USING TUNNELS
                boolean destReachable = CallGraphReachability.USE_CALL_GRAPH_REACH
                        ? ppr.getCallGraphReachability().isReachable(calleeInt,
                                                                     this.destinationCGNode,
                                                                     this.currentSubQuery) : true;

                if (destReachable) {
                    // this is a relevant node get the results for the callee
                    InterProgramPointReplica calleeEntryIPPR = calleeSummary.getEntryPP()
                                                                            .post()
                                                                            .getReplica(callee.snd());
                    ReachabilityResult res = getResults(calleeEntryIPPR, true, trigger);
                    if (res == ReachabilityResult.FOUND) {
                        return ReachabilityResult.FOUND;
                    }
                    reachableExits = reachableExits.join(res);
                    // We've processed the callee, no need to use the summary information.
                    continue;
                }
            }
            if (reachableExits != ReachabilityResult.NORMAL_AND_EXCEPTION_EXIT) {
                // If both exit types are not already accounted for then get summary results for the callee that cannot
                // reach the destination
                KilledAndAlloced normalRet = calleeResults.getNormalExitResult();
                KilledAndAlloced exRet = calleeResults.getExceptionalExitResult();
                if (DEBUG) {
                    System.err.println("PPDQ%% \t\t\t\tUSING SUMMARY  Normal: "
                            + normalRet.allows(this.noKill, this.noAlloc, this.g) + " Ex: "
                            + exRet.allows(this.noKill, this.noAlloc, this.g));
                }
                boolean recordedDependency = false;
                if (!reachableExits.containsNormalExit()) {
                    if (!normalRet.allows(this.noKill, this.noAlloc, this.g)) {
                        // record a dependency before reading a false result.
                        ppr.addMethodResultQueryDependency(this.currentSubQuery, calleeInt);
                        recordedDependency = true;
                    }
                    if (normalRet.allows(this.noKill, this.noAlloc, this.g)) {
                        // we don't kill things we aren't meant to, not allocated things we aren't meant to
                        //    on a path to the normal exit of the callee
                        reachableExits = reachableExits.join(ReachabilityResult.NORMAL_EXIT);
                    }
                }
                if (!reachableExits.containsExceptionExit()) {
                    if (!recordedDependency && !exRet.allows(this.noKill, this.noAlloc, this.g)) {
                        // record a dependency before reading a false result.
                        ppr.addMethodResultQueryDependency(this.currentSubQuery, calleeInt);
                    }
                    if (exRet.allows(this.noKill, this.noAlloc, this.g)) {
                        // we don't kill things we aren't meant to, not allocated things we aren't meant to
                        //    on a path to the exceptional exit of the callee
                        reachableExits = reachableExits.join(ReachabilityResult.EXCEPTION_EXIT);
                    }
                }
            }
        }
        if (DEBUG2) {
            System.err.println("PPDQ%% \t\t\t\tfinished " + ipp + " " + context + " " + reachableExits);
        }
        return reachableExits;
    }

    public static Set<ProgramPointSubQuery> impreciseKills = AnalysisUtil.createConcurrentSet();

    /**
     * Handle a "pre" program point that may kill the current path.
     *
     * @param stmt points-to graph statement for the current program point
     * @param currentContext analysis context for the call graph node currently being searched
     */
    private boolean handlePossibleKill(PointsToStatement stmt, Context currentContext) {
        if (stmt.mayKillNode(currentContext, g)) {
            // record the dependency before we call stmt.killsNode
            ppr.addKillQueryDependency(this.currentSubQuery,
                                       stmt.getReadDependencyForKillField(currentContext, g.getHaf()));

            OrderedPair<Boolean, PointsToGraphNode> killed = stmt.killsNode(currentContext, g);
            if (killed != null) {

                if (ppr.getApproximateCallSitesAndFieldAssigns().isApproximateKillSet(stmt, currentContext)) {
                    assert stmt instanceof LocalToFieldStatement;
                    if (killed.fst()) {
                        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
                            ProgramPointReachability.nonEmptyApproximatedKillSets.add(new OrderedPair<>(stmt,
                                                                                                        currentContext));
                        }
                        if (PointsToAnalysis.outputLevel > 0) {
                            System.err.println("APPROXIMATING kill set for field assign with receivers. "
                                    + stmt + " in " + currentContext);
                        }
                    }
                    // The statement is a field assignment with not receiver for the field access
                    // soundly approximate it by saying that it kills nothing and remove the dependency
                    ppr.removeKillQueryDependency(this.currentSubQuery,
                                                  stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
                    return false;
                }

                if (!killed.fst()) {
                    // not enough info available yet.
                    // for the moment, assume conservatively that this statement
                    // may kill a field we are interested in.
                    assert stmt instanceof LocalToFieldStatement;
                    boolean maybeKilled = couldStatemenKill((LocalToFieldStatement) stmt, noKill);
                    if (DEBUG2) {
                        if (maybeKilled) {
                            System.err.println("PPDQ%% \t\t\tKILL: " + killed);
                        }
                    }
                    if (!maybeKilled) {
                        // This statement can never kill anything in the noKill set
                        ppr.removeKillQueryDependency(this.currentSubQuery,
                                                      stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
                    }
                    return maybeKilled;
                }
                else if (killed.snd() != null && noKill.contains(g.lookupDictionary(killed.snd()))) {
                    // dang! we killed something we shouldn't. Prune the search.
                    if (DEBUG2) {
                        System.err.println("PPDQ%% \t\t\tKILL: " + killed);
                    }
                    impreciseKills.remove(ProgramPointSubQuery.lookupDictionary(this.currentSubQuery));
                    return true;
                }
                else if (killed.snd() == null) {
                    // we have enough information to know that this statement does not kill a node we care about
                    ppr.removeKillQueryDependency(this.currentSubQuery,
                                             stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
                }
                // we have enough information to determine whether this statement kills a field, and it does not
                // kill anything we care about. So we can continue with the search.
                assert killed.fst() && (killed.snd() == null || !noKill.contains(g.lookupDictionary(killed.snd())));
            }
        }
        else {
            // The statement should not be able to kill a node.
            ppr.removeKillQueryDependency(this.currentSubQuery,
                                     stmt.getReadDependencyForKillField(currentContext, g.getHaf()));

            assert stmt.killsNode(currentContext, g) == null
                    || (stmt.killsNode(currentContext, g).fst() == true && stmt.killsNode(currentContext, g).snd() == null);
        }

        // is "to" allocated at this program point?
        InstanceKeyRecency justAllocated = stmt.justAllocated(currentContext, g);
        if (justAllocated != null) {
            assert justAllocated.isRecent();
            int justAllocatedKey = g.lookupDictionary(justAllocated);
            if (DEBUG2) {
                System.err.println("PPDQ%% \t\t\t" + g.lookupDictionary(justAllocated) + " ### " + justAllocated);
                System.err.println("PPDQ%% \t\t\t\tMOST RECENT? " + g.isMostRecentObject(justAllocatedKey));
                System.err.println("PPDQ%% \t\t\t\tTRACKING?    " + g.isTrackingMostRecentObject(justAllocatedKey));
                System.err.println("PPDQ%% \t\t\t\tnoAlloc CONTAINS? "
                        + noAlloc.contains(g.lookupDictionary(justAllocated)));
            }
            if (g.isMostRecentObject(justAllocatedKey) && g.isTrackingMostRecentObject(justAllocatedKey)
                    && noAlloc.contains(g.lookupDictionary(justAllocated))) {
                // dang! we killed allocated we shouldn't. Prune the search.
                if (DEBUG2) {
                    System.err.println("PPDQ%% \t\t\tALLOC KILLED: " + justAllocated);
                }
                impreciseKills.remove(ProgramPointSubQuery.lookupDictionary(this.currentSubQuery));
                return true;
            }
        }
        impreciseKills.remove(ProgramPointSubQuery.lookupDictionary(this.currentSubQuery));
        return false;
    }

    /**
     * Check whether the points-to statement could kill something in the given kill set
     *
     * @param stmt field assignment statement for which the field access has no receiver
     * @param noKill set of points-to graph nodes that must not be killed
     */
    private boolean couldStatemenKill(LocalToFieldStatement stmt, /*Set<PointsToGraphNode>*/IntSet noKill) {
        FieldReference fr = stmt.getMaybeKilledField();
        IntIterator noKillIter = noKill.intIterator();
        while (noKillIter.hasNext()) {
            PointsToGraphNode n = g.lookupPointsToGraphNodeDictionary(noKillIter.next());
            if (n instanceof ObjectField) {
                ObjectField of = (ObjectField) n;
                if (of.fieldName().equals(fr.getName().toString())
                        && of.expectedType().getName().equals(fr.getFieldType().getName())) {
                    // The field in the noKill set has the same name and type as the field that may be killed by the field assignment
                    // Assume that it does kill the field, this is unsound, but we later will recover soundness by
                    // 1. Adding something to the points-to set for the receiver and checking whether it then kills
                    // 2. Assuming that nothing is killed if the points-to set for the receiver is still empty
                    //    after reaching a fixed point in the points-to analysis
                    impreciseKills.add(ProgramPointSubQuery.lookupDictionary(this.currentSubQuery));
                    return true;
                }
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
     * @param visited
     * @return true if the destination program point was found
     */
    private boolean handleMethodExitToUnknownCallSite(/*OrderedPair<IMethod, Context>*/int currentCallGraphNode,
                                                      boolean isExceptionExit, WorkItem src) {
        if (DEBUG2) {
            System.err.println("PPDQ%% \t\t\tHANDLING UNKNOWN EXIT " + currentCallGraphNode + " EX? " + isExceptionExit);
        }

        /*Set<OrderedPair<CallSiteProgramPoint, Context>>*/IntSet callers = g.getCallersOf(currentCallGraphNode);
        if (callers == null) {
            // no callers
            return false;
        }

        /*Iterator<ProgramPointReplica>*/IntIterator callerIter = callers.intIterator();
        while (callerIter.hasNext()) {
            int callSite = callerIter.next();
            boolean isReachableByReturnTo = true;
            if (CallGraphReachability.USE_CALL_GRAPH_REACH) {
                isReachableByReturnTo = ppr.getCallGraphReachability().isReachableByReturnTo(callSite,
                                                                                             this.destinationCGNode,
                                                                                             this.currentSubQuery);
            }
            if (!isReachableByReturnTo) {
                // don't bother with this call site, we can't get to the destination
                continue;
            }
            ProgramPointReplica callerSite = g.lookupCallSiteReplicaDictionary(callSite);
            CallSiteProgramPoint cspp = (CallSiteProgramPoint) callerSite.getPP();

            InterProgramPointReplica callerSiteReplica;
            if (isExceptionExit) {
                callerSiteReplica = cspp.getExceptionExit().post().getReplica(callerSite.getContext());
            }
            else {
                callerSiteReplica = cspp.getNormalExit().post().getReplica(callerSite.getContext());
            }

            // let's explore the caller now.
            ReachabilityResult res = getResults(callerSiteReplica, false, src);
            if (res == ReachabilityResult.FOUND) {
                // we found the destination!
                return true;
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
     * @param visited
     * @return
     */
    private ReachabilityResult getResults(InterProgramPointReplica newSrc, boolean isKnownCallSite, WorkItem trigger) {
        WorkItem newWI = new WorkItem(newSrc, isKnownCallSite);
        if (DEBUG2) {
            System.err.println("PPDQ%% \t\tRequesting results for: " + newWI + " from " + trigger);
        }
        ReachabilityResult res = resultCache.get(newWI);
        if (res != null) {
            if (DEBUG2) {
                System.err.println("PPDQ%% \t\t\tFOUND RESULTS: " + res);
            }
            return res;
        }

        // Set the default results
        resultCache.put(newWI, ReachabilityResult.UNREACHABLE);

        if (currentlyProcessing.contains(newWI)) {
            // add this work item to the queue for reprocessing and return the default value
            searchQ.add(newWI);
            if (DEBUG2) {
                System.err.println("PPDQ%% \t\t\tAlready Processing: " + ReachabilityResult.UNREACHABLE);
            }
            return ReachabilityResult.UNREACHABLE;
        }

        if (search(newWI)) {
            // The destination was found
            resultCache.put(newWI, ReachabilityResult.FOUND);
            return ReachabilityResult.FOUND;
        }

        // make sure to reprocess the trigger if the results for the requested search change
        addDependency(trigger, newWI);
        if (DEBUG2) {
            System.err.println("PPDQ%% \t\t\tNew results for: " + newWI + " " + resultCache.get(newWI));
        }

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
        if (previous != null && previous != res && workItemDependencies.containsKey(wi)) {
            if (DEBUG2) {
                System.err.println("PPDQ%% \t\tResults changed for: " + wi + " from " + previous + " to" + res);
            }
            // result changed, add any dependencies back onto the queue
            Set<WorkItem> deps = workItemDependencies.get(wi);
            if (DEBUG2) {
                System.err.println("PPDQ%% \t\tadding dependencies back into queue");
            }
            for (WorkItem dep : deps) {
                if (DEBUG2) {
                    System.err.println("PPDQ%% \t\t\tdep");
                }
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

    /**
     * Work items stored in the global queue and used to search within a single method
     */
    private static class WorkItem {
        /**
         * Node to search from
         */
        final InterProgramPointReplica src;
        /**
         * If true then the source is the entry program point for a method that came from a known call-site, from which
         * the search will continue when an exit program point is found
         * <p>
         * If false then when the search finds an exit program point it must continue from all possible call-sites
         */
        final boolean isFromCallSite;

        /**
         * Work queue item containing the source node of a search and whether the search was triggered from a known call
         * site
         *
         * @param src node to begin the search from
         * @param isFromCallSite whether the source is from a known call site (if so the source must be a method entry
         *            program point)
         */
        public WorkItem(InterProgramPointReplica src, boolean isFromCallSite) {
            assert src != null;
            assert !isFromCallSite || src.getInterPP().getPP().isEntrySummaryNode();
            this.src = src;
            this.isFromCallSite = isFromCallSite;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (this.isFromCallSite ? 1231 : 1237);
            result = prime * result + this.src.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            WorkItem other = (WorkItem) obj;
            if (this.isFromCallSite != other.isFromCallSite) {
                return false;
            }
            if (!this.src.equals(other.src)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "(" + src + ", " + isFromCallSite + ")";
        }
    }
}
