package analysis.pointer.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import util.OrderedPair;
import util.intmap.ConcurrentIntMap;
import util.intmap.IntMap;
import util.intset.EmptyIntSet;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.graph.MethodReachability.MethodSummaryKillAndAlloc;
import analysis.pointer.graph.MethodReachability.MethodSummaryKillAndAllocChanges;
import analysis.pointer.graph.ProgramPointSubQuery.QueryCacheKey;
import analysis.pointer.statements.LocalToFieldStatement;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

/**
 * This class answers questions about what programs are reachable from what other program points, and caches answers
 * smartly.
 */
public final class ProgramPointReachability {
    /**
     * Whether to incrementally print diagnostic timing and count information
     */
    public static final boolean PRINT_DIAGNOSTICS = false;
    /**
     * If printing diagnostics, reset all the counts after every print.
     */
    private static final boolean PRINT_AND_RESET = false;

    /**
     * Print debug info
     */
    public static final boolean DEBUG = false;

    /**
     * Print more debug info
     */
    public static final boolean DEBUG2 = false;

    /**
     * Should we use tunnels to the destination?
     */
    public static final boolean USE_TUNNELS = true;

    /**
     * Keep a reference to the PointsToGraph for convenience.
     */
    private final PointsToGraph g;

    /**
     * A reference to allow us to submit a subquery for reprocessing
     */
    private final PointsToAnalysisHandle analysisHandle;

    /**
     * Used to compute and summarize the reachability information for methods.
     */
    public final MethodReachability methodReachability;

    /**
     * Used to check whether one call graph node is reachable from another
     */
    private final CallGraphReachability callGraphReachability;

