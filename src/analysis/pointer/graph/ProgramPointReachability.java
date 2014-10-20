package analysis.pointer.graph;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import util.OrderedPair;
import util.WorkQueue;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
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

    ProgramPointReachability(PointsToGraph g) {
        this.g = g;
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
        static final KilledAndAlloced UNREACHABLE = new KilledAndAlloced(null, null);

        private/*Set<PointsToGraphNode>*/MutableIntSet killed;
        private/*Set<InstanceKeyRecency>*/MutableIntSet alloced;

        KilledAndAlloced(MutableIntSet killed, MutableIntSet alloced) {
            this.killed = killed;
            this.alloced = alloced;
            assert (killed == null && alloced == null) || (killed != null && alloced != null);
        }

        /**
         * Take the intersection of the killed and alloced sets with the corresponding sets in res. This method
         * imperatively updates the killed and alloced sets. It returns true if and only if the killed or alloced sets
         * of this object changed.
         */
        public boolean update(KilledAndAlloced res) {
            assert (this != UNREACHABLE) : "Can't update the UNREACHABLE constant";
            assert (killed == null && alloced == null) || (killed != null && alloced != null);
            assert (res.killed == null && res.alloced == null) || (res.killed != null && res.alloced != null);

            if (this == res || res.killed == null) {
                // no change to this object.
                return false;
            }
            if (this.killed == null) {
                // we represent the "universal" sets, so intersecting with
                // the sets in res just gives us directly the sets in res.
                // So copy over the sets res.killed and res.alloced.
                this.killed = MutableSparseIntSet.createMutableSparseIntSet(0);
                this.killed.copySet(res.killed);
                this.alloced = MutableSparseIntSet.createMutableSparseIntSet(0);
                this.alloced.copySet(res.alloced);
                return true;
            }

            // intersect the sets, and see if the size of either of them changed.
            int origKilledSize = this.killed.size();
            int origAllocedSize = this.alloced.size();
            this.killed.intersectWith(res.killed);
            this.alloced.intersectWith(res.alloced);
            return (this.killed.size() != origKilledSize || this.alloced.size() != origAllocedSize);
        }

        /**
         * Add a points to graph node to the kill set.
         */
        public boolean addKill(/*PointsToGraphNode*/int n) {
            assert killed != null;
            return this.killed.add(n);
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
            assert killed == null && alloced == null;
            this.killed = MutableSparseIntSet.createMutableSparseIntSet(0);
            this.alloced = MutableSparseIntSet.createMutableSparseIntSet(0);
        }

        /**
         * Returns false if this.killed intersects with noKill, or if this.alloced intersents with noAlloc. Otherwise,
         * it returns true.
         */
        public boolean allows(IntSet noKill, IntSet noAlloc) {
            return (this.killed != null && !this.killed.containsAny(noKill))
                    && (this.alloced != null && !this.alloced.containsAny(noAlloc));
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
            res = new KilledAndAlloced(null, null);
            results.put(ipp, res);
        }
        return res;
    }

    /*
     * Can destination be reached from any InterProgramPointReplica in sources without
     * going through a program point that kills any PointsToGraphNode in noKill, and
     * without going through a program point that allocates any InstanceKey in noAlloc?
     */
    public boolean reachable(Collection<InterProgramPointReplica> sources, InterProgramPointReplica destination,
                             /*Set<PointsToGraphNode>*/IntSet noKill, /*Set<InstanceKeyRecency>*/IntSet noAlloc) {

        assert allMostRecent(noAlloc);
        // check the caches
        for (InterProgramPointReplica src : sources) {
            if (this.positiveCache.contains(new MemoResult(src, destination, noKill, noAlloc))) {
                return true;
            }
        }

        // check the negative cache, when we have such a thing...

        // try to solve it for each source.
        OrderedPair<IMethod, Context> destinationMethod = new OrderedPair<>(destination.getContainingProcedure(),
                destination.getContext());
        Set<InterProgramPointReplica> visited = new HashSet<>();
        for (InterProgramPointReplica src : sources) {
            // First check the call graph to find the set of relevant call graph nodes.
            OrderedPair<IMethod, Context> sourceMethod = new OrderedPair<>(src.getContainingProcedure(),
                    src.getContext());
            Set<OrderedPair<IMethod, Context>> relevantNodes = findRelevantNodes(sourceMethod, destinationMethod);
            if (relevantNodes.isEmpty()) {
                // this one isn't possible.
                continue;
            }
            // Now try a depth first search through the relevant nodes...
            if (searchThroughRelevantNodes(src, destination, noKill, noAlloc, relevantNodes, visited)) {
                // we found it!
                positiveCache.add(new MemoResult(src, destination, noKill, noAlloc));
                return true;
            }
        }
        // we didn't find it.
        // Store in the negative cache sometime...
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
     * @return
     */
    private Set<OrderedPair<IMethod, Context>> findRelevantNodes(OrderedPair<IMethod, Context> sourceCGNode,
                                                                 OrderedPair<IMethod, Context> destinationCGNode) {
        Set<OrderedPair<IMethod, Context>> s = new HashSet<>();
        // first find the CG nodes reachable from sourceCGNode
        Deque<OrderedPair<IMethod, Context>> q = new ArrayDeque<>();
        q.add(sourceCGNode);
        s.add(sourceCGNode);
        while (!q.isEmpty()) {
            OrderedPair<IMethod, Context> cgnode = q.poll();
            for (OrderedPair<IMethod, Context> callee : g.getCalleesOf(cgnode)) {
                if (s.add(callee)) {
                    q.add(callee);
                }
            }
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
            for (OrderedPair<IMethod, Context> callee : g.getCalleesOf(cgnode)) {
                if (s.contains(callee) && t.add(callee)) {
                    q.add(callee);
                }
            }
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
     * @return
     */
    private boolean searchThroughRelevantNodes(InterProgramPointReplica src, InterProgramPointReplica destination,
                                               IntSet noKill, IntSet noAlloc,
                                               Set<OrderedPair<IMethod, Context>> relevantNodes,
                                               Set<InterProgramPointReplica> visited) {
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
                return true;
            }
            InterProgramPoint ipp = ippr.getInterPP();
            ProgramPoint pp = ipp.getPP();

            // if it is a call, then handle the results
            if (ipp instanceof PreProgramPoint) {
                if (pp instanceof CallSiteProgramPoint) {
                    // this is a method call! Register the dependency and use some cached results
                    //XXX!@! register dependency from this result to the call site.

                    OrderedPair<CallSiteProgramPoint, Context> caller = new OrderedPair<>((CallSiteProgramPoint) pp,
                            ippr.getContext());
                    Set<OrderedPair<IMethod, Context>> calleeSet = g.getCallGraphMap().get(caller);
                    if (calleeSet == null) {
                        // no callees, so nothing to do
                        continue;
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
                                if (searchThroughRelevantNodes(calleeEntryIPPR, destination, noKill, noAlloc, relevantNodes, visited)) {
                                    // we found it!
                                    return true;
                                }
                            }
                        }
                        // now use the summary results.
                        ReachabilityResult calleeResults = getReachabilityForMethod(callee.fst(), callee.snd());
                        KilledAndAlloced normalRet = calleeResults.getResult(calleeEntryIPPR,
                                                                             calleeSummary.getNormalExitPP()
                                                                             .pre()
                                                                             .getReplica(callee.snd()));
                        KilledAndAlloced exRet = calleeResults.getResult(calleeEntryIPPR,
                                                                         calleeSummary.getExceptionExitPP()
                                                                         .pre()
                                                                         .getReplica(callee.snd()));

                        // HERE WE SHOULD BE MORE PRECISE ABOUT PROGRAM POINT SUCCESSORS, AND PAY ATTENTION TO NORMAL VS EXCEPTIONAL EXIT


                        if (normalRet.allows(noKill, noAlloc) || exRet.allows(noKill, noAlloc)) {
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

                }
                else if (pp.isNormalExitSummaryNode() || pp.isExceptionExitSummaryNode()) {
                    // We are exiting the current method!
                    //XXX!@! register dependency from this result to the callee. i.e., we should be notified if someone else calls us.

                    // let's explore the callers
                    Set<OrderedPair<CallSiteProgramPoint, Context>> callers = g.getCallGraphReverseMap()
                            .get(currentCallGraphNode);
                    if (callers == null) {
                        // no callers
                        continue;
                    }
                    for (OrderedPair<CallSiteProgramPoint, Context> callerSite : callers) {
                        OrderedPair<IMethod, Context> caller = new OrderedPair<IMethod, Context>(callerSite.fst()
                                .containingProcedure(),
                                callerSite.snd());

                        if (relevantNodes.contains(caller)) {
                            // this is a relevant node, and we need to dig into it.
                            InterProgramPointReplica callerSiteReplica = callerSite.fst()
                                    .post()
                                    .getReplica(callerSite.snd());
                            if (inSameMethod) {
                                // let's delay it as long as possible, in case we find the destination here
                                delayed.add(callerSiteReplica);
                            }
                            else {
                                // let's explore the callee now.
                                if (searchThroughRelevantNodes(callerSiteReplica,
                                                               destination,
                                                               noKill,
                                                               noAlloc,
                                                               relevantNodes,
                                                               visited)) {
                                    // we found it!
                                    return true;
                                }
                            }
                        }
                        else {
                            // not a relevant node, so no need to pursue it.
                        }

                    }

                }
                else {
                    PointsToStatement stmt = g.registrar.getStmtAtPP(pp);
                    // not a call or a return, it's just a normal statement.
                    // does ipp kill this.node?
                    if (stmt != null) {
                        // !@!XXX record dependency, since stmt.killed actually looks up the points to information.
                        PointsToGraphNode killed = stmt.killed(currentContext, g);
                        if (killed != null && noKill.contains(g.lookupDictionary(killed))) {
                            // dang! we killed something we shouldn't. Prune the search.
                            continue;
                        }

                        // is "to" allocated at this program point?
                        InstanceKeyRecency justAllocated = stmt.justAllocated(currentContext, g);
                        if (justAllocated != null) {
                            int justAllocatedKey = g.lookupDictionary(justAllocated);
                            if (g.isMostRecentObject(justAllocatedKey) && g.isTrackingMostRecentObject(justAllocatedKey) && noAlloc.contains(g.lookupDictionary(justAllocated))) {
                                // dang! we killed allocated we shouldn't. Prune the search.
                                continue;
                            }
                        }
                        // otherwise, we're OK. visited the successor
                        InterProgramPointReplica post = pp.post().getReplica(currentContext);
                        if (visited.add(post)) {
                            q.add(post);
                        }
                    }
                }
            }
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
        }
        while (!delayed.isEmpty()) {
            InterProgramPointReplica ippr = delayed.poll();
            if (searchThroughRelevantNodes(ippr, destination, noKill, noAlloc, relevantNodes, visited)) {
                return true;
            }
        }
        // we didn't find it
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
            assert res != null;
            assert source.getContainingProcedure().equals(target.getContainingProcedure());
            assert source.getContext().equals(target.getContext());
            ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> thisTargetMap = this.getTargetMap(source);
            KilledAndAlloced existing = thisTargetMap.putIfAbsent(target, res);
            assert existing == null;
        }

        public void update(ReachabilityResult res) {
            for (InterProgramPointReplica s : res.m.keySet()) {
                ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> resTargetMap = res.m.get(s);
                if (resTargetMap != null) {
                    ConcurrentMap<InterProgramPointReplica, KilledAndAlloced> thisTargetMap = this.getTargetMap(s);
                    for (InterProgramPointReplica t : resTargetMap.keySet()) {
                        KilledAndAlloced resResult = resTargetMap.get(t);
                        KilledAndAlloced thisResult = getTargetResult(thisTargetMap, t, resResult);
                        thisResult.update(resResult);
                    }
                }
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

    }

    private final ConcurrentMap<IMethod, ReachabilityResult> memoization = AnalysisUtil.createConcurrentHashMap();

    /*
     * Get the reachability results for a method.
     */
    ReachabilityResult getReachabilityForMethod(IMethod m, Context context) {
        ReachabilityResult res = memoization.get(m);
        if (res != null) {
            return res;
        }
        // no results yet.
        res = ReachabilityResult.createInitial();
        ReachabilityResult existing = memoization.putIfAbsent(m, res);
        if (existing != null) {
            // someone beat us to it, and is currently working on the results.
            return existing;
        }
        res = computeReachabilityForMethod(m, context);
        return updateMemoization(m, res);
    }

    private ReachabilityResult updateMemoization(IMethod m, ReachabilityResult res) {
        ReachabilityResult existing = memoization.get(m);
        assert existing != null;
        existing.update(res);
        return existing;
    }

    private ReachabilityResult computeReachabilityForMethod(IMethod m, Context context) {
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
            assert current.killed != null;

            if (ipp instanceof PreProgramPoint) {
                if (pp instanceof CallSiteProgramPoint) {
                    // this is a method call! Register the dependency and get some cached results
                    //XXX!@! register dependency from this result to the call site.

                    OrderedPair<CallSiteProgramPoint, Context> caller = new OrderedPair<>((CallSiteProgramPoint) pp,
                                                                                          context);
                    Set<OrderedPair<IMethod, Context>> calleeSet = g.getCallGraphMap().get(caller);
                    if (calleeSet == null) {
                        // no callees, so nothing to do
                        continue;
                    }

                    KilledAndAlloced post = getOrCreate(results, pp.post());
                    boolean changed = post.update(current);
                    for (OrderedPair<IMethod, Context> callee : calleeSet) {
                        ReachabilityResult calleeResults = getReachabilityForMethod(callee.fst(), callee.snd());
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
                        changed |= post.update(normalRet);
                        changed |= post.update(exRet);

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
                        // !@!XXX record dependency, since stmt.killed actually looks up the points to information.
                        PointsToGraphNode killed = stmt.killed(context, g);
                        if (killed != null) {
                            changed |= current.addKill(g.lookupDictionary(killed));
                        }

                        // is "to" allocated at this program point?
                        InstanceKeyRecency justAllocated = stmt.justAllocated(context, g);
                        if (justAllocated != null) {
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
                    if (succResults.update(current)) {
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

        rr.add(entryIPP.getReplica(context), normExitIPP.getReplica(context), results.get(normExitIPP));
        rr.add(entryIPP.getReplica(context), exExitIPP.getReplica(context), results.get(exExitIPP));
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
    private final Set<MemoResult> positiveCache = AnalysisUtil.createConcurrentSet();

    private static class MemoResult {
        final InterProgramPointReplica source;
        final InterProgramPointReplica destination;
        final/*Set<PointsToGraphNode>*/IntSet noKill;
        final/*Set<InstanceKeyRecency>*/IntSet noAlloc;

        MemoResult(InterProgramPointReplica source, InterProgramPointReplica destination, /*Set<PointsToGraphNode>*/
                   IntSet noKill, final/*Set<InstanceKeyRecency>*/IntSet noAlloc) {
            this.source = source;
            this.destination = destination;
            this.noKill = noKill;
            this.noAlloc = noAlloc;

        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + destination.hashCode();
            result = prime * result + source.hashCode();
            result = prime * result + noAlloc.hashCode();
            result = prime * result + noKill.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!super.equals(obj)) {
                return false;
            }
            if (!(obj instanceof MemoResult)) {
                return false;
            }
            MemoResult other = (MemoResult) obj;
            if (!source.equals(other.source)) {
                return false;
            }
            if (!destination.equals(other.destination)) {
                return false;
            }
            if (!noAlloc.equals(other.noAlloc)) {
                return false;
            }
            if (!noKill.equals(other.noKill)) {
                return false;
            }
            return true;
        }

    }

}
