package analysis.pointer.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import util.OrderedPair;
import util.WorkQueue;
import util.intmap.ConcurrentIntMap;
import util.intset.EmptyIntSet;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
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
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

/**
 * This class answers questions about what programs are reachable from what other program points, and caches answers
 * smartly.
 */
public class ProgramPointReachability {
    /**
     * Keep a reference to the PointsToGraph for convenience.
     */
    private final PointsToGraph g;

    /**
     * A reference ot allow us to submit a StmtAndContext for reprocessing
     *
     * @param g
     */
    private final PointsToAnalysisHandle analysisHandle;

    ProgramPointReachability(PointsToGraph g, PointsToAnalysisHandle analysisHandle) {
        assert g != null && analysisHandle != null;
        this.g = g;
        this.analysisHandle = analysisHandle;
    }

    /**
     * A KilledAndAlloced object is simply the pair of two sets, one set which records which PointsTGraphNodes have been
     * killed, and the other set records which InstanceKeys have been allocated.
     *
     * KilledAndAlloced objects are used as program analysis facts. That is, when analyzing a method, we may record for
     * each program point pp in the method, which PointsToGraphNodes must have been killed on all path from the method
     * entry to pp, and which InstanceKeyRecency must have been newly allocated on all paths from the method entry to
     * pp.
     */
    public static class KilledAndAlloced {
        /**
         * We use a distinguished constant for unreachable program points. The null value for the killed and alloced
         * sets represents the "universe" sets, e.g., if killed == null, then it means that all fields are killed on all
         * paths to the program point.
         */
        static final KilledAndAlloced UNREACHABLE = new KilledAndAlloced(null, null, null);

        private/*Set<PointsToGraphNode>*/MutableIntSet killed;
        private/*Set<InstanceKeyRecency>*/MutableIntSet alloced;
        private Set<FieldReference> maybeKilledFields;

        KilledAndAlloced(MutableIntSet killed, Set<FieldReference> maybeKilledFields, MutableIntSet alloced) {
            this.killed = killed;
            this.maybeKilledFields = maybeKilledFields;
            this.alloced = alloced;
            assert (killed == null && maybeKilledFields == null && alloced == null)
                    || (killed != null && maybeKilledFields != null && alloced != null);
        }

        /**
         * Combine (union) a and b.
         *
         * @param a
         * @param b
         */
        public static KilledAndAlloced join(KilledAndAlloced a, KilledAndAlloced b) {
            if (a.killed == null || b.killed == null) {
                // represents everything!
                return UNREACHABLE;
            }
            int killedSize = a.killed.size() + b.killed.size();
            MutableIntSet killed = killedSize == 0 ? EmptyIntSet.INSTANCE
                    : MutableSparseIntSet.createMutableSparseIntSet(killedSize);
            Set<FieldReference> maybeKilledFields = new LinkedHashSet<>();
            int allocedSize = a.alloced.size() + b.alloced.size();
            MutableIntSet alloced = allocedSize == 0 ? EmptyIntSet.INSTANCE
                    : MutableSparseIntSet.createMutableSparseIntSet(allocedSize);

            if (killedSize > 0) {
                killed.addAll(a.killed);
                killed.addAll(b.killed);
            }
            maybeKilledFields.addAll(a.maybeKilledFields);
            maybeKilledFields.addAll(b.maybeKilledFields);
            if (allocedSize > 0) {
                alloced.addAll(a.alloced);
                alloced.addAll(b.alloced);
            }

            return new KilledAndAlloced(killed, maybeKilledFields, alloced);
        }

        /**
         * Take the intersection of the killed and alloced sets with the corresponding sets in res. This method
         * imperatively updates the killed and alloced sets. It returns true if and only if the killed or alloced sets
         * of this object changed.
         */
        public boolean meet(KilledAndAlloced res) {
            assert (this != UNREACHABLE) : "Can't update the UNREACHABLE constant";
            assert (killed == null && maybeKilledFields == null && alloced == null)
                    || (killed != null && maybeKilledFields != null && alloced != null);
            assert (res.killed == null && res.maybeKilledFields == null && res.alloced == null)
                    || (res.killed != null && res.maybeKilledFields != null && res.alloced != null);

            if (this == res || res.killed == null) {
                // no change to this object.
                return false;
            }
            if (this.killed == null) {
                // we represent the "universal" sets, so intersecting with
                // the sets in res just gives us directly the sets in res.
                // So copy over the sets res.killed and res.alloced.
                this.killed = MutableSparseIntSet.createMutableSparseIntSet(2);
                this.killed.copySet(res.killed);
                this.maybeKilledFields = new LinkedHashSet<>(res.maybeKilledFields);
                this.alloced = MutableSparseIntSet.createMutableSparseIntSet(2);
                this.alloced.copySet(res.alloced);
                return true;
            }

            // intersect the sets, and see if the size of either of them changed.
            int origKilledSize = this.killed.size();
            int origAllocedSize = this.alloced.size();
            this.killed.intersectWith(res.killed);
            this.alloced.intersectWith(res.alloced);
            boolean changed = this.maybeKilledFields.retainAll(res.maybeKilledFields);
            return changed || (this.killed.size() != origKilledSize || this.alloced.size() != origAllocedSize);

        }

        /**
         * Add a points to graph node to the kill set.
         */
        public boolean addKill(/*PointsToGraphNode*/int n) {
            assert killed != null;
            return this.killed.add(n);
        }

        public boolean addMaybeKilledField(FieldReference f) {
            assert maybeKilledFields != null;
            return this.maybeKilledFields.add(f);
        }


        /**
         * Add an instance key to the alloced set.
         */
        public boolean addAlloced(/*InstanceKeyRecency*/int justAllocatedKey) {
            assert alloced != null;
            return this.alloced.add(justAllocatedKey);
        }

        /**
         * Set the killed and alloced sets to empty. This should be used only as the first operation called after the
         * constructor.
         */
        public void setEmpty() {
            assert killed == null && maybeKilledFields == null && alloced == null;
            this.killed = MutableSparseIntSet.createMutableSparseIntSet(1);
            this.maybeKilledFields = Collections.EMPTY_SET;
            this.alloced = MutableSparseIntSet.createMutableSparseIntSet(1);
        }

