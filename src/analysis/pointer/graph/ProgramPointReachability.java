package analysis.pointer.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import util.OrderedPair;
import util.WorkQueue;
import util.intmap.ConcurrentIntMap;
import util.intset.EmptyIntSet;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.graph.RelevantNodesIncremental.RelevantNodesQuery;
import analysis.pointer.graph.RelevantNodesIncremental.SourceRelevantNodesQuery;
import analysis.pointer.registrar.MethodSummaryNodes;
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
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * This class answers questions about what programs are reachable from what other program points, and caches answers
 * smartly.
 */
public final class ProgramPointReachability {
    /**
     * Whether to incrementally print diagnostic timing and count information
     */
    private boolean PRINT_DIAGNOSTICS = true;

    /**
     * Print debug info
     */
    public static boolean DEBUG;

    /**
     * Print more debug info
     */
    public static boolean DEBUG2;

    /**
     * Keep a reference to the PointsToGraph for convenience.
     */
    private final PointsToGraph g;

    /**
     * A reference to allow us to submit a subquery for reprocessing
     */
    private final PointsToAnalysisHandle analysisHandle;

    /**
     * A reference to an object that will find us relevant nodes for reachability queries.
     */
    private final RelevantNodesIncremental relevantNodesIncrementalComputation;

    /**
     * Create a new reachability query engine
     *
     * @param g points to graph
     * @param analysisHandle interface for submitting jobs to the pointer analysis
     */
    ProgramPointReachability(PointsToGraph g, PointsToAnalysisHandle analysisHandle) {
        assert g != null && analysisHandle != null;
        this.g = g;
        this.analysisHandle = analysisHandle;
        this.relevantNodesIncrementalComputation = new RelevantNodesIncremental(g, analysisHandle, this);
    }

    /**
     * Given a map from InterProgramPoints to KilledAndAlloceds, either get the existing KilledAndAlloced for ipp, or
     * create one that represents all-killed-all-allocated and add it to the map for ipp.
     *
     * @param results map from program point to KilledAndAlloced sets that may contain <code>ipp</code>
     * @param ipp program point to get the sets for
     *
     * @return killed points-to graph nodes and allocated instance keys for the given key
     */
    private static KilledAndAlloced getOrCreate(Map<InterProgramPoint, KilledAndAlloced> results, InterProgramPoint ipp) {
        KilledAndAlloced res = results.get(ipp);
        if (res == null) {
            res = KilledAndAlloced.createUnreachable();
            results.put(ipp, res);
        }
        return res;
    }

    /**
     * Can destination be reached from any InterProgramPointReplica in <code>ppsc</code> without going through a program
     * point that kills any PointsToGraphNode in noKill, and without going through a program point that allocates any
     * InstanceKey in noAlloc?
     *
     * @return true if the destination is reachable from any source
     */
    public boolean reachable(ProgramPointSetClosure ppsc, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc, ReachabilityQueryOrigin origin) {
        return reachableImpl(ppsc.getSources(origin),
                             destination,
                             noKill,
                             noAlloc,
                             Collections.<InterProgramPointReplica> emptySet(),
                             origin);
    }

    /**
     * Can destination be reached from source without going through any of the forbidden program points? If forbidden is
     * non empty, then all of the forbidden IPPRs must be in the same method and context as one of the source or the
     * destination.
     *
     * @return true if the destination is reachable from the source
     */
    public boolean reachable(InterProgramPointReplica source, InterProgramPointReplica destination,
                             Set<InterProgramPointReplica> forbidden, ReachabilityQueryOrigin origin) {
        return reachableImpl(Collections.singleton(source),
                             destination,
                             EmptyIntSet.INSTANCE,
                             EmptyIntSet.INSTANCE,
                             forbidden,
                             origin);
    }

    /**
     * Can destination be reached from any InterProgramPointReplica in sources without going through a program point
     * that kills any PointsToGraphNode in noKill, and without going through a program point that allocates any
     * InstanceKey in noAlloc?
     *
     * @return true if the destination is reachable from anys source
     */
    public boolean reachable(Collection<InterProgramPointReplica> sources, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc, ReachabilityQueryOrigin origin) {
        return reachableImpl(sources,
                             destination,
                             noKill,
                             noAlloc,
                             Collections.<InterProgramPointReplica> emptySet(),
                             origin);
    }

    /**
     * Can destination be reached from any InterProgramPointReplica in sources without going through a program point
     * that kills any PointsToGraphNode in noKill, and without going through a program point that allocates any
     * InstanceKey in noAlloc, and without going through any of the forbidden IPPRs? If forbidden is non empty, then all
     * of the forbidden IPPRs must be in the same method and context as one of the source or the destination.
     *
     * @return true if the destination is reachable from anys source
     */
    private boolean reachableImpl(Collection<InterProgramPointReplica> sources, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc,
                                  Set<InterProgramPointReplica> forbidden, ReachabilityQueryOrigin origin) {
        totalRequests.incrementAndGet();
        assert allMostRecent(noAlloc);
        assert allInSameMethodAndContext(forbidden, sources, destination);

        // Uncomment for debugging output for a particular destination
        //        if (destination.toString().contains("**17558_pre**")) {
        //            DEBUG = true;
        //            DEBUG2 = true;
        //        }

        if (DEBUG && sources.isEmpty()) {
            System.err.println("PPR%%NO SOURCES " + " -> " + destination);
            System.err.println("PPR%%\tNO KILL " + noKill);
            System.err.println("PPR%%\tNO ALLOC " + noAlloc);
            System.err.println("PPR%%\tforbidden " + forbidden);
            System.err.println("PPR%%\tORIGIN " + origin);
        }

        // check the caches
        List<InterProgramPointReplica> unknown = new ArrayList<>(sources.size());
        for (InterProgramPointReplica src : sources) {
            ProgramPointSubQuery mr = new ProgramPointSubQuery(src, destination, noKill, noAlloc, forbidden);
            if (this.positiveCache.contains(mr)) {
                // We have already computed that the destination is reachable from src
                cachedResponses.incrementAndGet();
                return true;
            }
            addDependency(mr, origin);
            if (this.negativeCache.contains(mr)) {
                // We know it's a negative result for this one, keep looking
                if (DEBUG) {
                    System.err.println("PPR%%negative cache " + src + " -> " + destination);
                }
            }
            else {
                unknown.add(src);
            }
        }

        if (unknown.isEmpty()) {
            // all were negative!
            if (DEBUG) {
                System.err.println("PPR%%\t" + " -> " + destination);
                System.err.println("PPR%%\torigin " + origin);
                System.err.println("PPR%%\tfalse because cache all negative");
            }
            DEBUG = false;
            DEBUG2 = false;
            cachedResponses.incrementAndGet();
            return false;
        }
        // The cache didn't help. Try getting an answer for the unknown elements.
        this.reachabilityRequests.incrementAndGet();
        return computeQuery(unknown, destination, noKill, noAlloc, forbidden);
    }