    /**
     * Call sites with no receivers that will be approximating as terminating normally for the purposes of program point
     * reachability
     */
    private final ApproximateCallSitesAndFieldAssignments approx;

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
        this.approx = new ApproximateCallSitesAndFieldAssignments(g);
        this.methodReachability = new MethodReachability(this, g, analysisHandle);
        if (CallGraphReachability.USE_CALL_GRAPH_REACH) {
            this.callGraphReachability = new CallGraphReachability(this, g);
        }
        else {
            this.callGraphReachability = null;
        }
    }

    /**
     * Call sites and field assignments with no receivers that will be approximated for the purposes of program point
     * reachability.
     *
     * @return approximated call sites and field assignments and algorithm for finding more
     */
    public ApproximateCallSitesAndFieldAssignments getApproximateCallSitesAndFieldAssigns() {
        return approx;
    }

    /**
     * Get the data structure used to compute whether once call graph node is reachable from another via method calls
     *
     * @return call graph reachability data structure and algorithm
     */
    public CallGraphReachability getCallGraphReachability() {
        return callGraphReachability;
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
     * InstanceKey in noAlloc, and without going through any of the forbidden IPPRs? If forbidden is non empty, then all
     * of the forbidden IPPRs must be in the same method and context as one of the source or the destination.
     *
     * @return true if the destination is reachable from anys source
     */
    private boolean reachableImpl(Collection<InterProgramPointReplica> sources, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc,
                                  Set<InterProgramPointReplica> forbidden, ReachabilityQueryOrigin origin) {
        long start = 0L;
        if (PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
            totalRequests.incrementAndGet();
        }
        assert allMostRecent(noAlloc);
        assert allInSameMethodAndContext(forbidden, sources, destination);

        // Uncomment for debugging output for a particular destination
        //        if (destination.toString().contains("**17558_pre**")) {
        //            DEBUG = true;
        //            DEBUG2 = true;
        //        }

        if (DEBUG) {
            if (sources.isEmpty()) {

                System.err.println("PPR%%NO SOURCES " + " -> " + destination);
                System.err.println("PPR%%\tNO KILL " + noKill);
                System.err.println("PPR%%\tNO ALLOC " + noAlloc);
                System.err.println("PPR%%\tforbidden " + forbidden);
                System.err.println("PPR%%\tORIGIN " + origin);
            }
        }

        // check the caches
        List<InterProgramPointReplica> unknown = new ArrayList<>(sources.size());
        for (InterProgramPointReplica src : sources) {
            int currentQuery = ProgramPointSubQuery.lookupDictionary(src,
                                                                     destination,
                                                                     noKill,
                                                                     noAlloc,
                                                                     forbidden,
                                                                     origin);
            QueryCacheKey key = ProgramPointSubQuery.lookupDictionary(currentQuery).getCacheKey();
            boolean foundInCache = false;
            boolean done;
            do {
                // Be default, we will do this loop once, but in a certain race
                // we may need to run it again.
                done = true;

                if (this.positiveCache.contains(key)) {
                    // We have already computed that the destination is reachable from src
                    if (PRINT_DIAGNOSTICS) {
                        cachedResponses.incrementAndGet();
                    }
                    for (InterProgramPointReplica s : sources) {
                        int query = ProgramPointSubQuery.lookupDictionary(s,
                                                                          destination,
                                                                          noKill,
                                                                          noAlloc,
                                                                          forbidden,
                                                                          origin);

                        ProgramPointSubQuery sq = ProgramPointSubQuery.lookupDictionary(query);
                        sq.setExpired();
                    }

                    return true;
                }
                if (this.negativeCache.contains(key)) {
                    // it's a negative result! But we need ot have added
                    // a dependency before getting a hit in the negative cache, in order
                    // to make sure the dependency is registered before the cache is updated.
                    // So after adding the dependency we check the cache again.
                    addQueryDependency(key, currentQuery);
                    if (this.negativeCache.contains(key)) {
                        // We know it's a negative result for this one, keep looking
                        if (DEBUG) {
                            System.err.println("PPR%%negative cache " + src + " -> " + destination);
                        }
                        foundInCache = true;
                    }
                    else {
                        // we got a rare race condition, where after adding the dependency
                        // the result change from negative to positive.
                        // Set done to false, so that we re-check the positive cache.
                        done = false;
                    }
                }
            } while (!done);

            if (!foundInCache) {
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
            if (PRINT_DIAGNOSTICS) {
                cachedResponses.incrementAndGet();
            }
            return false;
        }
        // The cache didn't help. Try getting an answer for the unknown elements.
        if (PRINT_DIAGNOSTICS) {
            this.reachabilityRequests.incrementAndGet();
        }
        boolean b = computeQuery(unknown, destination, noKill, noAlloc, forbidden, origin);

        if (b) {
            for (InterProgramPointReplica s : sources) {
                int query = ProgramPointSubQuery.lookupDictionary(s, destination, noKill, noAlloc, forbidden, origin);
                ProgramPointSubQuery sq = ProgramPointSubQuery.lookupDictionary(query);
                sq.setExpired();
            }
        }

        if (PRINT_DIAGNOSTICS) {
            reachableImplTime.addAndGet(System.currentTimeMillis() - start);
        }
        return b;
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
                    // OK! (note, we assigned agreement in the boolean test above)
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
     * @param origin
     *
     * @return true if the destination is reachable from anys source
     */
    private boolean computeQuery(Collection<InterProgramPointReplica> sources, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc,
                                 Set<InterProgramPointReplica> forbidden, ReachabilityQueryOrigin origin) {
        // Uncomment for debugging output for a particular destination
        //        if (destination.toString().contains("**17558_pre**")) {
        //            DEBUG = true;
        //            DEBUG2 = true;
        //        }

        //        this.destinations.add(destination);
        long start = 0L;
        if (PRINT_DIAGNOSTICS) {
            int resp = this.computedResponses.incrementAndGet();
            if (resp % 1000000 == 0) {
                printDiagnostics();
            }
            start = System.currentTimeMillis();
        }
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
        }

        // try to solve it for each source.
        for (InterProgramPointReplica src : sources) {
            int queryInt = ProgramPointSubQuery.lookupDictionary(src, destination, noKill, noAlloc, forbidden, origin);
            ProgramPointSubQuery query = ProgramPointSubQuery.lookupDictionary(queryInt);
            QueryCacheKey key = query.getCacheKey();
            if (PRINT_DIAGNOSTICS) {
                totalDestQuery.incrementAndGet();
            }

            if (positiveCache.contains(key)) {
                // The result was computed by another thread before this thread ran
                recordQueryResult(queryInt, true);
                if (PRINT_DIAGNOSTICS) {
                    cachedDestQuery.incrementAndGet();
                }
                return true;
            }

            // Now try a search starting at the source
            long startDest = 0L;
            if (PRINT_DIAGNOSTICS) {
                startDest = System.currentTimeMillis();
            }

            // This query will be awakened on a change and is responsible for notifying any dependencies
            query.setCacheRepresentative(true);
            boolean found = prq.executeSubQuery(src, origin);

            if (PRINT_DIAGNOSTICS) {
                destQueryTime.addAndGet(System.currentTimeMillis() - startDest);
                computedDestQuery.incrementAndGet();
            }

            if (found) {
                recordQueryResult(queryInt, true);
                if (DEBUG) {
                    System.err.println("PPR%%\t" + sources + " -> " + destination);
                    System.err.println("PPR%%\ttrue because query returned true");
                }

                if (PRINT_DIAGNOSTICS) {
                    totalTime.addAndGet(System.currentTimeMillis() - start);
                }
                return true;
            }
            if (DEBUG) {
                System.err.println("PPR%%\t" + sources + " -> " + destination);
                System.err.println("PPR%%\tfalse because query returned false");
            }
            if (recordQueryResult(queryInt, false)) {
                // We computed false, but the cache already had true
                if (DEBUG) {
                    System.err.println("PPR%%\t" + sources + " -> " + destination);
                    System.err.println("PPR%%\ttrue because cache already had true (2)");
                }
                if (PRINT_DIAGNOSTICS) {
                    totalTime.addAndGet(System.currentTimeMillis() - start);
                }
                return true;
            }
        }

        // we didn't find it.
        if (DEBUG) {
            System.err.println("PPR%%\t" + " -> " + destination);
            System.err.println("PPR%%\tAll sources false");
        }
        if (PRINT_DIAGNOSTICS) {
            totalTime.addAndGet(System.currentTimeMillis() - start);
        }

        // we still didn't find it. Mark the destination as interesting, since we would benefit from using tunnels.
        this.methodReachability.addInterestingDestination(prq.destinationCGNode);
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
    private boolean recordQueryResult(/*ProgramPointSubQuery*/int query, boolean b) {
        long start = System.currentTimeMillis();
        ProgramPointSubQuery sq = ProgramPointSubQuery.lookupDictionary(query);
        QueryCacheKey key = sq.getCacheKey();
        if (b) {
            positiveCache.add(key);
            if (negativeCache.remove(key)) {
                // we previously thought it was negative.
                queryResultChanged(key);
            }
            if (PRINT_DIAGNOSTICS) {
                recordResultsTime.addAndGet(System.currentTimeMillis() - start);
            }
            return true;
        }

        // Recording a false result
        addQueryDependency(key, query);
        negativeCache.add(key);
        if (positiveCache.contains(key)) {
            if (negativeCache.remove(key)) {
                // we previously thought it was negative.
                queryResultChanged(key);
            }
            // A positive result has already been computed return it
            if (PRINT_DIAGNOSTICS) {
                recordResultsTime.addAndGet(System.currentTimeMillis() - start);
            }
            return true;
        }
        if (PRINT_DIAGNOSTICS) {
            recordResultsTime.addAndGet(System.currentTimeMillis() - start);
        }
        return false;
    }

    /**
     * Add a dependency that the origin in a query depends on the result of query.
     *
     * @param key key into the cache for which results may change
     * @param query containing the origin to be triggered if the query results change
     */
    private void addQueryDependency(QueryCacheKey key, /*ProgramPointSubQuery*/int query) {
        long start = 0L;
        if (PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
        }
        /*Set<ProgramPointSubQuery>*/MutableIntSet deps = this.queryDependencies.get(key);
        if (deps == null) {
            deps = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = queryDependencies.putIfAbsent(key, deps);
            if (existing != null) {
                deps = existing;
            }
        }
        deps.add(query);
        if (PRINT_DIAGNOSTICS) {
            queryDepTime.addAndGet(System.currentTimeMillis() - start);
        }
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

    public static final MutableIntSet nonEmptyApproximatedCallSites = AnalysisUtil.createConcurrentIntSet();
    public static final Set<OrderedPair<PointsToStatement, Context>> nonEmptyApproximatedKillSets = AnalysisUtil.createConcurrentSet();


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
    private Set<QueryCacheKey> positiveCache = AnalysisUtil.createConcurrentSet();
    private Set<QueryCacheKey> negativeCache = AnalysisUtil.createConcurrentSet();

    /* *****************************************************************************
     *
     * DEPENDENCY TRACKING
     *
     * The following code is responsible for recording dependencies
     */
    /**
     * Sub queries depend on the callees from a particular call site (ProgramPointReplica)
     */
    // ConcurrentMap<ProgramPointReplica, Set<ProgramPointSubQuery>>
    private final ConcurrentIntMap<MutableIntSet> calleeQueryDependencies = AnalysisUtil.createConcurrentIntMap();
    /**
     * Sub queries depend on the callers of a particular call graph node (OrderedPair<IMethod, Context>)
     */
    // ConcurrentMap<OrderedPair<IMethod, Context>, Set<ProgramPointSubQuery>>
    private final ConcurrentIntMap<MutableIntSet> callerQueryDependencies = AnalysisUtil.createConcurrentIntMap();
    /**
     * Sub queries depending on the (normal or exceptional) results of a method reachability.
     */
    // ConcurrentMap<OrderedPair<IMethod, Context>, Set<ProgramPointSubQuery>>
    private final ConcurrentIntMap<MutableIntSet> methodResultQueryDependencies = AnalysisUtil.createConcurrentIntMap();

    /**
     * Sub queries depending on a tunnel from one call graph node to another.
     */
    // ConcurrentMap<OrderedPair<IMethod, Context>, Map<OrderedPair<IMethod, Context>, <ProgramPointSubQuery>>
    private final ConcurrentIntMap<ConcurrentIntMap<MutableIntSet>> methodTunnelQueryDependencies = AnalysisUtil.createConcurrentIntMap();

    /**
     * Sub queries depend on the killset at a particular PointsToGraphNode
     */
    // ConcurrentIntMap<Set<ProgramPointSubQuery>>
    private final ConcurrentIntMap<MutableIntSet> killQueryDependencies = AnalysisUtil.createConcurrentIntMap();

    /**
     * Record the fact that the result of query depends on the callees of caller, and thus, if the callees change, then
     * query may need to be reevaluated.
     *
     * @param query
     * @param caller
     */
    void addCalleeQueryDependency(/*ProgramPointSubQuery*/int query, /*OrderedPair<IMethod, Context>*/int caller) {
        long start = 0L;
        if (PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
        }
        MutableIntSet s = calleeQueryDependencies.get(caller);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = calleeQueryDependencies.putIfAbsent(caller, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
        if (PRINT_DIAGNOSTICS) {
            calleeDepTime.addAndGet(System.currentTimeMillis() - start);
        }
    }

    /**
     * query needs to be re-run if there is a new caller of callGraphNode.
     *
     * @param query
     * @param callGraphNode
     */
    void addCallerQueryDependency(/*ProgramPointSubQuery*/int query, /*OrderedPair<IMethod, Context>*/
                                  int callGraphNode) {
        long start = 0L;
        if (PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
        }
        MutableIntSet s = callerQueryDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = callerQueryDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
        if (PRINT_DIAGNOSTICS) {
            callerDepTime.addAndGet(System.currentTimeMillis() - start);
        }
    }

    /**
     * We need to reanalyze the method results for (m, context) if the reachability results for callGraphNode changes.
     *
     * @param query
     * @param callGraphNode
     */
    void addMethodResultQueryDependency(/*ProgramPointSubQuery*/int query, /*OrderedPair<IMethod, Context>*/
                                        int callGraphNode) {
        long start = 0L;
        if (PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
        }
        MutableIntSet s = methodResultQueryDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = methodResultQueryDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
        if (PRINT_DIAGNOSTICS) {
            methodDepTime.addAndGet(System.currentTimeMillis() - start);
        }
    }

    /**
     * We need to reanalyze the method results for (m, context) if the tunnel from callGraphNode to destCallGraphNode
     * changes
     */
    void addMethodTunnelQueryDependency(/*ProgramPointSubQuery*/int query, /*OrderedPair<IMethod, Context>*/
                                        int callGraphNode, int destCallGraphNode) {
        long start = 0L;
        if (PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
        }
        ConcurrentIntMap<MutableIntSet> m = methodTunnelQueryDependencies.get(callGraphNode);
        if (m == null) {
            m = AnalysisUtil.createConcurrentIntMap();
            ConcurrentIntMap<MutableIntSet> existing = methodTunnelQueryDependencies.putIfAbsent(callGraphNode, m);
            if (existing != null) {
                m = existing;
            }
        }
        MutableIntSet s = m.get(destCallGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = m.putIfAbsent(destCallGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
        if (PRINT_DIAGNOSTICS) {
            methodDepTime.addAndGet(System.currentTimeMillis() - start);
        }
    }

    void addKillQueryDependency(/*ProgramPointSubQuery*/int query, ReferenceVariableReplica readDependencyForKillField) {
        long start = 0L;
        if (PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
        }
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);
        MutableIntSet s = killQueryDependencies.get(n);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = killQueryDependencies.putIfAbsent(n, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
        if (PRINT_DIAGNOSTICS) {
            killQueryDepTime.addAndGet(System.currentTimeMillis() - start);
        }
    }

    void removeKillQueryDependency(/*ProgramPointSubQuery*/int query,
                                   ReferenceVariableReplica readDependencyForKillField) {
        long start = 0L;
        if (PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
        }
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);

        MutableIntSet s = killQueryDependencies.get(n);
        if (s != null) {
            s.remove(query);
        }
        if (PRINT_DIAGNOSTICS) {
            killQueryDepTime.addAndGet(System.currentTimeMillis() - start);
        }
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
        ProgramPointReplica callSiteRep = g.lookupCallSiteReplicaDictionary(callSite);
        int caller = g.lookupCallGraphNodeDictionary(new OrderedPair<>(callSiteRep.getPP().getContainingProcedure(),
                                                                       callSiteRep.getContext()));
        MutableIntSet queries = calleeQueryDependencies.remove(caller);
        if (queries != null) {
            IntIterator iter = queries.intIterator();
            MutableIntSet toRemove = MutableSparseIntSet.createMutableSparseIntSet(2);
            while (iter.hasNext()) {
                int mr = iter.next();
                if (PRINT_DIAGNOSTICS) {
                    calleeQueryRequests.incrementAndGet();
                }
                // need to re-run the query of mr
                if (!requestRerunQuery(mr)) {
                    // whoops, no need to rerun this anymore.
                    toRemove.add(mr);
                }
            }

            // Now remove all the unneeded queries
            IntIterator removeIter = toRemove.intIterator();
            while (removeIter.hasNext()) {
                queries.remove(removeIter.next());
            }
        }
    }

    /**
     * Add a call site with no callees to be approximated as terminating normally
     *
     * @param callSite call site to be approximated
     */
    public void addApproximateCallSite(int callSite) {
        this.methodReachability.addApproximateCallSite(callSite);

        ProgramPointReplica callSiteRep = g.lookupCallSiteReplicaDictionary(callSite);
        int caller = g.lookupCallGraphNodeDictionary(new OrderedPair<>(callSiteRep.getPP().getContainingProcedure(),
                                                                       callSiteRep.getContext()));
        MutableIntSet queries = calleeQueryDependencies.remove(caller);
        if (queries != null) {
            IntIterator iter = queries.intIterator();
            MutableIntSet toRemove = MutableSparseIntSet.createMutableSparseIntSet(2);
            while (iter.hasNext()) {
                int mr = iter.next();
                if (PRINT_DIAGNOSTICS) {
                    calleeQueryRequests.incrementAndGet();
                }
                // need to re-run the query of mr
                if (!requestRerunQuery(mr)) {
                    // whoops, no need to rerun this anymore.
                    toRemove.add(mr);
                }
            }

            // Now remove all the unneeded queries
            IntIterator removeIter = toRemove.intIterator();
            while (removeIter.hasNext()) {
                queries.remove(removeIter.next());
            }
        }
    }

    /**
     * Add a field assignment with no receivers assumed to have an empty kill set
     *
     * @param fieldAssign field assignment to be approximated
     */
    public void addApproximateFieldAssign(StmtAndContext fieldAssign) {
        assert fieldAssign.stmt instanceof LocalToFieldStatement;
        ReferenceVariableReplica killDep = fieldAssign.stmt.getReadDependencyForKillField(fieldAssign.context,
                                                                                          g.getHaf());
        int killDepInt = g.lookupDictionary(killDep);
        this.methodReachability.addApproximateFieldAssign(killDepInt);

        MutableIntSet queries = killQueryDependencies.remove(killDepInt);
        if (queries != null) {
            IntIterator iter = queries.intIterator();
            MutableIntSet toRemove = MutableSparseIntSet.createMutableSparseIntSet(2);
            while (iter.hasNext()) {
                int mr = iter.next();
                if (PRINT_DIAGNOSTICS) {
                    killQueryRequests.incrementAndGet();
                }
                // need to re-run the query of mr
                if (!requestRerunQuery(mr)) {
                    // whoops, no need to rerun this anymore.
                    toRemove.add(mr);
                }
            }

            // Now remove all the unneeded queries
            IntIterator removeIter = toRemove.intIterator();
            while (removeIter.hasNext()) {
                queries.remove(removeIter.next());
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
        MutableIntSet queries = callerQueryDependencies.remove(callGraphNode);
        if (queries != null) {
            IntIterator iter = queries.intIterator();
            MutableIntSet toRemove = MutableSparseIntSet.createMutableSparseIntSet(2);
            while (iter.hasNext()) {
                int mr = iter.next();
                if (PRINT_DIAGNOSTICS) {
                    callerQueryRequests.incrementAndGet();
                }
                // need to re-run the query of mr
                if (!requestRerunQuery(mr)) {
                    // whoops, no need to rerun this anymore.
                    toRemove.add(mr);
                }
            }

            // Now remove all the unneeded queries
            IntIterator removeIter = toRemove.intIterator();
            while (removeIter.hasNext()) {
                queries.remove(removeIter.next());
            }
        }
    }

    void methodSummariesChanged(/*Map<OrderedPair<IMethod, Context>,MethodSummaryKillAndAllocChanges>*/IntMap<MethodSummaryKillAndAllocChanges> allChanges) {
        MutableIntSet queriesToRerun = MutableSparseIntSet.makeEmpty();
        IntIterator cgNodes = allChanges.keyIterator();
        while (cgNodes.hasNext()) {

            int cgNode = cgNodes.next();
            MethodSummaryKillAndAllocChanges changes = allChanges.get(cgNode);
            if (changes.resultsChanged()) {
                MutableIntSet queries = methodResultQueryDependencies.remove(cgNode);
                if (queries != null) {
                    queriesToRerun.addAll(queries);
                }
            }

            if (changes.tunnelsChanged()) {
                ConcurrentIntMap<MutableIntSet> m = this.methodTunnelQueryDependencies.get(cgNode);
                if (m != null) {
                    IntIterator changedTunnels = changes.changedTunnels().keyIterator();
                    while (changedTunnels.hasNext()) {
                        int destCGNode = changedTunnels.next();
                        IntSet s = m.remove(destCGNode);
                        if (s != null) {
                            queriesToRerun.addAll(s);
                        }
                    }
                }
            }
        }
        IntIterator iter = queriesToRerun.intIterator();
        while (iter.hasNext()) {
            int mr = iter.next();
            if (PRINT_DIAGNOSTICS) {
                methodQueryRequests.incrementAndGet();
            }
            // need to re-run the query of mr
            requestRerunQuery(mr);
        }
    }

    /**
     * Rerun the ProgramPointSubQuery ppSubQuery if necessary. Will return true if the query was rerun, false if it did
     * not need to be rerurn.
     *
     * @param ppSubQuery query to rerun
     */
    boolean requestRerunQuery(/*ProgramPointSubQuery*/int ppSubQuery) {
        if (PRINT_DIAGNOSTICS) {
            totalRequests.incrementAndGet();
        }
        ProgramPointSubQuery query = ProgramPointSubQuery.lookupDictionary(ppSubQuery);
        QueryCacheKey key = query.getCacheKey();
        if (this.positiveCache.contains(key)) {
            // the query is already guaranteed to be true.
            if (PRINT_DIAGNOSTICS) {
                cachedResponses.incrementAndGet();
            }
            return false;
        }
        this.analysisHandle.submitReachabilityQuery(query);
        return true;
    }

    /**
     * Reachability origins that depend on a particular cached value
     */
    // ConcurrentMap<QueryCacheKey, Set<ProgramPointSubQuery>>
    private final ConcurrentMap<QueryCacheKey, MutableIntSet> queryDependencies = AnalysisUtil.createConcurrentHashMap();

    /**
     * The result of the query changed, from negative to positive. Make sure to reprocess any StmtAndContexts that
     * depended on it, either by adding it to toReprocess, or giving it to the engine immediately.
     *
     */
    private void queryResultChanged(QueryCacheKey key) {
        assert this.positiveCache.contains(key);

        // since the query is positive, it will never change in the future.
        // Let's save some memory by removing the set of dependent SaCs.
        /*Set<ProgramPointSubQuery>*/MutableIntSet deps = this.queryDependencies.remove(key);
        if (deps == null) {
            if (DEBUG) {
                System.err.println("NO DEPS " + key);
            }
            // nothing to do.
            return;
        }

        if (DEBUG) {
            if (deps.isEmpty()) {
                System.err.println("\tEMPTY DEPS");
            }
        }

        // immediately execute the tasks that depended on this.
        IntIterator iter = deps.intIterator();
        while (iter.hasNext()) {
            ReachabilityQueryOrigin task = ProgramPointSubQuery.lookupDictionary(iter.next()).origin;
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
        if (CallGraphReachability.USE_CALL_GRAPH_REACH) {
            this.callGraphReachability.addCallGraphEdge(callerSite, calleeCGNode);
        }
        this.methodReachability.addCallGraphEdge(callerSite);
        this.calleeAddedTo(callerSite);
        this.callerAddedTo(calleeCGNode);
    }

    /**
     * Takes a GraphDelta (representing changse to the PointsToGraph) and recomputes ProgramPointSubQuerys and
     * reachability info for methods that read any PointsToGraphNode that changed.
     *
     * @param delta
     */
    public void checkPointsToGraphDelta(GraphDelta delta) {
        this.methodReachability.checkPointsToGraphDelta(delta);
        IntIterator domainIter = delta.domainIterator();
        while (domainIter.hasNext()) {
            int n = domainIter.next();
            MutableIntSet queries = killQueryDependencies.remove(n);
            if (queries != null) {
                IntIterator iter = queries.intIterator();
                MutableIntSet toRemove = MutableSparseIntSet.createMutableSparseIntSet(2);
                while (iter.hasNext()) {
                    int mr = iter.next();
                    if (PRINT_DIAGNOSTICS) {
                        killQueryRequests.incrementAndGet();
                    }
                    // need to re-run the query of mr
                    if (!requestRerunQuery(mr)) {
                        // whoops, no need to rerun this anymore.
                        toRemove.add(mr);
                    }
                }

                // Now remove all the unneeded queries
                IntIterator removeIter = toRemove.intIterator();
                while (removeIter.hasNext()) {
                    queries.remove(removeIter.next());
                }
            }
        }
    }

    public void processMethodReachabilityRecomputation(/*Set<OrderedPair<IMethod, Context>>*/MutableIntSet toRecompute) {
        this.methodReachability.processMethodReachabilityRecomputation(toRecompute);
    }
    public AtomicInteger numExpired = new AtomicInteger(0);

    public void processSubQuery(ProgramPointSubQuery sq) {
        if (sq.isExpired()) {
            if (!sq.isCacheRepresentative()) {
                // Don't rerun expired queries unless other queries are relying on the results
                if (PRINT_DIAGNOSTICS) {
                    numExpired.incrementAndGet();
                }
                return;
            }
            QueryCacheKey key = sq.getCacheKey();

            // Other queries may be relying on the results of this one since it is the representative in the negative cache
            // Whether the query should be rerun even though it is expired
            boolean rerun = false;

            MutableIntSet deps = queryDependencies.get(key);
            if (deps == null) {
                // no dependencies!
                // But we still need to check for races...
            }
            else {
                /*Iterator<ProgramPointSubQuery>*/IntIterator iter = deps.intIterator();
                // Set of expired queries to remove when iteration is done
                MutableIntSet toRemove = MutableSparseIntSet.makeEmpty();
                while (iter.hasNext()) {
                    int dep = iter.next();
                    ProgramPointSubQuery depQuery = ProgramPointSubQuery.lookupDictionary(dep);
                    if (!depQuery.isExpired()) {
                        // At least one of the dependencies has not yet expired.
                        // We definitely need to rerun the query.
                        rerun = true;
                        break;
                    }
                    // no need to keep expired dependencies
                    toRemove.add(dep);
                }

                // Remove expired dependencies
                IntIterator remove = toRemove.intIterator();
                while (remove.hasNext()) {
                    deps.remove(remove.next());
                }
            }
            if (!rerun) {
                // As far as we know, all of the dependencies are expired.
                // But there may have been races, if something added itself to the dependencies
                // while we were checking.

                // So, we will remove the key from the negative cache (so that any newly arriving
                // queries will be sure to execute), and remove the dependencies to make sure
                // that we are checking them thoroughly.
                negativeCache.remove(key);

                // Remove the dependencies and double check that they are all expired
                deps = queryDependencies.remove(sq.getCacheKey());
                if (deps != null) {
                    /*Iterator<ProgramPointSubQuery>*/IntIterator iter2 = deps.intIterator();
                    while (iter2.hasNext()) {
                        int dep = iter2.next();
                        ProgramPointSubQuery depQuery = ProgramPointSubQuery.lookupDictionary(dep);
                        if (!depQuery.isExpired()) {
                            // At least one of the new dependencies has not yet expired.

                            // add it back to the negative cache...
                            negativeCache.add(key);

                            // Add all the dependencies back and rerun the query
                            IntIterator iter3 = deps.intIterator();
                            while (iter3.hasNext()) {
                                addQueryDependency(key, iter3.next());
                            }
                            rerun = true;
                            break;
                        }
                    }
                }
            }

            if (!rerun) {
                // At this point the query is no longer in the cache and any dependencies were expired.
                // No need to rerun the query and this query is no longer responsible for notifying dependencies of any change.
                // Whoever recomputes the result will notify the dependencies
                // We are basically starting over as if the query was never computed
                sq.setCacheRepresentative(false);

                return;
            }
        }
        this.computeQuery(Collections.singleton(sq.source),
                          sq.destination,
                          sq.noKill,
                          sq.noAlloc,
                          sq.forbidden,
                          sq.origin);
    }

    /**
     * Clear caches containing results for queries that have already been computed
     */
    public void clearCaches() {
        System.err.println("Clearing reachability cache.");
        positiveCache = AnalysisUtil.createConcurrentSet();
        negativeCache = AnalysisUtil.createConcurrentSet();
        if (CallGraphReachability.USE_CALL_GRAPH_REACH) {
            callGraphReachability.clearCaches();
        }
    }

    //***********************
    // Diagnostic info
    //***********************

    // Times
    private AtomicLong queryDepTime = new AtomicLong(0);
    private AtomicLong recordResultsTime = new AtomicLong(0);
    private AtomicLong totalTime = new AtomicLong(0);
    private AtomicLong destQueryTime = new AtomicLong(0);
    AtomicLong recordMethodTime = new AtomicLong(0);
    AtomicLong methodReachTime = new AtomicLong(0);
    private AtomicLong calleeDepTime = new AtomicLong(0);
    private AtomicLong callerDepTime = new AtomicLong(0);
    private AtomicLong methodDepTime = new AtomicLong(0);
    private AtomicLong killQueryDepTime = new AtomicLong(0);
    AtomicLong killCallerDepTime = new AtomicLong(0);
    AtomicLong methodCalleeDepTime = new AtomicLong(0);
    AtomicLong methodCallerDepTime = new AtomicLong(0);
    private AtomicLong reachableImplTime = new AtomicLong(0);

    // Reachability counts
    private AtomicInteger totalRequests = new AtomicInteger(0);
    private AtomicInteger cachedResponses = new AtomicInteger(0);
    private AtomicInteger computedResponses = new AtomicInteger(0);
    // Method reachability counts
    AtomicInteger totalMethodReach = new AtomicInteger(0);
    AtomicInteger cachedMethodReach = new AtomicInteger(0);
    AtomicInteger computedMethodReach = new AtomicInteger(0);
    AtomicInteger totalComputeMethodReachability = new AtomicInteger(0);
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

    public void printDiagnostics() {
        if (!PRINT_DIAGNOSTICS) {
            return;
        }
        if (PRINT_AND_RESET) {
            printAndReset();
            return;
        }
        StringBuffer sb = new StringBuffer();
        sb.append("\n%%%%%%%%%%%%%%%%% REACHABILITY STATISTICS %%%%%%%%%%%%%%%%%\n");
        sb.append("\nTotal requests: "
                + totalRequests
                + "  ;  "
                + cachedResponses
                + "  cached "
                + computedResponses
                + " computed ("
                + (int) (100 * (cachedResponses.floatValue() / (cachedResponses.floatValue() + computedResponses.floatValue())))
                + "% hit rate)\n");
        sb.append("Total getReachabilityForMethod requests: " + totalMethodReach + "  ;  " + cachedMethodReach
                + "  cached " + computedMethodReach + " computed ("
                + (int) (100 * (cachedMethodReach.floatValue() / totalMethodReach.floatValue())) + "% hit rate)\n");
        sb.append("Total computeMethodReachability calls: " + totalComputeMethodReachability + "\n");
        sb.append("Total subquery requests: " + totalDestQuery + "  ;  " + cachedDestQuery + "  races "
                + computedDestQuery + " computed ("
                + (int) (100 * (cachedDestQuery.floatValue() / totalDestQuery.floatValue())) + "% race rate)\n");

        double analysisTime = (System.currentTimeMillis() - PointsToAnalysis.startTime) / 1000.0;
        double total = totalTime.get() / 1000.0;
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
        double reachableImpl = reachableImplTime.get() / 1000.0;

        double calleeQueryRequestCount = calleeQueryRequests.get();
        double callerQueryRequestCount = callerQueryRequests.get();
        double killQueryRequestCount = killQueryRequests.get();
        double methodQueryRequestCount = methodQueryRequests.get();
        double reachabilityRequestCount = reachabilityRequests.get();

        sb.append("REACHABILITY QUERY EXECUTION\n");
        sb.append("\tTotal: " + analysisTime + "s;\n");
        // Multiply by the number of threads to get the right ratios
        analysisTime *= analysisHandle.numThreads();
        sb.append("\tReachability: " + total + "s; RATIO: " + total / analysisTime + "\n");
        sb.append("\tMethod: " + methodReach + "s; RATIO: " + methodReach / analysisTime + "\n");
        sb.append("\tDestination: " + destQuery + "s; RATIO: " + destQuery / analysisTime + "\n");
        sb.append("\tReachableImpl: " + reachableImpl + "s; RATIO: " + reachableImpl / analysisTime + "\n");
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
        sb.append("\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n");
        System.err.println(sb.toString());
        if (CallGraphReachability.USE_CALL_GRAPH_REACH) {
            callGraphReachability.printDiagnostics();
        }
    }

    private long epochTime = -1;

    public void printAndReset() {
        if (!PRINT_DIAGNOSTICS) {
            return;
        }
        StringBuffer sb = new StringBuffer();
        sb.append("\n%%%%%%%%%%%%%%%%% REACHABILITY STATISTICS %%%%%%%%%%%%%%%%%\n");
        sb.append("\nTotal requests: "
                + totalRequests
                + "  ;  "
                + cachedResponses
                + "  cached "
                + computedResponses
                + " computed ("
                + (int) (100 * (cachedResponses.getAndSet(0) / ((double) cachedResponses.getAndSet(0) + (double) computedResponses.getAndSet(0))))
                + "% hit rate)\n");
        sb.append("Total getReachabilityForMethod requests: " + totalMethodReach + "  ;  " + cachedMethodReach
                + "  cached " + computedMethodReach.getAndSet(0) + " computed ("
                + (int) (100 * (cachedMethodReach.getAndSet(0) / (double) totalMethodReach.getAndSet(0)))
                + "% hit rate)\n");
        sb.append("Total computeMethodReachability calls: " + totalComputeMethodReachability.getAndSet(0) + "\n");
        sb.append("Total subquery requests: " + totalDestQuery + "  ;  " + cachedDestQuery
                + "  races " + computedDestQuery.getAndSet(0) + " computed ("
                + (int) (100 * (cachedDestQuery.getAndSet(0) / (double) totalDestQuery.getAndSet(0)))
                + "% race rate)\n");

        if (epochTime < 0) {
            epochTime = PointsToAnalysis.startTime;
        }
        long endTime = System.currentTimeMillis();
        double analysisTime = (endTime - epochTime) / 1000.0;
        epochTime = endTime;
        double total = totalTime.getAndSet(0) / 1000.0;
        double methodReach = methodReachTime.getAndSet(0) / 1000.0;
        double destQuery = destQueryTime.getAndSet(0) / 1000.0;

        double recordResults = recordResultsTime.getAndSet(0) / 1000.0;
        double recordMethod = recordMethodTime.getAndSet(0) / 1000.0;

        double queryDep = queryDepTime.getAndSet(0) / 1000.0;
        double calleeDep = calleeDepTime.getAndSet(0) / 1000.0;
        double callerDep = callerDepTime.getAndSet(0) / 1000.0;
        double methodDep = methodDepTime.getAndSet(0) / 1000.0;
        double killQueryDep = killQueryDepTime.getAndSet(0) / 1000.0;
        double killCallerDep = killCallerDepTime.getAndSet(0) / 1000.0;
        double methodCalleeDep = methodCalleeDepTime.getAndSet(0) / 1000.0;
        double methodCallerDep = methodCallerDepTime.getAndSet(0) / 1000.0;
        double reachableImpl = reachableImplTime.getAndSet(0) / 1000.0;

        double calleeQueryRequestCount = calleeQueryRequests.getAndSet(0);
        double callerQueryRequestCount = callerQueryRequests.getAndSet(0);
        double killQueryRequestCount = killQueryRequests.getAndSet(0);
        double methodQueryRequestCount = methodQueryRequests.getAndSet(0);
        double reachabilityRequestCount = reachabilityRequests.getAndSet(0);
        double requestCount = totalRequests.getAndSet(0);

        sb.append("REACHABILITY QUERY EXECUTION\n");
        sb.append("\tTotal: " + analysisTime + "s;\n");
        // Multiply by the number of threads to get the right ratios
        analysisTime *= analysisHandle.numThreads();
        sb.append("\tReachability: " + total + "s; RATIO: " + total / analysisTime + "\n");
        sb.append("\tMethod: " + methodReach + "s; RATIO: " + methodReach / analysisTime + "\n");
        sb.append("\tDestination: " + destQuery + "s; RATIO: " + destQuery / analysisTime + "\n");
        sb.append("\tReachableImpl: " + reachableImpl + "s; RATIO: " + reachableImpl / analysisTime + "\n");
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
                + (calleeQueryRequestCount / requestCount) + "\n");
        sb.append("\tcallerQueryRequests: " + callerQueryRequestCount + " RATIO: "
                + (callerQueryRequestCount / requestCount) + "\n");
        sb.append("\tkillQueryRequests: " + killQueryRequestCount + " RATIO: " + (killQueryRequestCount / requestCount)
                + "\n");
        sb.append("\tmethodQueryRequests: " + methodQueryRequestCount + " RATIO: "
                + (methodQueryRequestCount / requestCount) + "\n");
        sb.append("\treachabilityRequests: " + reachabilityRequestCount + " RATIO: "
                + (reachabilityRequestCount / requestCount) + "\n");
        sb.append("\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n");
        System.err.println(sb.toString());
        if (CallGraphReachability.USE_CALL_GRAPH_REACH) {
            callGraphReachability.printDiagnostics();
        }
    }

    public MethodSummaryKillAndAlloc getReachabilityForMethod(int cgNode) {
        return this.methodReachability.getReachabilityForMethod(cgNode);
    }
}