        /**
         * Returns false if this.killed intersects with noKill, or if this.alloced intersents with noAlloc. Otherwise,
         * it returns true.
         *
         * @param g
         */
        public boolean allows(IntSet noKill, IntSet noAlloc, PointsToGraph g) {
            if ((this.killed != null && !this.killed.containsAny(noKill))
                    && (this.alloced != null && !this.alloced.containsAny(noAlloc))) {
                // check if the killed fields might be a problem.
                if (!this.maybeKilledFields.isEmpty()) {
                    IntIterator iter = noKill.intIterator();
                    while (iter.hasNext()) {
                        int n = iter.next();
                        PointsToGraphNode node = g.lookupPointsToGraphNodeDictionary(n);
                        if (node instanceof ObjectField
                                && this.maybeKilledFields.contains(((ObjectField) node).fieldReference())) {
                            // the field may be killed.
                            return false;
                        }
                    }
                }
                return true;
            }
            return false;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((alloced == null) ? 0 : alloced.size());
            result = prime * result + ((killed == null) ? 0 : killed.size());
            result = prime * result + ((maybeKilledFields == null) ? 0 : maybeKilledFields.hashCode());
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
            if (!(obj instanceof KilledAndAlloced)) {
                return false;
            }
            KilledAndAlloced other = (KilledAndAlloced) obj;
            if (alloced == null) {
                if (other.alloced != null) {
                    return false;
                }
            }
            else if (!alloced.sameValue(other.alloced)) {
                return false;
            }
            if (killed == null) {
                if (other.killed != null) {
                    return false;
                }
            }
            else if (!killed.sameValue(other.killed)) {
                return false;
            }
            if (maybeKilledFields == null) {
                if (other.maybeKilledFields != null) {
                    return false;
                }
            }
            else if (!maybeKilledFields.equals(other.maybeKilledFields)) {
                return false;
            }
            return true;
        }

    }


    /**
     * Given a map from InterProgramPoints to KilledAndAlloceds, either get the existing KilledAndAlloced for ipp, or
     * craete one that represents all-killled-all-allocated and add it to the map for ipp.
     *
     * @param results
     * @param ipp
     * @return
     */
    private static KilledAndAlloced getOrCreate(Map<InterProgramPoint, KilledAndAlloced> results, InterProgramPoint ipp) {
        KilledAndAlloced res = results.get(ipp);
        if (res == null) {
            res = new KilledAndAlloced(null, null, null);
            results.put(ipp, res);
        }
        return res;
    }

    /*
     * Can destination be reached from any InterProgramPointReplica in sources without
     * going through a program point that kills any PointsToGraphNode in noKill, and
     * without going through a program point that allocates any InstanceKey in noAlloc?
     */
    public boolean reachable(ProgramPointSetClosure ppsc, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc,
                             ReachabilityQueryOrigin origin, Set<ReachabilityQueryOrigin> sacsToReprocess) {
        return reachableImpl(ppsc.getSources(this.g, origin),
                             destination,
                             noKill,
                             noAlloc,
                             Collections.<InterProgramPointReplica> emptySet(),
                             origin,
                             sacsToReprocess);
    }

    /*
     * Can destination be reached from source without going through any of the forbidden program points?
     * If forbidden is non empty, then all of the forbidden IPPRs must be in the same method and context as one of the source or the destination.
     */
    public boolean reachable(InterProgramPointReplica source, InterProgramPointReplica destination,
                             Set<InterProgramPointReplica> forbidden, ReachabilityQueryOrigin origin,
                             Set<ReachabilityQueryOrigin> sacsToReprocess) {
        return reachableImpl(Collections.singleton(source),
                             destination,
                             EmptyIntSet.INSTANCE,
                             EmptyIntSet.INSTANCE,
                             forbidden,
                             origin,
                             sacsToReprocess);
    }

    /*
     * Can destination be reached from any InterProgramPointReplica in sources without
     * going through a program point that kills any PointsToGraphNode in noKill, and
     * without going through a program point that allocates any InstanceKey in noAlloc?
     */
    public boolean reachable(Collection<InterProgramPointReplica> sources, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc,
                             ReachabilityQueryOrigin origin, Set<ReachabilityQueryOrigin> tasksToReprocess) {
        return reachableImpl(sources,
                             destination,
                             noKill,
                             noAlloc,
                             Collections.<InterProgramPointReplica> emptySet(),
                             origin,
                             tasksToReprocess);
    }

    /*
     * Can destination be reached from any InterProgramPointReplica in sources without
     * going through a program point that kills any PointsToGraphNode in noKill, and
     * without going through a program point that allocates any InstanceKey in noAlloc, and without going through any of the forbidden IPPRs?
     * If forbidden is non empty, then all of the forbidden IPPRs must be in the same method and context as one of the source or the destination.
     */
    private boolean reachableImpl(Collection<InterProgramPointReplica> sources, InterProgramPointReplica destination,
    /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc,
                             Set<InterProgramPointReplica> forbidden, ReachabilityQueryOrigin origin,
                             Set<ReachabilityQueryOrigin> tasksToReprocess) {
        assert allMostRecent(noAlloc);
        assert allInSameMethodAndContext(forbidden, sources, destination);
        // check the caches
        List<InterProgramPointReplica> unknown = new ArrayList<>(sources.size());
        for (InterProgramPointReplica src : sources) {
            SubQuery mr = new SubQuery(src, destination, noKill, noAlloc, forbidden);
            if (this.positiveCache.contains(mr)) {
                assert !this.negativeCache.contains(mr);
                return true;
            }
            else if (this.negativeCache.contains(mr)) {
                // we know it's a negative result for this one.
                assert !positiveCache.contains(mr);
            }
            else {
                unknown.add(src);
            }
        }

        if (unknown.isEmpty()) {
            // all were negative!
            return false;
        }
        // The cache didn't help. Try getting an answer for the unknown elements.
        return computeQuery(unknown, destination, noKill, noAlloc, forbidden, origin, tasksToReprocess);
    }

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