    /**
     * Check whether every element of the set of forbidden nodes is in the same method and context as the source or the
     * destination.
     *
     * @param forbidden set of nodes to check against the sources and destination
     * @param sources sources of a reachability query (must be a singleton if the forbidden set is non-empty)
     * @param destination destination of a reachability query
     * @return true if each of the forbidden nodes is valid
     */
    private static boolean allInSameMethodAndContext(Set<InterProgramPointReplica> forbidden,
                                                     Collection<InterProgramPointReplica> sources,
                                                     InterProgramPointReplica destination) {
        if (forbidden.isEmpty()) {
            return true;
        }
        assert sources.size() == 1;
        InterProgramPointReplica agreement = null;
        for (InterProgramPointReplica ippr : forbidden) {
            if (agreement == null) {
                if (destination.getContainingProcedure() == ippr.getContainingProcedure()
                        && destination.getContext().equals(ippr.getContext())) {
                    agreement = destination;
                }
                else if ((agreement = sources.iterator().next()).getContainingProcedure() == ippr.getContainingProcedure()
                        && agreement.getContext().equals(ippr.getContext())) {
                    // OK!
                }
                else {
                    return false;
                }
            }
            else {
                if (agreement.getContainingProcedure() != ippr.getContainingProcedure()
                        || !agreement.getContext().equals(ippr.getContext())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Can destination be reached from any InterProgramPointReplica in sources without going through a program point
     * that kills any PointsToGraphNode in noKill, and without going through a program point that allocates any
     * InstanceKey in noAlloc, and without going through any of the forbidden IPPRs? If forbidden is non empty, then all
     * of the forbidden IPPRs must be in the same method and context as one of the source or the destination.
     *
     * @param sources sources of the reachability query
     * @param destination destination node
     * @param noKill set of points-to graph nodes that must not be killed
     * @param noAlloc set of instance keys that must not be allocated
     * @param forbidden set of program points that must not be passed through
     *
     * @return true if the destination is reachable from anys source
     */
    private boolean computeQuery(Collection<InterProgramPointReplica> sources, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc,
                                 Set<InterProgramPointReplica> forbidden) {
        // Uncomment for debugging output for a particular destination
        //        if (destination.toString().contains("**17558_pre**")) {
        //            DEBUG = true;
        //            DEBUG2 = true;
        //        }

        this.destinations.add(destination);
        int resp = this.computedResponses.incrementAndGet();
        if (PRINT_DIAGNOSTICS && (resp % 10000) == 0) {
            printDiagnostics();
        }
        this.totalDestQueries.add(new DestQuery(destination, noKill, noAlloc, forbidden));
        long start = System.currentTimeMillis();
        ProgramPointDestinationQuery prq = new ProgramPointDestinationQuery(destination,
                                                                            noKill,
                                                                            noAlloc,
                                                                            forbidden,
                                                                            g,
                                                                            this);

        if (DEBUG) {
            // Uncomment if only want output for a particular source
            //            boolean reallyDEBUG = false;
            //            for (InterProgramPointReplica src : sources) {
            //                if (src.toString().contains("**17567_post**")) {
            //                    reallyDEBUG = true;
            //                    break;
            //                }
            //            }
            //
            //            if (!reallyDEBUG) {
            //                prq.DEBUG = false;
            //                prq.DEBUG2 = false;
            //                DEBUG = false;
            //                DEBUG2 = false;
            //            }

            prq.DEBUG = DEBUG;
            prq.DEBUG2 = DEBUG2;
        }



        // try to solve it for each source.
        for (InterProgramPointReplica src : sources) {
            ProgramPointSubQuery query = new ProgramPointSubQuery(src, destination, noKill, noAlloc, forbidden);
            totalDestQuery.incrementAndGet();

            if (positiveCache.contains(query)) {
                // The result was computed by another thread before this thread ran
                cachedDestQuery.incrementAndGet();
                return true;
            }
            this.sources.add(src);

            // First check the call graph to find the set of call graph nodes that must be searched directly
            // (i.e. the effects for these nodes cannot be summarized).
            /*OrderedPair<IMethod, Context>*/int source = g.lookupCallGraphNodeDictionary(new OrderedPair<>(query.source.getContainingProcedure(),
                                                                                                             query.source.getContext()));
            /*OrderedPair<IMethod, Context>*/int dest = g.lookupCallGraphNodeDictionary(new OrderedPair<>(query.destination.getContainingProcedure(),
                                                                                                           query.destination.getContext()));
            long startRelevant = System.currentTimeMillis();
            /*Set<OrderedPair<IMethod, Context>>*/IntSet relevantNodes = this.relevantNodesIncrementalComputation.relevantNodes(source,
                                                                                                                                 dest,
                                                                                                                                 query);
            relevantNodesTime.addAndGet(System.currentTimeMillis() - startRelevant);

            if (relevantNodes.isEmpty()) {
                // this path isn't possible.
                if (recordQueryResult(query, false)) {
                    // We computed false, but the cache already had true
                    if (DEBUG) {
                        System.err.println("PPR%%\t" + src + " -> " + destination);
                        System.err.println("PPR%%\ttrue because cache already had true");
                    }
                    DEBUG = false;
                    DEBUG2 = false;
                    totalTime.addAndGet(System.currentTimeMillis() - start);
                    return true;
                }
                if (DEBUG) {
                    System.err.println("PPR%%\t" + src + " -> " + destination);
                    System.err.println("PPR%%\tfalse because no relevant nodes");
                }
                continue;
            }

            // Now try a search starting at the source
            long startDest = System.currentTimeMillis();
            boolean found = prq.executeSubQuery(src, relevantNodes);
            destQueryTime.addAndGet(System.currentTimeMillis() - startDest);
            computedDestQuery.incrementAndGet();
            if (found) {
                recordQueryResult(query, true);
                if (DEBUG) {
                    System.err.println("PPR%%\t" + src + " -> " + destination);
                    System.err.println("PPR%%\ttrue because query returned true");
                }
                DEBUG = false;
                DEBUG2 = false;
                totalTime.addAndGet(System.currentTimeMillis() - start);
                return true;
            }
            if (DEBUG) {
                System.err.println("PPR%%\t" + src + " -> " + destination);
                System.err.println("PPR%%\tfalse because query returned false");
            }
            if (recordQueryResult(query, false)) {
                // We computed false, but the cache already had true
                if (DEBUG) {
                    System.err.println("PPR%%\t" + src + " -> " + destination);
                    System.err.println("PPR%%\ttrue because cache already had true (2)");
                }
                DEBUG = false;
                DEBUG2 = false;
                totalTime.addAndGet(System.currentTimeMillis() - start);
                return true;
            }
        }
        // we didn't find it.
        if (DEBUG) {
            System.err.println("PPR%%\t" + " -> " + destination);
            System.err.println("PPR%%\tAll sources false");
        }
        DEBUG = false;
        DEBUG2 = false;
        totalTime.addAndGet(System.currentTimeMillis() - start);
        return false;
    }

    /**
     * Record in the caches that the result of query mr is b. If this is a change (from negative to positive), then some
     * StmtAndContexts may need to be rerun. If set sacsToReprocess is non-null then the StmtAndContexts will be added
     * to the set. Otherwise, they will be submitted to the PointsToEngine.
     *
     * @param query query to record
     * @param b new result, true if the destination was reachable from the source, false otherwise
     *
     * @return Whether the actual result is "true" or "false". When recording false, it is possible to return true if
     *         the cache already has a positive result.
     */
    private boolean recordQueryResult(ProgramPointSubQuery query, boolean b) {
        long start = System.currentTimeMillis();
        totalQueries.add(query);
        if (b) {
            positiveCache.add(query);
            if (negativeCache.remove(query)) {
                // we previously thought it was negative.
                queryResultChanged(query);
            }
            recordResultsTime.addAndGet(System.currentTimeMillis() - start);
            return true;
        }

        // Recording a false result
        negativeCache.add(query);
        if (positiveCache.contains(query)) {
            this.negativeCache.remove(query);
            // A positive result has already been computed return it
            recordResultsTime.addAndGet(System.currentTimeMillis() - start);
            return true;
        }
        recordResultsTime.addAndGet(System.currentTimeMillis() - start);
        return false;
    }

    /**
     * Check if all the elements of <code>s</code> are "most recent" instance keys
     *
     * @param s set of instance keys
     * @return true if all the elements are "most recent"
     */
    private boolean allMostRecent(/*Set<InstanceKey>*/IntSet s) {
        IntIterator iter = s.intIterator();
        while (iter.hasNext()) {
            int ik = iter.next();
            if (!g.isMostRecentObject(ik)) {
                return false;
            }
        }
        return true;
    }

    /* *****************************************************************************
    *
    * METHOD REACHABILITY CODE
    *
    * The following code is responsible for computing the reachability results of an
    * entire method.
    */

    // ConcurrentMap<OrderedPair<IMethod, Context>, MethodSummaryKillAndAlloc>
    private final ConcurrentIntMap<MethodSummaryKillAndAlloc> methodSummaryMemoization = AnalysisUtil.createConcurrentIntMap();

    /*
     * Get the reachability results for a method.
     */
    MethodSummaryKillAndAlloc getReachabilityForMethod(/*OrderedPair<IMethod, Context>*/int cgNode) {
        totalMethodReach.incrementAndGet();
        if (DEBUG) {
            OrderedPair<IMethod, Context> n = g.lookupCallGraphNodeDictionary(cgNode);
            System.err.println("PPR%% METHOD " + PrettyPrinter.methodString(n.fst()) + " in " + n.snd());
        }
        MethodSummaryKillAndAlloc res = methodSummaryMemoization.get(cgNode);
        if (res != null) {
            if (DEBUG) {
                System.err.println("PPR%% \tCACHED");
                for (InterProgramPointReplica src : res.getAllSources()) {
                    for (InterProgramPointReplica target : res.getTargetMap(src).keySet()) {
                        System.err.println("PPR%% \t\t" + src + " -> " + target + " "
                                + res.getTargetMap(src).get(target));
                        MutableIntSet alloced = res.getTargetMap(src).get(target).getAlloced();
                        IntIterator iter;
                        if (alloced != null) {
                            iter = alloced.intIterator();
                            while (iter.hasNext()) {
                                System.err.println("PPR%% \t\t\tALLOC " + g.lookupInstanceKeyDictionary(iter.next()));
                            }
                        }
                    }
                }
            }
            cachedMethodReach.incrementAndGet();
            return res;
        }
        // no results yet.
        res = MethodSummaryKillAndAlloc.createInitial();
        MethodSummaryKillAndAlloc existing = methodSummaryMemoization.putIfAbsent(cgNode, res);
        if (existing != null) {
            // someone beat us to it, and is currently working on the results.
            if (DEBUG) {
                System.err.println("PPR%% \tBEATEN");
                for (InterProgramPointReplica src : existing.getAllSources()) {
                    for (InterProgramPointReplica target : existing.getTargetMap(src).keySet()) {
                        System.err.println("PPR%% \t\t" + src + " -> " + target + " "
                                + existing.getTargetMap(src).get(target));
                        IntIterator iter = existing.getTargetMap(src).get(target).getAlloced().intIterator();
                        while (iter.hasNext()) {
                            System.err.println("PPR%% \t\t\tALLOC " + g.lookupInstanceKeyDictionary(iter.next()));
                        }
                    }
                }
            }
            cachedMethodReach.incrementAndGet();
            return existing;
        }

        MethodSummaryKillAndAlloc rr = computeReachabilityForMethod(cgNode);
        if (DEBUG) {
            OrderedPair<IMethod, Context> n = g.lookupCallGraphNodeDictionary(cgNode);
            System.err.println("PPR%% \tCOMPUTED " + PrettyPrinter.methodString(n.fst()) + " in " + n.snd());
            for (InterProgramPointReplica src : rr.getAllSources()) {
                for (InterProgramPointReplica target : rr.getTargetMap(src).keySet()) {
                    System.err.println("PPR%% \t\t" + src + " -> " + target + " " + rr.getTargetMap(src).get(target));
                    MutableIntSet alloced = rr.getTargetMap(src).get(target).getAlloced();
                    if (alloced != null) {
                        IntIterator iter = alloced.intIterator();
                        while (iter.hasNext()) {
                            System.err.println("PPR%% \t\t\tALLOC " + g.lookupInstanceKeyDictionary(iter.next()));
                        }
                    }
                }
            }
        }
        computedMethodReach.incrementAndGet();
        return rr;
    }

    private void recordMethodReachability(/*OrderedPair<IMethod, Context>*/int cgnode, MethodSummaryKillAndAlloc res) {
        long start = System.currentTimeMillis();
        MethodSummaryKillAndAlloc existing = methodSummaryMemoization.put(cgnode, res);
        if (existing != null && !existing.equals(res)) {
            // trigger update for dependencies.
            methodSummaryChanged(cgnode);
        }
        recordMethodTime.addAndGet(System.currentTimeMillis() - start);
    }

    private MethodSummaryKillAndAlloc computeReachabilityForMethod(/*OrderedPair<IMethod, Context>*/int cgNode) {
        long start = System.currentTimeMillis();
        // XXX at the moment we will just record from the start node.
        OrderedPair<IMethod, Context> n = g.lookupCallGraphNodeDictionary(cgNode);
        Context context = n.snd();
        if (DEBUG) {
            System.err.println("PPR%% COMPUTING FOR " + PrettyPrinter.methodString(n.fst()) + " in " + context);
        }

        // do a dataflow over the program points. XXX could try to use a dataflow framework to speed this up.

        Map<InterProgramPoint, KilledAndAlloced> results = new HashMap<>();
        WorkQueue<InterProgramPoint> q = new WorkQueue<>();
        Set<InterProgramPoint> visited = new HashSet<>();

        MethodSummaryNodes summ = g.getRegistrar().getMethodSummary(n.fst());
        PostProgramPoint entryIPP = summ.getEntryPP().post();
        q.add(entryIPP);
        getOrCreate(results, entryIPP).setEmpty();

        while (!q.isEmpty()) {
            InterProgramPoint ipp = q.poll();
            if (!visited.add(ipp)) {
                continue;
            }
            if (DEBUG) {
                System.err.println("PPR%% \tQ " + ipp);
            }
            ProgramPoint pp = ipp.getPP();
            assert pp.getContainingProcedure().equals(n.fst());
            KilledAndAlloced current = getOrCreate(results, ipp);

            if (ipp instanceof PreProgramPoint) {
                if (pp instanceof CallSiteProgramPoint) {
                    // this is a method call! Register the dependency and get some cached results
                    /*ProgramPointReplica*/int callSite = g.lookupCallSiteReplicaDictionary(pp.getReplica(context));
                    addCalleeDependency(cgNode, callSite);

                    CallSiteProgramPoint cspp = (CallSiteProgramPoint) pp;

                    /*Set<OrderedPair<IMethod, Context>>*/IntSet calleeSet = g.getCalleesOf(callSite);
                    if (calleeSet.isEmpty()) {
                        // no callees, so nothing to do
                        if (DEBUG) {
                            System.err.println("PPR%% \t\tno callees " + ipp);
                        }
                        continue;
                    }

                    KilledAndAlloced postNormal = getOrCreate(results, cspp.getNormalExit().post());
                    KilledAndAlloced postEx = getOrCreate(results, cspp.getExceptionExit().post());

                    /*Iterator<OrderedPair<IMethod, Context>>*/IntIterator calleeIter = calleeSet.intIterator();
                    while (calleeIter.hasNext()) {
                        int calleeInt = calleeIter.next();
                        addMethodDependency(cgNode, calleeInt);
                        MethodSummaryKillAndAlloc calleeResults = getReachabilityForMethod(calleeInt);

                        OrderedPair<IMethod, Context> callee = g.lookupCallGraphNodeDictionary(calleeInt);
                        MethodSummaryNodes calleeSummary = g.getRegistrar().getMethodSummary(callee.fst());
                        InterProgramPointReplica calleeEntryIPPR = ProgramPointReplica.create(callee.snd(),
                                                                                              calleeSummary.getEntryPP())
                                                                                      .post();
                        KilledAndAlloced normalRet = calleeResults.getResult(calleeEntryIPPR,
                                                                             ProgramPointReplica.create(callee.snd(),
                                                                                                        calleeSummary.getNormalExitPP())
                                                                                                .pre());
                        KilledAndAlloced exRet = calleeResults.getResult(calleeEntryIPPR,
                                                                         ProgramPointReplica.create(callee.snd(),
                                                                                                    calleeSummary.getExceptionExitPP())
                                                                                            .pre());

                        // The final results will be the sets that are killed or alloced for all the callees
                        //     so intersect the results
                        postNormal.meet(KilledAndAlloced.join(current, normalRet));
                        postEx.meet(KilledAndAlloced.join(current, exRet));

                    }
                    // Add the successor program points to the queue
                    q.add(cspp.getNormalExit().post());
                    q.add(cspp.getExceptionExit().post());
                } // end CallSiteProgramPoint
                else if (pp.isNormalExitSummaryNode() || pp.isExceptionExitSummaryNode()) {
                    // not much to do here. The results will be copied once the work queue finishes.
                    if (DEBUG2) {
                        System.err.println("PPR%% \t\tEXIT " + pp);
                    }
                    continue;
                } // end ExitSummaryNode
                else {
                    PointsToStatement stmt = g.getRegistrar().getStmtAtPP(pp);
                    // not a call or a return, it's just a normal statement.
                    // does ipp kill this.node?
                    if (stmt != null) {
                        if (stmt.mayKillNode(context, g)) {
                            // record the dependency before we call stmt.killsNode
                            addKillDependency(cgNode, stmt.getReadDependencyForKillField(context, g.getHaf()));

                            OrderedPair<Boolean, PointsToGraphNode> killed = stmt.killsNode(context, g);
                            if (killed != null) {
                                if (!killed.fst()) {
                                    if (DEBUG2) {
                                        System.err.println("PPR%% \t\tCould Kill "
                                                + stmt.getReadDependencyForKillField(context, g.getHaf()));
                                    }
                                    // not enough info available yet.
                                    // add a dependency since more information may change this search
                                    // conservatively assume that it kills any kind of the field we give it.
                                    current.addMaybeKilledField(stmt.getMaybeKilledField());

                                }
                                else if (killed.snd() != null) {
                                    if (DEBUG2) {
                                        System.err.println("PPR%% \t\tDoes Kill "
                                                + stmt.getReadDependencyForKillField(context, g.getHaf()) + " "
                                                + g.lookupDictionary(killed.snd()));
                                    }
                                    // this statement really does kill something.
                                    current.addKill(g.lookupDictionary(killed.snd()));
                                }
                                else if (killed.snd() == null) {
                                    // we have enough information to know that this statement does not kill a node we care about
                                    removeKillDependency(cgNode,
                                                         stmt.getReadDependencyForKillField(context, g.getHaf()));
                                }
                            }
                        }
                        else {
                            // The statement should not be able to kill a node.
                            removeKillDependency(cgNode, stmt.getReadDependencyForKillField(context, g.getHaf()));

                            assert stmt.killsNode(context, g) == null
                                    || (stmt.killsNode(context, g).fst() == true && stmt.killsNode(context, g).snd() == null);
                        }

                        // is anything allocated at this program point?
                        InstanceKeyRecency justAllocated = stmt.justAllocated(context, g);
                        if (justAllocated != null) {
                            assert justAllocated.isRecent();
                            int/*InstanceKeyRecency*/justAllocatedKey = g.lookupDictionary(justAllocated);
                            if (g.isMostRecentObject(justAllocatedKey)
                                    && g.isTrackingMostRecentObject(justAllocatedKey)) {
                                if (DEBUG2) {
                                    System.err.println("PPR%% \t\tDoes Alloc " + justAllocatedKey);
                                }
                                current.addAlloced(justAllocatedKey);
                            }
                        }
                    }
                    // Add the post program point to continue the traversal
                    KilledAndAlloced postResults = getOrCreate(results, pp.post());
                    postResults.meet(current);
                    q.add(pp.post());
                } // end other pre program point
            } // end pre program point
            else if (ipp instanceof PostProgramPoint) {
                Set<ProgramPoint> ppSuccs = pp.succs();
                // Add all the successor program points
                for (ProgramPoint succ : ppSuccs) {
                    KilledAndAlloced succResults = getOrCreate(results, succ.pre());
                    succResults.meet(current);
                    q.add(succ.pre());
                }
            } // end post program point
            else {
                throw new IllegalArgumentException("Don't know about this kind of interprogrampoint");
            }
        } // queue is now empty

        MethodSummaryKillAndAlloc rr = MethodSummaryKillAndAlloc.createInitial();
        PreProgramPoint normExitIPP = summ.getNormalExitPP().pre();
        PreProgramPoint exExitIPP = summ.getExceptionExitPP().pre();

        rr.add(entryIPP.getReplica(context), normExitIPP.getReplica(context), getOrCreate(results, normExitIPP));
        rr.add(entryIPP.getReplica(context), exExitIPP.getReplica(context), getOrCreate(results, exExitIPP));

        recordMethodReachability(cgNode, rr);
        methodReachTime.addAndGet(System.currentTimeMillis() - start);
        return rr;
    }

    /**
     * A ReachabilityResult records the reachability results of a single method in a context, that is, which for a
     * subset of the source and destination pairs of InterProgramPointReplica in the method and context, what are the
     * KilledAndAlloced sets that summarize all paths from the source to the destination.
     *
     * This object must be thread safe.
     */
    static class MethodSummaryKillAndAlloc {
        /**
         * Map from source iipr to target ippr with a pair of killed and allocated. Note that all source and target
         * ipprs in m should be from the same method.
         *
         * If (s, t, _, _) is not in the map, then it means that t is not reachable from s (or at least we haven't found
         * out that it is, or are not interested in recording that fact).
         *
         * If (s, t, killed, alloced) is in the map, then this means that t is reachable from s, but all paths from s to
         * t will kill the PointsToGraphNodes in killed, and allocate the InstanceKeyRecencys in alloced.
         */
        final ConcurrentMap<InterProgramPointReplica, ConcurrentMap<InterProgramPointReplica, KilledAndAlloced>> m = AnalysisUtil.createConcurrentHashMap();

        private MethodSummaryKillAndAlloc() {
            // Intentionally left blank
        }

        /**
         * Create a result in which every result is unreachable
         *
         * @return
         */
        public static MethodSummaryKillAndAlloc createInitial() {
            return new MethodSummaryKillAndAlloc();
        }

        public KilledAndAlloced getResult(InterProgramPointReplica source, InterProgramPointReplica target) {
            ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> s = m.get(source);
            if (s == null) {
                return KilledAndAlloced.createUnreachable();
            }
            KilledAndAlloced p = s.get(target);
            if (p == null) {
                return KilledAndAlloced.createUnreachable();
            }
            return p;
        }

        public void add(InterProgramPointReplica source, InterProgramPointReplica target, KilledAndAlloced res) {
            assert target != null;
            assert source.getContainingProcedure().equals(target.getContainingProcedure());
            assert source.getContext().equals(target.getContext());
            ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> thisTargetMap = this.getTargetMap(source);
            if (res != null) {
                KilledAndAlloced existing = thisTargetMap.putIfAbsent(target, res);
                assert existing == null;
            }
            else {
                // we are putting in null, i.e., the target is not reachable from the source.
                assert !thisTargetMap.containsKey(target);
                //thisTargetMap.remove(target);
            }
        }

        Set<InterProgramPointReplica> getAllSources() {
            return this.m.keySet();
        }

        ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> getTargetMap(InterProgramPointReplica s) {
            ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> tm = this.m.get(s);
            if (tm == null) {
                tm = AnalysisUtil.createConcurrentHashMap();
                ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> existing = this.m.putIfAbsent(s, tm);
                if (existing != null) {
                    tm = existing;
                }
            }
            return tm;
        }

        @Override
        public int hashCode() {
            return m.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (!(obj instanceof MethodSummaryKillAndAlloc)) {
                return false;
            }
            MethodSummaryKillAndAlloc other = (MethodSummaryKillAndAlloc) obj;
            if (m == null) {
                if (other.m != null) {
                    return false;
                }
            }
            else if (!m.equals(other.m)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (InterProgramPointReplica s : m.keySet()) {
                for (InterProgramPointReplica t : m.get(s).keySet()) {
                    sb.append(s + " -> " + t + "\n\t" + m.get(s).get(t));
                }
            }
            return sb.toString();
        }
    }

    /* *****************************************************************************
     *
     * REACHABILITY QUERY RESULT MEMOIZATION
     *
     * The following code is responsible for memoizing query results.
     */

    /**
     * Cache of the positive answers. i.e., if (source, destination, noKill, noAlloc) in positiveCache, then there is a
     * path from source to destination that does not kill any object in noKill, nor does it allocate any object in
     * noAlloc.
     */
    private Set<ProgramPointSubQuery> positiveCache = AnalysisUtil.createConcurrentSet();
    private Set<ProgramPointSubQuery> negativeCache = AnalysisUtil.createConcurrentSet();

    /* *****************************************************************************
     *
     * DEPENDENCY TRACKING
     *
     * The following code is responsible for recording dependencies
     */
    /**
     * Reachability queries depend on a sub queries
     */
    private final ConcurrentMap<ProgramPointSubQuery, Set<ReachabilityQueryOrigin>> queryDependencies = AnalysisUtil.createConcurrentHashMap();
    /**
     * Sub queries depend on the callers at particular call site (ProgramPointReplica)
     */
    // ConcurrentMap<ProgramPointReplica, Set<ProgramPointSubQuery>>
    private final ConcurrentIntMap<Set<ProgramPointSubQuery>> calleeQueryDependencies = AnalysisUtil.createConcurrentIntMap();
    /**
     * Method reachability queries (starting at a given call garph node) depend on the callers particular call site
     * (ProgramPointReplica)
     */
    // ConcurrentMap<ProgramPointReplica, Set<OrderedPair<IMethod, Context>>>
    private final ConcurrentIntMap<MutableIntSet> calleeMethodDependencies = AnalysisUtil.createConcurrentIntMap();
    /**
     * Sub queries depend on the callees of a particular call graph node (OrderedPair<IMethod, Context>)
     */
    // ConcurrentMap<OrderedPair<IMethod, Context>, Set<ProgramPointSubQuery>>
    private final ConcurrentIntMap<Set<ProgramPointSubQuery>> callerQueryDependencies = AnalysisUtil.createConcurrentIntMap();
    /**
     * Sub queries depend on a method reachability query starting at a particular call graph node (OrderedPair<IMethod,
     * Context>)
     */
    // ConcurrentMap<OrderedPair<IMethod, Context>, Set<ProgramPointSubQuery>>
    private final ConcurrentIntMap<Set<ProgramPointSubQuery>> methodQueryDependencies = AnalysisUtil.createConcurrentIntMap();
    /**
     * Method reachability queries indicated by the start node depend on method reachability queries starting at a
     * different call graph node (OrderedPair<IMethod, Context>)
     */
    // ConcurrentMap<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>>
    private final ConcurrentIntMap<MutableIntSet> methodMethodDependencies = AnalysisUtil.createConcurrentIntMap();
    /**
     * Sub queries depend on the killset at a particular PointsToGraphNode
     */
    // ConcurrentIntMap<Set<ProgramPointSubQuery>>
    private final ConcurrentIntMap<Set<ProgramPointSubQuery>> killQueryDependencies = AnalysisUtil.createConcurrentIntMap();
    /**
     * Method reachability queries (beginning at a particular call graph node) depend on the killset at a particular
     * PointsToGraphNode
     */
    // ConcurrentIntMap<Set<OrderedPair<IMethod, Context>>>
    private final ConcurrentIntMap<MutableIntSet> killMethodDependencies = AnalysisUtil.createConcurrentIntMap();

    /**
     * Record the fact that the result of query depends on the callees of caller, and thus, if the callees change, then
     * query may need to be reevaluated.
     *
     * @param query
     * @param caller
     */
    void addCalleeDependency(ProgramPointSubQuery query, /*OrderedPair<IMethod, Context>*/int caller) {
        long start = System.currentTimeMillis();
        Set<ProgramPointSubQuery> s = calleeQueryDependencies.get(caller);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<ProgramPointSubQuery> existing = calleeQueryDependencies.putIfAbsent(caller, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
        calleeDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * query needs to be re-run if there is a new caller of callGraphNode.
     *
     * @param query
     * @param callGraphNode
     */
    void addCallerDependency(ProgramPointSubQuery query, /*OrderedPair<IMethod, Context>*/int callGraphNode) {
        long start = System.currentTimeMillis();
        Set<ProgramPointSubQuery> s = callerQueryDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<ProgramPointSubQuery> existing = callerQueryDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
        callerDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * We need to reanalyze the method results for (m, context) if the reachability results for callGraphNode changes.
     *
     * @param m
     * @param context
     * @param callGraphNode
     */
    void addMethodDependency(ProgramPointSubQuery query, /*OrderedPair<IMethod, Context>*/int callGraphNode) {
        long start = System.currentTimeMillis();
        Set<ProgramPointSubQuery> s = methodQueryDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<ProgramPointSubQuery> existing = methodQueryDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
        methodDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    void addKillDependency(ProgramPointSubQuery query, ReferenceVariableReplica readDependencyForKillField) {
        long start = System.currentTimeMillis();
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);
        Set<ProgramPointSubQuery> s = killQueryDependencies.get(n);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<ProgramPointSubQuery> existing = killQueryDependencies.putIfAbsent(n, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
        killQueryDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    void removeKillDependency(ProgramPointSubQuery query, ReferenceVariableReplica readDependencyForKillField) {
        long start = System.currentTimeMillis();
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);

        Set<ProgramPointSubQuery> s = killQueryDependencies.get(n);
        if (s != null) {
            s.remove(query);
        }
        killQueryDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    private void addCalleeDependency(/*OrderedPair<IMethod, Context>*/int callee, /*ProgramPointReplica*/int callSite) {
        long start = System.currentTimeMillis();
        /*Set<OrderedPair<IMethod, Context>>*/MutableIntSet s = calleeMethodDependencies.get(callSite);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = calleeMethodDependencies.putIfAbsent(callSite, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(callee);
        methodCalleeDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * We need to reanalyze the method results for "dependee" if the reachability results for callGraphNode changes.
     *
     * @param dependee
     * @param callGraphNode
     */
    private void addMethodDependency(/*OrderedPair<IMethod, Context>*/int dependee,
    /*OrderedPair<IMethod, Context>*/int callGraphNode) {
        long start = System.currentTimeMillis();
        /*Set<OrderedPair<IMethod, Context>>*/MutableIntSet s = methodMethodDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = methodMethodDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(dependee);
        methodCallerDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    private void addKillDependency(/*OrderedPair<IMethod, Context>*/int callGraphNode,
                                   ReferenceVariableReplica readDependencyForKillField) {
        long start = System.currentTimeMillis();
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);

        /*Set<OrderedPair<IMethod, Context>>*/MutableIntSet s = killMethodDependencies.get(n);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = killMethodDependencies.putIfAbsent(n, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(callGraphNode);
        killCallerDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    private void removeKillDependency(/*OrderedPair<IMethod, Context>*/int callGraphNode,
                                      ReferenceVariableReplica readDependencyForKillField) {
        long start = System.currentTimeMillis();
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);

        /*Set<OrderedPair<IMethod, Context>>*/MutableIntSet s = killMethodDependencies.get(n);
        if (s != null) {
            s.remove(callGraphNode);
        }
        killCallerDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * This method is invoked to let us know that a new edge in the call graph has been added from the callSite.
     *
     * We use this method to make sure that we re-run any method reachability results and (negative) queries depended on
     * the call site.
     *
     * @param callSite
     */
    private void calleeAddedTo(/*ProgramPointReplica*/int callSite) {
        /*Set<OrderedPair<IMethod, Context>>*/IntSet meths = calleeMethodDependencies.get(callSite);
        if (meths != null) {
            IntIterator methIter = meths.intIterator();
            while (methIter.hasNext()) {
                /*OrderedPair<IMethod, Context>*/int m = methIter.next();
                // need to re-run the analysis of m
                computeReachabilityForMethod(m);
            }
        }

        ProgramPointReplica callSiteRep = g.lookupCallSiteReplicaDictionary(callSite);
        int caller = g.lookupCallGraphNodeDictionary(new OrderedPair<>(callSiteRep.getPP().getContainingProcedure(),
                                                                       callSiteRep.getContext()));
        Set<ProgramPointSubQuery> queries = calleeQueryDependencies.get(caller);
        if (queries != null) {
            Iterator<ProgramPointSubQuery> iter = queries.iterator();
            while (iter.hasNext()) {
                ProgramPointSubQuery mr = iter.next();
                calleeQueryRequests.incrementAndGet();
                // need to re-run the query of mr
                if (!requestRerunQuery(mr)) {
                    // whoops, no need to rerun this anymore.
                    iter.remove();
                }
            }
        }
    }

    /**
     * This method is invoked to let us know that a new edge in the call graph has been added that goes to the
     * callGraphNode
     *
     * We use this method to make sure that we re-run any queries that depended on the call site.
     *
     * @param callSite
     */
    private void callerAddedTo(/*OrderedPair<IMethod, Context>*/int callGraphNode) {
        Set<ProgramPointSubQuery> queries = callerQueryDependencies.get(callGraphNode);
        if (queries != null) {
            Iterator<ProgramPointSubQuery> iter = queries.iterator();
            while (iter.hasNext()) {
                ProgramPointSubQuery mr = iter.next();
                callerQueryRequests.incrementAndGet();
                // need to re-run the query of mr
                if (!requestRerunQuery(mr)) {
                    // whoops, no need to rerun this anymore.
                    iter.remove();
                }
            }
        }
    }

    private void methodSummaryChanged(/*OrderedPair<IMethod, Context>*/int cgNode) {
        /*Set<OrderedPair<IMethod, Context>>*/IntSet meths = methodMethodDependencies.get(cgNode);
        if (meths != null) {
            IntIterator methIter = meths.intIterator();
            while (methIter.hasNext()) {
                /*OrderedPair<IMethod, Context>*/int m = methIter.next();
                // need to re-run the analysis of m
                computeReachabilityForMethod(m);
            }
        }

        Set<ProgramPointSubQuery> queries = methodQueryDependencies.get(cgNode);
        if (queries != null) {
            Iterator<ProgramPointSubQuery> iter = queries.iterator();
            while (iter.hasNext()) {
                ProgramPointSubQuery mr = iter.next();
                methodQueryRequests.incrementAndGet();
                // need to re-run the query of mr
                if (!requestRerunQuery(mr)) {
                    // whoops, no need to rerun this anymore.
                    iter.remove();
                }
            }
        }
    }

    /**
     * Rerun the query mr if necessary. Will return true if the query was rerun, false if it did not need to be rerurn.
     *
     * @param mr
     */
    boolean requestRerunQuery(ProgramPointSubQuery mr) {
        totalRequests.incrementAndGet();
        if (this.positiveCache.contains(mr)) {
            // the query is already guaranteed to be true.
            cachedResponses.incrementAndGet();
            return false;
        }
        this.analysisHandle.submitReachabilityQuery(mr);
        return true;
    }

    /**
     * The result of the query changed, from negative to positive. Make sure to reprocess any StmtAndContexts that
     * depended on it, either by adding it to toReprocess, or giving it to the engine immediately.
     *
     */
    private void queryResultChanged(ProgramPointSubQuery mr) {
        assert this.positiveCache.contains(mr);

        // since the query is positive, it will never change in the future.
        // Let's save some memory by removing the set of dependent SaCs.
        Set<ReachabilityQueryOrigin> deps = this.queryDependencies.remove(mr);
        if (deps == null) {
            if (DEBUG) {
                System.err.println("NO DEPS " + mr);
            }
            // nothing to do.
            return;
        }
        // immediately execute the tasks that depended on this.
        if (DEBUG) {
            System.err.println("DEPS " + mr);
        }
        for (ReachabilityQueryOrigin task : deps) {
            if (DEBUG) {
                System.err.println("\tDEP: " + task);
            }
            task.trigger(this.analysisHandle);
        }
    }

    /**
     * This is invoked by the PointsToGraph to let us know that a new edge has been added to the call graph. This allows
     * us to retrigger computation as needed.
     *
     */
    public void addCallGraphEdge(/*ProgramPointReplica*/int callerSite, /*OrderedPair<IMethod, Context>*/
                                 int calleeCGNode) {

        this.calleeAddedTo(callerSite);
        this.callerAddedTo(calleeCGNode);
        this.relevantNodesIncrementalComputation.calleeAddedTo(callerSite);
        this.relevantNodesIncrementalComputation.callerAddedTo(calleeCGNode);
    }

    /**
     * Add a dependency that originatingSaC depends on the result of query.
     *
     * @param query
     * @param originatingSaC
     */
    private void addDependency(ProgramPointSubQuery query, ReachabilityQueryOrigin origin) {
        long start = System.currentTimeMillis();
        if (origin == null) {
            // nothing to do
            return;
        }
        Set<ReachabilityQueryOrigin> deps = this.queryDependencies.get(query);
        if (deps == null) {
            deps = AnalysisUtil.createConcurrentSet();
            Set<ReachabilityQueryOrigin> existing = queryDependencies.putIfAbsent(query, deps);
            if (existing != null) {
                deps = existing;
            }
        }
        deps.add(origin);
        queryDepTime.addAndGet(System.currentTimeMillis() - start);
    }

    /**
     * Takes a GraphDelta (representing changse to the PointsToGraph) and recomputes ProgramPointSubQuerys and
     * reachability info for methods that read any PointsToGraphNode that changed.
     *
     * @param delta
     */
    public void checkPointsToGraphDelta(GraphDelta delta) {
        IntIterator domainIter = delta.domainIterator();
        while (domainIter.hasNext()) {
            int n = domainIter.next();
            /*Set<OrderedPair<IMethod, Context>>*/IntSet meths = killMethodDependencies.get(n);
            if (meths != null) {
                IntIterator methIter = meths.intIterator();
                while (methIter.hasNext()) {
                    /*OrderedPair<IMethod, Context>*/int m = methIter.next();
                    // need to re-run the analysis of m
                    computeReachabilityForMethod(m);
                }
            }

            Set<ProgramPointSubQuery> queries = killQueryDependencies.get(n);
            if (queries != null) {
                Iterator<ProgramPointSubQuery> iter = queries.iterator();
                while (iter.hasNext()) {
                    ProgramPointSubQuery mr = iter.next();
                    killQueryRequests.incrementAndGet();
                    // need to re-run the query of mr
                    if (!requestRerunQuery(mr)) {
                        // whoops, no need to rerun this anymore.
                        iter.remove();
                    }
                }
            }
        }
    }

    public void processSubQuery(ProgramPointSubQuery sq) {
        this.computeQuery(Collections.singleton(sq.source), sq.destination, sq.noKill, sq.noAlloc, sq.forbidden);
    }

    public void processRelevantNodesQuery(RelevantNodesQuery rq) {
        this.relevantNodesIncrementalComputation.computeRelevantNodes(rq);
    }

    public void processSourceRelevantNodesQuery(SourceRelevantNodesQuery sq) {
        this.relevantNodesIncrementalComputation.computeSourceDependencies(sq);
    }

    /**
     * Clear caches containing results for queries that have already been computed
     */
    public void clearCaches() {
        System.err.println("Clearing reachability cache.");
        positiveCache = AnalysisUtil.createConcurrentSet();
        negativeCache = AnalysisUtil.createConcurrentSet();
        relevantNodesIncrementalComputation.clearCaches();
    }

    //***********************
    // Diagnostic info
    //***********************

    /**
     * Description of a single source program point reachability query.
     */
    private static final class DestQuery {
        final InterProgramPointReplica destination;
        final/*Set<PointsToGraphNode>*/IntSet noKill;
        final/*Set<InstanceKeyRecency>*/IntSet noAlloc;
        final Set<InterProgramPointReplica> forbidden;

        /**
         * Create a new sub query from source to destination
         *
         * @param source program point to search from
         * @param destination program point to find
         * @param noKill points-to graph nodes that must not be killed on a valid path from source to destination
         * @param noAlloc instance key that must not be allocated on a valid path from source to destination
         * @param forbidden program points that must not be traversed on a valid path from source to destination
         */
        DestQuery(InterProgramPointReplica destination, /*Set<PointsToGraphNode>*/
                             IntSet noKill, final/*Set<InstanceKeyRecency>*/IntSet noAlloc,
                             Set<InterProgramPointReplica> forbidden) {
            this.destination = destination;
            this.noKill = noKill;
            this.noAlloc = noAlloc;
            this.forbidden = forbidden;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = destination.hashCode();
            result = prime * result + noAlloc.size();
            result = prime * result + noKill.size();
            result = prime * result + forbidden.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DestQuery)) {
                return false;
            }
            DestQuery other = (DestQuery) obj;
            if (!destination.equals(other.destination)) {
                return false;
            }
            if (noAlloc.isEmpty() != other.noAlloc.isEmpty()) {
                return false;
            }
            if (!noAlloc.sameValue(other.noAlloc)) {
                return false;
            }
            if (noKill.isEmpty() != other.noKill.isEmpty()) {
                return false;
            }
            if (!noKill.sameValue(other.noKill)) {
                return false;
            }
            if (!forbidden.equals(other.forbidden)) {
                return false;
            }
            return true;
        }
    }

    private Set<InterProgramPointReplica> destinations = AnalysisUtil.createConcurrentSet();
    private Set<InterProgramPointReplica> sources = AnalysisUtil.createConcurrentSet();
    private Set<ProgramPointSubQuery> totalQueries = AnalysisUtil.createConcurrentSet();
    private Set<DestQuery> totalDestQueries = AnalysisUtil.createConcurrentSet();

    // Times
    private AtomicLong queryDepTime = new AtomicLong(0);
    private AtomicLong relevantNodesTime = new AtomicLong(0);
    private AtomicLong recordResultsTime = new AtomicLong(0);
    private AtomicLong totalTime = new AtomicLong(0);
    private AtomicLong destQueryTime = new AtomicLong(0);
    private AtomicLong recordMethodTime = new AtomicLong(0);
    private AtomicLong methodReachTime = new AtomicLong(0);
    private AtomicLong calleeDepTime = new AtomicLong(0);
    private AtomicLong callerDepTime = new AtomicLong(0);
    private AtomicLong methodDepTime = new AtomicLong(0);
    private AtomicLong killQueryDepTime = new AtomicLong(0);
    private AtomicLong killCallerDepTime = new AtomicLong(0);
    private AtomicLong methodCalleeDepTime = new AtomicLong(0);
    private AtomicLong methodCallerDepTime = new AtomicLong(0);

    // Reachability counts
    private AtomicInteger totalRequests = new AtomicInteger(0);
    private AtomicInteger cachedResponses = new AtomicInteger(0);
    private AtomicInteger computedResponses = new AtomicInteger(0);
    // Method reachability counts
    private AtomicInteger totalMethodReach = new AtomicInteger(0);
    private AtomicInteger cachedMethodReach = new AtomicInteger(0);
    private AtomicInteger computedMethodReach = new AtomicInteger(0);
    // Destination query counts
    private AtomicInteger totalDestQuery = new AtomicInteger(0);
    private AtomicInteger cachedDestQuery = new AtomicInteger(0);
    private AtomicInteger computedDestQuery = new AtomicInteger(0);
    // Dependency counts
    private AtomicInteger calleeQueryRequests = new AtomicInteger(0);
    private AtomicInteger callerQueryRequests = new AtomicInteger(0);
    private AtomicInteger killQueryRequests = new AtomicInteger(0);
    private AtomicInteger methodQueryRequests = new AtomicInteger(0);
    private AtomicInteger reachabilityRequests = new AtomicInteger(0);
    AtomicInteger relevantRequests = new AtomicInteger(0);

    private void printDiagnostics() {
        StringBuffer sb = new StringBuffer();
        sb.append("\n%%%%%%%%%%%%%%%%% REACHABILITY STATISTICS %%%%%%%%%%%%%%%%%\n");
        sb.append("\nTotal requests: " + totalRequests + "  ;  " + cachedResponses + "  cached " + computedResponses
                + " computed (" + (int) (100 * (cachedResponses.floatValue() / totalRequests.floatValue()))
                + "% hit rate)\n");
        sb.append("Total method requests: " + totalMethodReach + "  ;  " + cachedMethodReach + "  cached "
                + computedMethodReach + " computed ("
                + (int) (100 * (cachedMethodReach.floatValue() / totalMethodReach.floatValue())) + "% hit rate)\n");
        sb.append("Total dest subquery requests: " + totalDestQuery + "  ;  " + cachedDestQuery + "  cached "
                + computedDestQuery + " computed ("
                + (int) (100 * (cachedDestQuery.floatValue() / totalDestQuery.floatValue())) + "% hit rate)\n");

        double analysisTime = (System.currentTimeMillis() - PointsToAnalysis.startTime) / 1000.0;
        double total = totalTime.get() / 1000.0;
        double relevantNodes = relevantNodesTime.get() / 1000.0;
        double methodReach = methodReachTime.get() / 1000.0;
        double destQuery = destQueryTime.get() / 1000.0;

        double recordResults = recordResultsTime.get() / 1000.0;
        double recordMethod = recordMethodTime.get() / 1000.0;

        double queryDep = queryDepTime.get() / 1000.0;
        double calleeDep = calleeDepTime.get() / 1000.0;
        double callerDep = callerDepTime.get() / 1000.0;
        double methodDep = methodDepTime.get() / 1000.0;
        double killQueryDep = killQueryDepTime.get() / 1000.0;
        double killCallerDep = killCallerDepTime.get() / 1000.0;
        double methodCalleeDep = methodCalleeDepTime.get() / 1000.0;
        double methodCallerDep = methodCallerDepTime.get() / 1000.0;

        double sourceCount = sources.size();
        double destCount = destinations.size();
        double queryCount = totalQueries.size();
        double destQueryCount = totalDestQueries.size();
        double calleeQueryRequestCount = calleeQueryRequests.get();
        double callerQueryRequestCount = callerQueryRequests.get();
        double killQueryRequestCount = killQueryRequests.get();
        double methodQueryRequestCount = methodQueryRequests.get();
        double relevantRequestCount = relevantRequests.get();
        double reachabilityRequestCount = reachabilityRequests.get();

        sb.append("REACHABILITY QUERY EXECUTION\n");
        sb.append("\tTotal: " + analysisTime + "s;\n");
        // Multiply by the number of threads to get the right ratios
        analysisTime *= analysisHandle.numThreads();
        sb.append("\tReachability: " + total + "s; RATIO: " + total / analysisTime + "\n");
        sb.append("\tRelevant: " + relevantNodes + "s; RATIO: " + relevantNodes / analysisTime + "\n");
        sb.append("\tMethod: " + methodReach + "s; RATIO: " + methodReach / analysisTime + "\n");
        sb.append("\tDestination: " + destQuery + "s; RATIO: " + destQuery / analysisTime + "\n");
        sb.append("\tTotal sources: " + sourceCount + " " + (sourceCount / computedDestQuery.get())
                + " per computed sub query\n");
        sb.append("\tTotal destinations: " + destCount + " " + (destCount / computedDestQuery.get())
                + " per computed sub query\n");
        sb.append("\tTotal sub queries: " + queryCount + " " + (queryCount / computedDestQuery.get())
                + " per computed sub query\n");
        sb.append("\tTotal queries with same dest noKill etc: " + destQueryCount + " "
                + (destQueryCount / computedDestQuery.get())
                + " per computed sub query\n");
        sb.append("RECORD RESULTS" + "\n");
        sb.append("\tQuery results: " + recordResults + "s; RATIO: " + recordResults / analysisTime + "\n");
        sb.append("\tMethod Query results: " + recordMethod + "s; RATIO: " + recordMethod / analysisTime + "\n");
        sb.append("RECORD DEPENDENCIES" + "\n");
        sb.append("\tQuery deps: " + queryDep + "s; RATIO: " + queryDep / analysisTime + "\n");
        sb.append("\tCallee deps: " + calleeDep + "s; RATIO: " + calleeDep / analysisTime + "\n");
        sb.append("\tCaller deps: " + callerDep + "s; RATIO: " + callerDep / analysisTime + "\n");
        sb.append("\tMethod deps: " + methodDep + "s; RATIO: " + methodDep / analysisTime + "\n");
        sb.append("\tKill query deps: " + killQueryDep + "s; RATIO: " + killQueryDep / analysisTime + "\n");
        sb.append("\tKill caller deps: " + killCallerDep + "s; RATIO: " + killCallerDep / analysisTime + "\n");
        sb.append("\tMethod callee deps: " + methodCalleeDep + "s; RATIO: " + methodCalleeDep / analysisTime + "\n");
        sb.append("\tMethod caller deps: " + methodCallerDep + "s; RATIO: " + methodCallerDep / analysisTime + "\n");
        sb.append("TRIGGERED DEPENDENCIES" + "\n");
        sb.append("\tcalleeQueryRequests: " + calleeQueryRequestCount + " RATIO: "
                + (calleeQueryRequestCount / totalRequests.get()) + "\n");
        sb.append("\tcallerQueryRequests: " + callerQueryRequestCount + " RATIO: "
                + (callerQueryRequestCount / totalRequests.get()) + "\n");
        sb.append("\tkillQueryRequests: " + killQueryRequestCount + " RATIO: "
                + (killQueryRequestCount / totalRequests.get()) + "\n");
        sb.append("\tmethodQueryRequests: " + methodQueryRequestCount + " RATIO: "
                + (methodQueryRequestCount / totalRequests.get()) + "\n");
        sb.append("\treachabilityRequests: " + reachabilityRequestCount + " RATIO: "
                + (reachabilityRequestCount / totalRequests.get()) + "\n");
        sb.append("\trelevantNodeRequests: " + relevantRequestCount + " RATIO: "
                + (relevantRequestCount / totalRequests.get()) + "\n");
        sb.append("\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n");
        System.err.println(sb.toString());
    }
}