    private boolean computeQuery(Collection<InterProgramPointReplica> sources, InterProgramPointReplica destination,
                                 IntSet noKill, IntSet noAlloc, Set<InterProgramPointReplica> forbidden,
                                 ReachabilityQueryOrigin origin,
                                 Set<ReachabilityQueryOrigin> tasksToReprocess) {
        // try to solve it for each source.
        OrderedPair<IMethod, Context> destinationMethod = new OrderedPair<>(destination.getContainingProcedure(),
                destination.getContext());
        Set<InterProgramPointReplica> visited = new HashSet<>();
        for (InterProgramPointReplica src : sources) {
            SubQuery query = new SubQuery(src, destination, noKill, noAlloc, forbidden);
            if (positiveCache.contains(query)) {
                assert !negativeCache.contains(query);
                // The result was computed by another thread before this thread ran
                return true;
            }

            // First check the call graph to find the set of relevant call graph nodes.
            OrderedPair<IMethod, Context> sourceMethod = new OrderedPair<>(src.getContainingProcedure(),
                    src.getContext());
            Set<OrderedPair<IMethod, Context>> relevantNodes = findRelevantNodes(sourceMethod, destinationMethod, query);

            if (relevantNodes.isEmpty()) {
                // this one isn't possible.
                addDependency(query, origin);
                if (recordQueryResult(query, false, tasksToReprocess)) {
                    // We computed false, but the cache already had true
                    return true;
                }
                continue;
            }
            // Now try a depth first search through the relevant nodes...
            if (searchThroughRelevantNodes(src,
                                           destination,
                                           noKill,
                                           noAlloc,
                                           forbidden,
                                           relevantNodes,
                                           visited,
                                           query,
                                           tasksToReprocess)) {
                // we found it!
                recordQueryResult(query, true, tasksToReprocess);
                return true;
            }
            addDependency(query, origin);
            if (recordQueryResult(query, false, tasksToReprocess)) {
                // We computed false, but the cache already had true
                return true;
            }
        }
        // we didn't find it.
        return false;
    }

    SubQuery prev = null;

    /**
     * Record in the caches that the result of query mr is b. If this is a change (from negative to positive), then some
     * StmtAndContexts may need to be rerun. If set sacsToReprocess is non-null then the StmtAndContexts will be added
     * to the set. Otherwise, they will be submitted to the PointsToEngine.
     *
     * @param mr
     * @param b
     * @param sacsToReprocess
     *
     * @return Whether the actual result is "true" or "false". When recording false, it is possible to return true if
     *         the cache already has a positive result.
     */
    private boolean recordQueryResult(SubQuery mr, boolean b, Set<ReachabilityQueryOrigin> sacsToReprocess) {
        if (b) {
            positiveCache.add(mr);
            if (negativeCache.remove(mr)) {
                // we previously thought it was negative.
                queryResultChanged(mr, sacsToReprocess);
            }
            return true;
        }

        // Recording a false result
        negativeCache.add(mr);
        if (positiveCache.contains(mr)) {
            this.negativeCache.remove(mr);
            // A positive result has already been computed return it
            return true;
        }
        return false;
    }


    private boolean allMostRecent(IntSet s) {
        IntIterator iter = s.intIterator();
        while (iter.hasNext()) {
            int ik = iter.next();
            if (!g.isMostRecentObject(ik)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Find the relevant call graph nodes. That is, the call graph nodes that are reachable (forward or backward) from
     * sourceCGNode and also reachable (forward or backward) from destinationCGNode.
     *
     * @param sourceCGNode
     * @param destinationCGNode
     * @param query
     * @return
     */
    private Set<OrderedPair<IMethod, Context>> findRelevantNodes(OrderedPair<IMethod, Context> sourceCGNode,
                                                                 OrderedPair<IMethod, Context> destinationCGNode,
                                                                 SubQuery query) {
        Set<OrderedPair<IMethod, Context>> s = new HashSet<>();
        // first find the CG nodes reachable from sourceCGNode
        Deque<OrderedPair<IMethod, Context>> q = new ArrayDeque<>();
        q.add(sourceCGNode);
        s.add(sourceCGNode);
        while (!q.isEmpty()) {
            OrderedPair<IMethod, Context> cgnode = q.poll();
            for (ProgramPointReplica callSite : g.getCallSitesOf(cgnode)) {
                this.addCalleeDependency(query, callSite);
                for (OrderedPair<IMethod, Context> callee : g.getCalleesOf(callSite)) {
                    if (s.add(callee)) {
                        q.add(callee);
                    }
                }

            }
            this.addCallerDependency(query, cgnode);
            for (OrderedPair<IMethod, Context> caller : g.getCallersOf(cgnode)) {
                if (s.add(caller)) {
                    q.add(caller);
                }
            }
        }
        if (!s.contains(destinationCGNode)) {
            // the destination isn't reachable
            // Nothing is relevant.
            return Collections.emptySet();
        }
        // Now restrict the relevant stuff to what is reachable from the destination.
        Set<OrderedPair<IMethod, Context>> t = new HashSet<>();
        q.add(destinationCGNode);
        t.add(destinationCGNode);
        while (!q.isEmpty()) {
            OrderedPair<IMethod, Context> cgnode = q.poll();
            for (ProgramPointReplica callSite : g.getCallSitesOf(cgnode)) {
                this.addCalleeDependency(query, callSite);
                for (OrderedPair<IMethod, Context> callee : g.getCalleesOf(callSite)) {
                    if (s.contains(callee) && t.add(callee)) {
                        q.add(callee);
                    }
                }
            }
            this.addCallerDependency(query, cgnode);
            for (OrderedPair<IMethod, Context> caller : g.getCallersOf(cgnode)) {
                if (s.contains(caller) && t.add(caller)) {
                    q.add(caller);
                }
            }
        }
        return t;
    }

    /**
     * Try to find a path from src to destination. relevantNodes contains all call graph nodes that might possibly
     * contain such a path.
     *
     * @param src
     * @param destination
     * @param noAlloc
     * @param noKill
     * @param relevantNodes
     * @param query The query that caused this search, used to track dependencies
     * @return
     */
    private boolean searchThroughRelevantNodes(InterProgramPointReplica src, InterProgramPointReplica destination,
                                               IntSet noKill, IntSet noAlloc, Set<InterProgramPointReplica> forbidden,
                                               Set<OrderedPair<IMethod, Context>> relevantNodes,
                                               Set<InterProgramPointReplica> visited, SubQuery query,
                                               Set<ReachabilityQueryOrigin> tasksToReprocess) {
        if (!visited.add(src)) {
            // we've already tried it...
            return false;
        }
        IMethod currentMethod = src.getContainingProcedure();
        Context currentContext = src.getContext();
        OrderedPair<IMethod, Context> currentCallGraphNode = new OrderedPair<>(currentMethod, currentContext);
        // Is the destination node in the same node as we are?
        boolean inSameMethod = destination.getContainingProcedure().equals(currentMethod);

        // try searching forward from src, carefully handling calls.
        Deque<InterProgramPointReplica> q = new ArrayDeque<>();
        Deque<InterProgramPointReplica> delayed = new ArrayDeque<>();
        q.add(src);
        while (!q.isEmpty()) {
            InterProgramPointReplica ippr = q.poll();
            assert (ippr.getContainingProcedure().equals(currentMethod));
            if (inSameMethod && ippr.equals(destination)) {
                // Found it!
                return true;
            }

            if (forbidden.contains(ippr)) {
                // prune this!
                continue;
            }

            InterProgramPoint ipp = ippr.getInterPP();
            ProgramPoint pp = ipp.getPP();

            // if it is a call, then handle the results
            if (ipp instanceof PreProgramPoint) {
                if (pp instanceof CallSiteProgramPoint) {
                    // This is a program point for a method call
                    boolean found = handleCall(query,
                                               ippr,
                                               relevantNodes,
                                               inSameMethod,
                                               delayed,
                                               destination,
                                               noKill,
                                               noAlloc,
                                               forbidden,
                                               visited,
                                               tasksToReprocess,
                                               q,
                                               currentContext);
                    if (found) {
                        return true;
                    }
                    continue;
                }
                else if (pp.isNormalExitSummaryNode() || pp.isExceptionExitSummaryNode()) {
                    boolean found = handleMethodExit(query,
                                                     currentCallGraphNode,
                                                     relevantNodes,
                                                     inSameMethod,
                                                     delayed,
                                                     destination,
                                                     noKill,
                                                     noAlloc,
                                                     forbidden,
                                                     visited,
                                                     tasksToReprocess);
                    if (found) {
                        return true;
                    }
                    continue;
                }
                PointsToStatement stmt = g.registrar.getStmtAtPP(pp);
                // not a call or a return, it's just a normal statement.
                // does ipp kill this.node?
                if (stmt != null && handlePossibleKill(stmt, currentContext, query, noKill, noAlloc)) {
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
        } // end of normal queue
        while (!delayed.isEmpty()) {
            InterProgramPointReplica ippr = delayed.poll();
            if (searchThroughRelevantNodes(ippr,
                                           destination,
                                           noKill,
                                           noAlloc,
                                           forbidden,
                                           relevantNodes,
                                           visited,
                                           query,
                                           tasksToReprocess)) {
                return true;
            }
        }
        // we didn't find it
        return false;
    }

    /**
     * Handle a "pre" program point that may kill the current path.
     *
     * @param stmt points-to graph statement for the current program point
     * @param currentContext current context
     * @param query query being executed
     * @param noKill Set of points to graph nodes that should not be killed on a valid path
     * @param noAlloc Set of points to graph nodes that should not be allocated on a valid path
     * @return whether the path was killed
     */
    private boolean handlePossibleKill(PointsToStatement stmt, Context currentContext, SubQuery query, IntSet noKill,
                                       IntSet noAlloc) {
        OrderedPair<Boolean, PointsToGraphNode> killed = stmt.killsNode(currentContext, g);
        if (killed != null) {
            if (!killed.fst()) {
                // not enough info available yet.
                //add a depedency since more information may change this search
                addKillDependency(query, stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
                // for the moment, assume conservatively that this statement
                // may kill a field we are interested in.
                return true;
            }
            else if (killed.snd() != null && noKill.contains(g.lookupDictionary(killed.snd()))) {
                // dang! we killed something we shouldn't. Prune the search.
                // add a depedency in case this changes in the future.
                addKillDependency(query, stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
                return true;
            }
            else if (killed.snd() == null) {
                // we have enough information to know that this statement does not kill a node we care about
                removeKillDependency(query, stmt.getReadDependencyForKillField(currentContext, g.getHaf()));
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
     * Handle a the exit from a method, check whether the destination is reachable from the exit (by checking callers)
     *
     * @param query current query
     * @param currentCallGraphNode call graph node we are exiting
     * @param relevantNodes relevant call graph nodes
     * @param inSameMethod whether the source and destination are in the same method
     * @param delayed queue for nodes for which processing has been delayed
     * @param destination destination
     * @param noKill Set of points to graph nodes that should not be killed on a valid path
     * @param noAlloc Set of points to graph nodes that should not be allocated on a valid path
     * @param forbidden program points that act as "kill" nodes cutting off any path
     * @param visited program points that have already been visited
     * @param tasksToReprocess Queries that need to be reprocessed because something may have caused the results to
     *            change
     * @return true if the destination program point was found
     */
    private boolean handleMethodExit(SubQuery query, OrderedPair<IMethod, Context> currentCallGraphNode,
                                     Set<OrderedPair<IMethod, Context>> relevantNodes, boolean inSameMethod,
                                     Deque<InterProgramPointReplica> delayed,
                                     InterProgramPointReplica destination, IntSet noKill, IntSet noAlloc,
                                     Set<InterProgramPointReplica> forbidden, Set<InterProgramPointReplica> visited,
                                     Set<ReachabilityQueryOrigin> tasksToReprocess) {
        // We are exiting the current method!
        // register dependency from this result to the callee. i.e., we should be notified if a new caller is added
        addCallerDependency(query, currentCallGraphNode);

        // let's explore the callers
        Set<OrderedPair<CallSiteProgramPoint, Context>> callers = g.getCallGraphReverseMap().get(currentCallGraphNode);
        if (callers == null) {
            // no callers
            return false;
        }
        for (OrderedPair<CallSiteProgramPoint, Context> callerSite : callers) {
            OrderedPair<IMethod, Context> caller = new OrderedPair<>(callerSite.fst().containingProcedure(),
                                                                     callerSite.snd());
            if (relevantNodes.contains(caller)) {
                // this is a relevant node, and we need to dig into it.
                InterProgramPointReplica callerSiteReplica = callerSite.fst().post().getReplica(callerSite.snd());
                if (inSameMethod) {
                    // let's delay it as long as possible, in case we find the destination here
                    delayed.add(callerSiteReplica);
                }
                else {
                    // let's explore the caller now.
                    if (searchThroughRelevantNodes(callerSiteReplica,
                                                   destination,
                                                   noKill,
                                                   noAlloc,
                                                   forbidden,
                                                   relevantNodes,
                                                   visited,
                                                   query,
                                                   tasksToReprocess)) {
                        // we found it!
                        return true;
                    }
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
     * Handle a method call, check whether the destination is reachable from the given call-site
     *
     * @param query current query
     * @param ippr call site program point replica
     * @param relevantNodes relevant call graph nodes
     * @param inSameMethod whether the source and destination are in the same method
     * @param delayed queue for nodes for which processing has been delayed
     * @param destination destination
     * @param noKill Set of points to graph nodes that should not be killed on a valid path
     * @param noAlloc Set of points to graph nodes that should not be allocated on a valid path
     * @param forbidden program points that act as "kill" nodes cutting off any path
     * @param visited program points that have already been visited
     * @param tasksToReprocess Queries that need to be reprocessed because something may have caused the results to
     *            change
     * @param q program point work queue
     * @param currentContext context for the source program poinr
     * @return true if the destination program point was found
     */
    private boolean handleCall(SubQuery query, InterProgramPointReplica ippr,
                               Set<OrderedPair<IMethod, Context>> relevantNodes, boolean inSameMethod,
                               Deque<InterProgramPointReplica> delayed, InterProgramPointReplica destination,
                               IntSet noKill, IntSet noAlloc, Set<InterProgramPointReplica> forbidden,
                               Set<InterProgramPointReplica> visited, Set<ReachabilityQueryOrigin> tasksToReprocess,
                               Deque<InterProgramPointReplica> q, Context currentContext) {
        CallSiteProgramPoint pp = (CallSiteProgramPoint) ippr.getInterPP().getPP();

        // this is a method call! Register the dependency and use some cached results
        addCalleeDependency(query, pp.getReplica(ippr.getContext()));

        OrderedPair<CallSiteProgramPoint, Context> caller = new OrderedPair<>( pp,
                                                                              ippr.getContext());
        Set<OrderedPair<IMethod, Context>> calleeSet = g.getCallGraphMap().get(caller);
        if (calleeSet == null) {
            // no callees, so nothing to do
            return false;
        }

        for (OrderedPair<IMethod, Context> callee : calleeSet) {
            MethodSummaryNodes calleeSummary = g.registrar.getMethodSummary(callee.fst());
            InterProgramPointReplica calleeEntryIPPR = calleeSummary.getEntryPP().post().getReplica(callee.snd());

            if (relevantNodes.contains(callee)) {
                // this is a relevant node, and we need to dig into it.
                if (inSameMethod) {
                    // let's delay it as long as possible, in case we find the destination here
                    delayed.add(calleeEntryIPPR);
                }
                else {
                    // let's explore the callee now.
                    if (searchThroughRelevantNodes(calleeEntryIPPR,
                                                   destination,
                                                   noKill,
                                                   noAlloc,
                                                   forbidden,
                                                   relevantNodes,
                                                   visited,
                                                   query,
                                                   tasksToReprocess)) {
                        // we found it!
                        return true;
                    }
                }
            }
            // now use the summary results.
            addMethodDependency(query, callee);
            ReachabilityResult calleeResults = getReachabilityForMethod(callee.fst(), callee.snd(), tasksToReprocess);
            KilledAndAlloced normalRet = calleeResults.getResult(calleeEntryIPPR,
                                                                 calleeSummary.getNormalExitPP()
                                                                              .pre()
                                                                              .getReplica(callee.snd()));
            KilledAndAlloced exRet = calleeResults.getResult(calleeEntryIPPR, calleeSummary.getExceptionExitPP()
                                                                                           .pre()
                                                                                           .getReplica(callee.snd()));

            // HERE WE SHOULD BE MORE PRECISE ABOUT PROGRAM POINT SUCCESSORS, AND PAY ATTENTION TO NORMAL VS EXCEPTIONAL EXIT

            if (normalRet.allows(noKill, noAlloc, g) || exRet.allows(noKill, noAlloc, g)) {
                // we don't kill things we aren't meant to, not allocated things we aren't meant to!
                InterProgramPointReplica post = pp.post().getReplica(currentContext);
                if (visited.add(post)) {
                    q.add(post);
                }
            }
            else {
                // nope, this means the callee kills a pointstographnode we are interseted in,
                // or it allocates an instancekey we are interested in.
                // Prune the search...
            }

        }
        // We didn't find the destination node
        return false;
    }

    /* *****************************************************************************
    *
    * METHOD REACHABILITY CODE
    *
    * The following code is responsible for computing the reachability results of an
    * entire method.
    */

    /**
     * A ReachabilityResult records the reachability results of a single method in a context, that is, which for a
     * subset of the source and destination pairs of InterProgramPointReplica in the method and context, what are the
     * KilledAndAlloced sets that summarize all paths from the source to the destination.
     *
     * This object must be thread safe.
     */
    private static class ReachabilityResult {
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

        public static ReachabilityResult createInitial() {
            return new ReachabilityResult();
        }

        public KilledAndAlloced getResult(InterProgramPointReplica source, InterProgramPointReplica target) {
            ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> s = m.get(source);
            if (s == null) {
                return KilledAndAlloced.UNREACHABLE;
            }
            KilledAndAlloced p = s.get(target);
            if (p == null) {
                return KilledAndAlloced.UNREACHABLE;
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

        private static KilledAndAlloced getTargetResult(ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> targetMap,
                                                        InterProgramPointReplica t, KilledAndAlloced initialResult) {
            KilledAndAlloced p = targetMap.get(t);
            if (p != null) {
                // great, a result already exists. return it.
                return p;
            }
            p = targetMap.putIfAbsent(t, initialResult);
            if (p != null) {
                // someone else beat us.
                return p;
            }
            // we successfully put in the initial result.
            return initialResult;
        }

        private ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> getTargetMap(InterProgramPointReplica s) {
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
            if (!(obj instanceof ReachabilityResult)) {
                return false;
            }
            ReachabilityResult other = (ReachabilityResult) obj;
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

    }

    private final ConcurrentMap<OrderedPair<IMethod, Context>, ReachabilityResult> memoization = AnalysisUtil.createConcurrentHashMap();

    /*
     * Get the reachability results for a method.
     */
    ReachabilityResult getReachabilityForMethod(IMethod m, Context context,
                                                Set<ReachabilityQueryOrigin> tasksToReprocess) {
        OrderedPair<IMethod, Context> cgnode = new OrderedPair<>(m, context);
        ReachabilityResult res = memoization.get(cgnode);
        if (res != null) {
            return res;
        }
        // no results yet.
        res = ReachabilityResult.createInitial();
        ReachabilityResult existing = memoization.putIfAbsent(cgnode, res);
        if (existing != null) {
            // someone beat us to it, and is currently working on the results.
            return existing;
        }
        return computeReachabilityForMethod(m, context, tasksToReprocess);
    }

    private void recordMethodReachability(IMethod m, Context context, ReachabilityResult res,
                                          Set<ReachabilityQueryOrigin> tasksToReprocess) {
        System.err.println("Recording method reachavility result : " + m + " " + context);
        OrderedPair<IMethod, Context> cgnode = new OrderedPair<>(m, context);
        ReachabilityResult existing = memoization.put(cgnode, res);
        if (existing != null && !existing.equals(res)) {
            // trigger update for dependencies.
            methodReachabilityChanged(m, context, tasksToReprocess);
        }
    }

    private ReachabilityResult computeReachabilityForMethod(IMethod m, Context context,
                                                            Set<ReachabilityQueryOrigin> tasksToReprocess) {
        // XXX at the moment we will just record from the start node.

        // do a dataflow over the program points. XXX could try to use a dataflow framework to speed this up.

        Map<InterProgramPoint, KilledAndAlloced> results = new HashMap<>();

        WorkQueue<InterProgramPoint> q = new WorkQueue<>();
        MethodSummaryNodes summ = g.registrar.getMethodSummary(m);
        PostProgramPoint entryIPP = summ.getEntryPP().post();
        q.add(entryIPP);
        getOrCreate(results, entryIPP).setEmpty();

        while (!q.isEmpty()) {
            InterProgramPoint ipp = q.poll();
            ProgramPoint pp = ipp.getPP();
            assert pp.containingProcedure().equals(m);

            KilledAndAlloced current = getOrCreate(results, ipp);

            if (ipp instanceof PreProgramPoint) {
                if (pp instanceof CallSiteProgramPoint) {
                    // this is a method call! Register the dependency and get some cached results
                    addCalleeDependency(m, context, pp.getReplica(context));

                    OrderedPair<CallSiteProgramPoint, Context> caller = new OrderedPair<>((CallSiteProgramPoint) pp,
                                                                                          context);
                    Set<OrderedPair<IMethod, Context>> calleeSet = g.getCallGraphMap().get(caller);
                    if (calleeSet == null) {
                        // no callees, so nothing to do
                        continue;
                    }

                    KilledAndAlloced post = getOrCreate(results, pp.post());
                    boolean changed = false;
                    for (OrderedPair<IMethod, Context> callee : calleeSet) {
                        addMethodDependency(m, context, callee);
                        ReachabilityResult calleeResults = getReachabilityForMethod(callee.fst(),
                                                                                    callee.snd(),
                                                                                    tasksToReprocess);
                        MethodSummaryNodes calleeSummary = g.registrar.getMethodSummary(callee.fst());
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

                        // HERE WE SHOULD BE MORE PRECISE ABOUT PROGRAM POINT SUCCESSORS, AND PAY ATTENTION TO NORMAL VS EXCEPTIONAL EXIT
                        changed |= post.meet(KilledAndAlloced.join(current, normalRet));
                        changed |= post.meet(KilledAndAlloced.join(current, exRet));

                    }
                    if (changed) {
                        q.add(pp.post());
                    }

                }
                else if (pp.isNormalExitSummaryNode() || pp.isExceptionExitSummaryNode()) {
                    // not much to do here. The results will be copied once the work queue finishes.
                    continue;
                }
                else {
                    PointsToStatement stmt = g.registrar.getStmtAtPP(pp);
                    // not a call or a return, it's just a normal statement.
                    // does ipp kill this.node?
                    if (stmt != null) {
                        boolean changed = false;
                        OrderedPair<Boolean, PointsToGraphNode> killed = stmt.killsNode(context, g);
                        if (killed != null) {
                            if (!killed.fst()) {
                                // not enough info available yet.
                                // add a depedency since more information may change this search
                                // conservatively assume that it kills any kind of the field we give it.
                                changed |= current.addMaybeKilledField(stmt.getMaybeKilledField());
                                addKillDependency(m, context, stmt.getReadDependencyForKillField(context, g.getHaf()));

                            }
                            else if (killed.snd() != null && killed.snd() != null) {
                                // this statement really does kill something.
                                changed |= current.addKill(g.lookupDictionary(killed.snd()));
                                // record it, including the dependency.
                                addKillDependency(m, context, stmt.getReadDependencyForKillField(context, g.getHaf()));
                            }
                            else if (killed.snd() == null) {
                                // we have enough information to know that this statement does not kill a node we care about
                                removeKillDependency(m,
                                                     context,
                                                     stmt.getReadDependencyForKillField(context, g.getHaf()));
                            }
                        }


                        // is "to" allocated at this program point?
                        InstanceKeyRecency justAllocated = stmt.justAllocated(context, g);
                        if (justAllocated != null) {
                            assert justAllocated.isRecent();
                            int/*InstanceKeyRecency*/justAllocatedKey = g.lookupDictionary(justAllocated);
                            if (g.isMostRecentObject(justAllocatedKey)
                                    && g.isTrackingMostRecentObject(justAllocatedKey)) {
                                changed |= current.addAlloced(justAllocatedKey);
                            }
                        }
                        if (changed) {
                            q.add(pp.post());
                        }
                    }
                }
            }
            else if (ipp instanceof PostProgramPoint) {
                Set<ProgramPoint> ppSuccs = pp.succs();
                for (ProgramPoint succ : ppSuccs) {
                    KilledAndAlloced succResults = getOrCreate(results, succ.pre());
                    if (succResults.meet(current)) {
                        q.add(succ.pre());
                    }
                }
            }
            else {
                throw new IllegalArgumentException("Don't know about this kind of interprogrampoint");
            }

        }

        ReachabilityResult rr = new ReachabilityResult();
        PreProgramPoint normExitIPP = summ.getNormalExitPP().pre();
        PreProgramPoint exExitIPP = summ.getExceptionExitPP().pre();

        rr.add(entryIPP.getReplica(context), normExitIPP.getReplica(context), getOrCreate(results, normExitIPP));
        rr.add(entryIPP.getReplica(context), exExitIPP.getReplica(context), getOrCreate(results, exExitIPP));

        recordMethodReachability(m, context, rr, tasksToReprocess);

        return rr;
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
    private final Set<SubQuery> positiveCache = AnalysisUtil.createConcurrentSet();
    private final Set<SubQuery> negativeCache = AnalysisUtil.createConcurrentSet();

    public static class SubQuery {
        final InterProgramPointReplica source;
        final InterProgramPointReplica destination;
        final/*Set<PointsToGraphNode>*/IntSet noKill;
        final/*Set<InstanceKeyRecency>*/IntSet noAlloc;
        final Set<InterProgramPointReplica> forbidden;

        SubQuery(InterProgramPointReplica source, InterProgramPointReplica destination, /*Set<PointsToGraphNode>*/
                   IntSet noKill, final/*Set<InstanceKeyRecency>*/IntSet noAlloc,
                   Set<InterProgramPointReplica> forbidden) {
            this.source = source;
            this.destination = destination;
            this.noKill = noKill;
            this.noAlloc = noAlloc;
            this.forbidden = forbidden;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = destination.hashCode();
            result = prime * result + source.hashCode();
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
            if (!(obj instanceof SubQuery)) {
                return false;
            }
            SubQuery other = (SubQuery) obj;
            if (!source.equals(other.source)) {
                return false;
            }
            if (!destination.equals(other.destination)) {
                return false;
            }
            if (!noAlloc.sameValue(other.noAlloc)) {
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

        @Override
        public String toString() {
            return "SubQuery [" + source + " => " + destination + ", noKill=" + noKill
 + ", noAlloc=" + noAlloc
                    + ", forbidden=" + forbidden + "]";
        }

    }

    /* *****************************************************************************
     *
     * DEPENDENCY TRACKING
     *
     * The following code is responsible for recording dependencies
     */
    private final ConcurrentMap<SubQuery, Set<ReachabilityQueryOrigin>> queryDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<ProgramPointReplica, Set<SubQuery>> calleeQueryDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<ProgramPointReplica, Set<OrderedPair<IMethod, Context>>> calleeMethodDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<SubQuery>> callerQueryDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<SubQuery>> methodQueryDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>> methodMethodDependencies = AnalysisUtil.createConcurrentHashMap();
    private final ConcurrentIntMap<Set<SubQuery>> killQueryDependencies = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();
    private final ConcurrentIntMap<Set<OrderedPair<IMethod, Context>>> killMethodDependencies = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * Record the fact that the result of query depends on the callees of callSite, and thus, if the callees change,
     * then query may need to be reevaluated.
     *
     * @param query
     * @param callSite
     */
    private void addCalleeDependency(SubQuery query, ProgramPointReplica callSite) {
        assert callSite.getPP() instanceof CallSiteProgramPoint;

        Set<SubQuery> s = calleeQueryDependencies.get(callSite);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<SubQuery> existing = calleeQueryDependencies.putIfAbsent(callSite, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
    }

    /**
     * query needs to be re-run if there is a new caller of callGraphNode.
     *
     * @param query
     * @param callGraphNode
     */
    private void addCallerDependency(SubQuery query, OrderedPair<IMethod, Context> callGraphNode) {
        Set<SubQuery> s = callerQueryDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<SubQuery> existing = callerQueryDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
    }

    /**
     * We need to reanalyze the method results for (m, context) if the reachability results for callGraphNode changes.
     *
     * @param m
     * @param context
     * @param callGraphNode
     */
    private void addMethodDependency(SubQuery query, OrderedPair<IMethod, Context> callGraphNode) {
        Set<SubQuery> s = methodQueryDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<SubQuery> existing = methodQueryDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
    }

    private void addKillDependency(SubQuery query, ReferenceVariableReplica readDependencyForKillField) {
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);
        Set<SubQuery> s = killQueryDependencies.get(n);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<SubQuery> existing = killQueryDependencies.putIfAbsent(n, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(query);
    }

    private void removeKillDependency(SubQuery query, ReferenceVariableReplica readDependencyForKillField) {
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);

        Set<SubQuery> s = killQueryDependencies.get(n);
        if (s != null) {
            s.remove(query);
        }
    }

    private void addCalleeDependency(IMethod m, Context context, ProgramPointReplica callSite) {
        assert callSite.getPP() instanceof CallSiteProgramPoint;

        Set<OrderedPair<IMethod, Context>> s = calleeMethodDependencies.get(callSite);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<OrderedPair<IMethod, Context>> existing = calleeMethodDependencies.putIfAbsent(callSite, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(new OrderedPair<>(m, context));
    }

    /**
     * We need to reanalyze the method results for (m, context) if the reachability results for callGraphNode changes.
     *
     * @param m
     * @param context
     * @param callGraphNode
     */
    private void addMethodDependency(IMethod m, Context context, OrderedPair<IMethod, Context> callGraphNode) {
        Set<OrderedPair<IMethod, Context>> s = methodMethodDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<OrderedPair<IMethod, Context>> existing = methodMethodDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(new OrderedPair<>(m, context));
    }

    private void addKillDependency(IMethod m, Context context, ReferenceVariableReplica readDependencyForKillField) {
        if (readDependencyForKillField == null) {
            return;
        }
        OrderedPair<IMethod, Context> callGraphNode = new OrderedPair<>(m, context);
        int n = g.lookupDictionary(readDependencyForKillField);

        Set<OrderedPair<IMethod, Context>> s = killMethodDependencies.get(n);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<OrderedPair<IMethod, Context>> existing = killMethodDependencies.putIfAbsent(n, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(callGraphNode);
    }

    private void removeKillDependency(IMethod m, Context context, ReferenceVariableReplica readDependencyForKillField) {
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);

        OrderedPair<IMethod, Context> callGraphNode = new OrderedPair<>(m, context);
        Set<OrderedPair<IMethod, Context>> s = killMethodDependencies.get(n);
        if (s != null) {
            s.remove(callGraphNode);
        }
    }

    /**
     * This method is invoked to let us know that a new edge in the
     * call graph has been added from the callSite.
     *
     *  We use this method to make sure that we re-run any method reachability results
     *  and (negative) queries depended on the call site.
     * @param callSite
     */
    public void calleeAddedTo(ProgramPointReplica callSite) {
        assert callSite.getPP() instanceof CallSiteProgramPoint;
        Set<OrderedPair<IMethod, Context>> meths = calleeMethodDependencies.get(callSite);
        if (meths != null) {
            for (OrderedPair<IMethod, Context> p : meths) {
                // need to re-run the analysis of p
                computeReachabilityForMethod(p.fst(), p.snd(), null);
            }
        }
        Set<SubQuery> queries = calleeQueryDependencies.get(callSite);
        if (queries != null) {

            Iterator<SubQuery> iter = queries.iterator();
            while (iter.hasNext()) {
                SubQuery mr = iter.next();
                // need to re-run the query of mr
                if (!requestRerunQuery(mr, null)) {
                    // whoops, no need to rerun this anymore.
                    iter.remove();
                }
            }
        }

    }

    /**
     * This method is invoked to let us know that a new edge in the
     * call graph has been added that goes to the callGraphNode
     *
     *  We use this method to make sure that we re-run any
     *   queries that depended on the call site.
     * @param callSite
     */
    public void callerAddedTo(OrderedPair<IMethod, Context> callGraphNode) {
        Set<SubQuery> queries = callerQueryDependencies.get(callGraphNode);
        if (queries != null) {
            Iterator<SubQuery> iter = queries.iterator();
            while (iter.hasNext()) {
                SubQuery mr = iter.next();
                // need to re-run the query of mr
                if (!requestRerunQuery(mr, null)) {
                    // whoops, no need to rerun this anymore.
                    iter.remove();
                }
            }
        }

    }

    private void methodReachabilityChanged(IMethod m, Context context, Set<ReachabilityQueryOrigin> tasksToReprocess) {
        OrderedPair<IMethod, Context> cgnode = new OrderedPair<>(m, context);
        Set<OrderedPair<IMethod, Context>> meths = methodMethodDependencies.get(cgnode);
        if (meths != null) {
            for (OrderedPair<IMethod, Context> p : meths) {
                // need to re-run the analysis of p
                computeReachabilityForMethod(p.fst(), p.snd(), tasksToReprocess);
            }
        }
        Set<SubQuery> queries = methodQueryDependencies.get(cgnode);
        if (queries != null) {
            Iterator<SubQuery> iter = queries.iterator();
            while (iter.hasNext()) {
                SubQuery mr = iter.next();
                // need to re-run the query of mr
                if (!requestRerunQuery(mr, tasksToReprocess)) {
                    // whoops, no need to rerun this anymore.
                    iter.remove();
                }
            }
        }
    }

    /**
     * Rerun the query mr if necessary. Will return true if the query was rerun,
     * false if it did not need to be rerurn.
     * @param mr
     */
    private boolean requestRerunQuery(SubQuery mr, Set<ReachabilityQueryOrigin> sacsToReprocess) {
        if (this.positiveCache.contains(mr)) {
            assert !this.negativeCache.contains(mr);
            // the query is already guaranteed to be true.
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
    private void queryResultChanged(SubQuery mr, Set<ReachabilityQueryOrigin> sacsToReprocess) {
        assert this.positiveCache.contains(mr);
        assert !this.negativeCache.contains(mr);

        // since the query is positive, it will never change in the future.
        // Let's save some memory by removing the set of dependent SaCs.
        Set<ReachabilityQueryOrigin> deps = this.queryDependencies.remove(mr);
        if (deps == null) {
            // nothing to do.
            return;
        }
        if (sacsToReprocess != null) {
            sacsToReprocess.addAll(deps);
        }
        else {
            // immediately execute the tasks that depended on this.
            for (ReachabilityQueryOrigin task : deps) {
                task.trigger(this.analysisHandle);
            }
        }
    }

    /**
     * This is invoked by the PointsToGraph to let us know that a new edge has been
     * added to the call graph. This allows us to retrigger computation as needed.
     *
     */
    void addCallGraphEdge(CallSiteProgramPoint callSite, Context callerContext, IMethod callee,
                                Context calleeContext) {

        // XXX turn these into separate tasks that can be run concurrently
        calleeAddedTo(callSite.getReplica(callerContext));
        callerAddedTo(new OrderedPair<>(callee, calleeContext));


    }

    /**
     * Add a dependency that originatingSaC depends on the result of query.
     *
     * @param query
     * @param originatingSaC
     */
    private void addDependency(SubQuery query, ReachabilityQueryOrigin origin) {
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

    }

    public Set<ReachabilityQueryOrigin> checkPointsToGraphDelta(GraphDelta delta) {
        Set<ReachabilityQueryOrigin> stmtsToReprocess = new HashSet<>();
        IntIterator domainIter = delta.domainIterator();
        while (domainIter.hasNext()) {
            int n = domainIter.next();
            Set<OrderedPair<IMethod, Context>> meths = killMethodDependencies.get(n);
            if (meths != null) {
                for (OrderedPair<IMethod, Context> p : meths) {
                    // need to re-run the analysis of p
                    computeReachabilityForMethod(p.fst(), p.snd(), stmtsToReprocess);
                }
            }
            Set<SubQuery> queries = killQueryDependencies.get(n);
            if (queries != null) {
                Iterator<SubQuery> iter = queries.iterator();
                while (iter.hasNext()) {
                    SubQuery mr = iter.next();
                    // need to re-run the query of mr
                    if (!requestRerunQuery(mr, stmtsToReprocess)) {
                        // whoops, no need to rerun this anymore.
                        iter.remove();
                    }
                }
            }
        }
        return stmtsToReprocess;
    }

    public void processSubQuery(SubQuery sq) {
        this.computeQuery(Collections.singleton(sq.source),
                          sq.destination,
                          sq.noKill,
                          sq.noAlloc,
                          sq.forbidden,
                          null,
                          null);
    }
}
