package analysis.pointer.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import util.WorkQueue;
import util.intmap.ConcurrentIntMap;
import util.intmap.IntMap;
import util.intmap.SparseIntMap;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.KilledAndAlloced.ThreadLocalKilledAndAlloced;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPoint;
import analysis.pointer.statements.ProgramPoint.PostProgramPoint;
import analysis.pointer.statements.ProgramPoint.PreProgramPoint;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

public class MethodReachability {
    private final ProgramPointReachability ppr;
    private final PointsToGraph g;

    /**
     * Method reachability queries indicated by the start node depend on method reachability queries starting at a
     * different call graph node (OrderedPair<IMethod, Context>)
     */
    // ConcurrentMap<OrderedPair<IMethod, Context>, Set<OrderedPair<IMethod, Context>>>
    private final ConcurrentIntMap<MutableIntSet> methodMethodDependencies = AnalysisUtil.createConcurrentIntMap();

    /**
     * Method reachability queries (beginning at a particular call graph node) depend on the killset at a particular
     * PointsToGraphNode
     */
    // ConcurrentMap<ReferenceVariableReplica, Set<OrderedPair<IMethod, Context>>>
    private final ConcurrentIntMap<MutableIntSet> killMethodDependencies = AnalysisUtil.createConcurrentIntMap();

    MethodReachability(ProgramPointReachability ppr, PointsToGraph g) {
        this.ppr = ppr;
        this.g = g;
    }

    private MethodSummaryKillAndAllocChanges recordMethodReachability(/*OrderedPair<IMethod, Context>*/int cgnode,
                                                               KilledAndAlloced normalExitResults,
                                                               KilledAndAlloced exceptionalExitResults,
                                                               IntMap<KilledAndAlloced> tunnel) {
        long start = 0L;
        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
        }
        MethodSummaryKillAndAlloc existing = methodSummaryMemoization.get(cgnode);
        assert existing != null;
        MethodSummaryKillAndAllocChanges changed = (existing.update(normalExitResults, exceptionalExitResults, tunnel));
        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            this.ppr.recordMethodTime.addAndGet(System.currentTimeMillis() - start);
        }
        return changed;
    }

    // ConcurrentMap<OrderedPair<IMethod, Context>, MethodSummaryKillAndAlloc>
    private final ConcurrentIntMap<MethodSummaryKillAndAlloc> methodSummaryMemoization = AnalysisUtil.createConcurrentIntMap();

    /*
     * Get the reachability results for a method.
     */
    MethodSummaryKillAndAlloc getReachabilityForMethod(/*OrderedPair<IMethod, Context>*/int cgNode) {
        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            this.ppr.totalMethodReach.incrementAndGet();
        }
        if (ProgramPointReachability.DEBUG) {
            OrderedPair<IMethod, Context> n = g.lookupCallGraphNodeDictionary(cgNode);
            System.err.println("PPR%% METHOD " + PrettyPrinter.methodString(n.fst()) + " in " + n.snd());
        }
        MethodSummaryKillAndAlloc res = methodSummaryMemoization.get(cgNode);
        if (res != null) {
            if (ProgramPointReachability.DEBUG) {
                System.err.println("PPR%% \tCACHED");
                System.err.println("PPR%% \t\t normalExit" + res.getNormalExitResult());
                System.err.println("PPR%% \t\t exceptionalExit" + res.getExceptionalExitResult());
            }
            if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
                this.ppr.cachedMethodReach.incrementAndGet();
            }
            return res;
        }
        // no results yet.
        res = MethodSummaryKillAndAlloc.createInitial(cgNode);
        MethodSummaryKillAndAlloc existing = methodSummaryMemoization.putIfAbsent(cgNode, res);
        if (existing != null) {
            // someone beat us to it, and is currently working on the results.
            if (ProgramPointReachability.DEBUG) {
                System.err.println("PPR%% \tBEATEN");
                System.err.println("PPR%% \t\t normalExit" + res.getNormalExitResult());
                System.err.println("PPR%% \t\t exceptionalExit" + res.getExceptionalExitResult());
            }
            if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
                this.ppr.cachedMethodReach.incrementAndGet();
            }
            return existing;
        }

        // res is the summary, but is currently the default initial entry.
        // Let's compute it! The call to recomputeMethodReachability will imperatively update res.
        MutableIntSet mis = MutableSparseIntSet.createMutableSparseIntSet(2);
        mis.add(cgNode);
        this.recomputeMethodReachability(mis);

        if (ProgramPointReachability.DEBUG) {
            OrderedPair<IMethod, Context> n = g.lookupCallGraphNodeDictionary(cgNode);
            System.err.println("PPR%% \tCOMPUTED " + PrettyPrinter.methodString(n.fst()) + " in " + n.snd());
            System.err.println("PPR%% \t\t normalExit" + res.getNormalExitResult());
            System.err.println("PPR%% \t\t exceptionalExit" + res.getExceptionalExitResult());
        }
        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            this.ppr.computedMethodReach.incrementAndGet();
        }
        return res;
    }

    /**
     * Trigger recomputation of all of the call graph nodes in the set cgnodes. If the recomputation triggers
     * dependencies for other methods to be recomputed, this will be done.
     *
     * This method assumes that it owns toRecompute, and can modify it at will.
     *
     * @param cgNodes
     */
    private void recomputeMethodReachability(/*Set<OrderedPair<IMethod, Context>>*/MutableIntSet toRecompute) {
        IntMap<MethodSummaryKillAndAllocChanges> summaryChanged = new SparseIntMap<>();
        while (!toRecompute.isEmpty()) {
            // get the next node to recompute.
            // The max is the cheapest to remove, so lets do that.
            int cgNode = toRecompute.max();
            toRecompute.remove(cgNode);

            MethodSummaryKillAndAllocChanges changed = computeMethodSummary(cgNode);
            if (changed != null) {
                // the method summary changed!
                if (summaryChanged.containsKey(cgNode)) {
                    summaryChanged.get(cgNode).merge(changed);
                }
                else {
                    summaryChanged.put(cgNode, changed);
                }
                // this may cause us to recompute some other methods.
                /*Set<OrderedPair<IMethod, Context>>*/IntSet meths = methodMethodDependencies.get(cgNode);
                if (meths != null) {
                    toRecompute.addAll(meths);
                }
            }

        }

        // we have now finished all the recomputation of the summaries. Let ppr know.
        this.ppr.methodSummariesChanged(summaryChanged);
    }

    /**
     * Compute the summary for the specified call-graph node, and record the result. Returns true if and only if the
     * result differed from the existing result.
     *
     * THIS METHOD SHOULD ONLY BE CALLED BY recomputeMethodReachability, TO ENSURE THAT DEPENDENCIES ARE UPDATED
     * CORRECTLY.
     *
     * @param cgNode
     * @return
     */
    private MethodSummaryKillAndAllocChanges computeMethodSummary(/*OrderedPair<IMethod, Context>*/int cgNode) {
        long start = 0L;
        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
        }

        OrderedPair<IMethod, Context> n = g.lookupCallGraphNodeDictionary(cgNode);
        Context context = n.snd();
        if (ProgramPointReachability.DEBUG) {
            System.err.println("PPR%% COMPUTING FOR " + PrettyPrinter.methodString(n.fst()) + " in " + context);
        }

        // do a dataflow over the program points.

        // The map facts records what facts are true at each interprogram point.
        Map<InterProgramPoint, KilledAndAlloced> facts = new HashMap<>();
        ConcurrentIntMap<KilledAndAlloced> tunnel = AnalysisUtil.createConcurrentIntMap();
        KilledAndAlloced kaa = KilledAndAlloced.createLocalUnreachable();
        kaa.setEmpty();
        tunnel.put(cgNode, kaa);

        WorkQueue<InterProgramPoint> q = new WorkQueue<>();
        Set<InterProgramPoint> visited = new HashSet<>();

        MethodSummaryNodes summ = g.getRegistrar().getMethodSummary(n.fst());
        PostProgramPoint entryIPP = summ.getEntryPP().post();
        q.add(entryIPP);
        getOrCreate(facts, entryIPP).setEmpty();

        while (!q.isEmpty()) {
            InterProgramPoint ipp = q.poll();
            if (!visited.add(ipp)) {
                continue;
            }
            if (ProgramPointReachability.DEBUG) {
                System.err.println("PPR%% \tQ " + ipp);
            }
            ProgramPoint pp = ipp.getPP();
            assert pp.getContainingProcedure().equals(n.fst());
            KilledAndAlloced current = getOrCreate(facts, ipp);

            assert !current.isUnreachable() : "Program point " + ipp + " appears to be unreachable";

            if (ipp instanceof PreProgramPoint) {
                if (pp instanceof CallSiteProgramPoint) {
                    // this is a method call!
                    /*ProgramPointReplica*/int callSite = g.lookupCallSiteReplicaDictionary(pp.getReplica(context));

                    CallSiteProgramPoint cspp = (CallSiteProgramPoint) pp;

                    /*Set<OrderedPair<IMethod, Context>>*/IntSet calleeSet = g.getCalleesOf(callSite);
                    if (this.ppr.getApproximateCallSitesAndFieldAssigns().isApproximate(callSite)) {
                        if (!calleeSet.isEmpty()) {
                            if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
                                this.ppr.nonEmptyApproximatedCallSites.add(callSite);
                            }
                            if (PointsToAnalysis.outputLevel > 0) {
                                System.err.println("APPROXIMATING non-empty call site "
                                        + g.lookupCallSiteReplicaDictionary(callSite));
                            }
                        }
                        // This is a call site with no callees that we approximate by assuming it returns normally
                        // and kills nothing
                        KilledAndAlloced postNormal = getOrCreate(facts, cspp.getNormalExit().post());
                        postNormal.setEmpty();
                        q.add(cspp.getNormalExit().post());

                        KilledAndAlloced postEx = getOrCreate(facts, cspp.getExceptionExit().post());
                        postEx.setEmpty();
                        q.add(cspp.getExceptionExit().post());
                        continue;
                    }

                    if (calleeSet.isEmpty()) {
                        // no callees, so nothing to do
                        if (ProgramPointReachability.DEBUG) {
                            System.err.println("PPR%% \t\tno callees " + ipp);
                        }
                        continue;
                    }

                    KilledAndAlloced postNormal = getOrCreate(facts, cspp.getNormalExit().post());
                    KilledAndAlloced postEx = getOrCreate(facts, cspp.getExceptionExit().post());

                    /*Iterator<OrderedPair<IMethod, Context>>*/IntIterator calleeIter = calleeSet.intIterator();
                    while (calleeIter.hasNext()) {
                        int calleeInt = calleeIter.next();
                        addMethodMethodDependency(cgNode, calleeInt);
                        MethodSummaryKillAndAlloc calleeResults = getReachabilityForMethod(calleeInt);

                        KilledAndAlloced normalRet = calleeResults.getNormalExitResult();
                        KilledAndAlloced exRet = calleeResults.getExceptionalExitResult();

                        // The final results will be the sets that are killed or alloced for all the callees
                        //     so intersect the results
                        postNormal.meet(ThreadLocalKilledAndAlloced.join(current, normalRet));
                        postEx.meet(ThreadLocalKilledAndAlloced.join(current, exRet));

                        if (ProgramPointReachability.USE_TUNNELS) {
                            // add to the tunnel. That is, everything that can be reached from callee is reachable from
                            // this node, albeit, with killed and alloced as indicated by current.
                            IntIterator reachableFromCalleeIterator = calleeResults.tunnel.keyIterator();
                            while (reachableFromCalleeIterator.hasNext()) {
                                int reachableFromCallee = reachableFromCalleeIterator.next();
                                KilledAndAlloced tunnelToCallee = tunnel.get(reachableFromCallee);
                                if (tunnelToCallee == null) {
                                    tunnelToCallee = KilledAndAlloced.createLocalUnreachable();
                                    tunnel.put(reachableFromCallee, tunnelToCallee);
                                }
                                tunnelToCallee.meet(ThreadLocalKilledAndAlloced.join(calleeResults.tunnel.get(reachableFromCallee),
                                                                          current));
                            }
                        }
                    }
                    // Add the successor program points to the queue, if they are reachable
                    if (!postNormal.isUnreachable()) {
                        q.add(cspp.getNormalExit().post());
                    }
                    if (!postEx.isUnreachable()) {
                        q.add(cspp.getExceptionExit().post());
                    }
                    continue;
                } // end CallSiteProgramPoint
                else if (pp.isNormalExitSummaryNode() || pp.isExceptionExitSummaryNode()) {
                    // not much to do here. The results will be copied once the work queue finishes.
                    if (ProgramPointReachability.DEBUG2) {
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
                            addKillMethodDependency(cgNode, stmt.getReadDependencyForKillField(context, g.getHaf()));

                            OrderedPair<Boolean, PointsToGraphNode> killed = stmt.killsNode(context, g);
                            if (killed != null) {

                                if (this.ppr.getApproximateCallSitesAndFieldAssigns().isApproximateKillSet(stmt,
                                                                                                           context)) {
                                    if (killed.fst()) {
                                        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
                                            this.ppr.nonEmptyApproximatedKillSets.add(new OrderedPair<>(stmt, context));
                                        }
                                        if (PointsToAnalysis.outputLevel > 0) {
                                            System.err.println("APPROXIMATING kill set for field assign with receivers. "
                                                    + stmt + " in " + context);
                                        }
                                    }
                                    // This statement is being approximated since it has no receivers
                                    removeKillMethodDependency(cgNode,
                                                               stmt.getReadDependencyForKillField(context, g.getHaf()));
                                }
                                else if (!killed.fst()) {
                                    if (ProgramPointReachability.DEBUG2) {
                                        System.err.println("PPR%% \t\tCould Kill "
                                                + stmt.getReadDependencyForKillField(context, g.getHaf()));
                                    }

                                    // not enough info available yet.
                                    // add a dependency since more information may change this search
                                    // conservatively assume that it kills any kind of the field we give it.
                                    current.addMaybeKilledField(stmt.getMaybeKilledField());
                                }
                                else if (killed.snd() != null) {
                                    if (ProgramPointReachability.DEBUG2) {
                                        System.err.println("PPR%% \t\tDoes Kill "
                                                + stmt.getReadDependencyForKillField(context, g.getHaf()) + " "
                                                + g.lookupDictionary(killed.snd()));
                                    }
                                    // this statement really does kill something.
                                    current.addKill(g.lookupDictionary(killed.snd()));
                                }
                                else if (killed.snd() == null) {
                                    // we have enough information to know that this statement does not kill a node we care about
                                    removeKillMethodDependency(cgNode,
                                                               stmt.getReadDependencyForKillField(context, g.getHaf()));
                                }
                            }
                        } // end stmt.mayKillNode
                        else {
                            // The statement should not be able to kill a node.
                            removeKillMethodDependency(cgNode, stmt.getReadDependencyForKillField(context, g.getHaf()));

                            assert stmt.killsNode(context, g) == null
                                    || (stmt.killsNode(context, g).fst() == true && stmt.killsNode(context, g).snd() == null);
                        } // end !stmt.mayKillNode

                        // is anything allocated at this program point?
                        InstanceKeyRecency justAllocated = stmt.justAllocated(context, g);
                        if (justAllocated != null) {
                            assert justAllocated.isRecent();
                            int/*InstanceKeyRecency*/justAllocatedKey = g.lookupDictionary(justAllocated);
                            if (g.isMostRecentObject(justAllocatedKey)
                                    && g.isTrackingMostRecentObject(justAllocatedKey)) {
                                if (ProgramPointReachability.DEBUG2) {
                                    System.err.println("PPR%% \t\tDoes Alloc " + justAllocatedKey);
                                }
                                if (current.isUnreachable()) {
                                    System.err.println("Null alloc set for " + stmt + " at " + ipp + " current="
                                            + current);
                                }
                                current.addAlloced(justAllocatedKey);
                            }
                        }
                    } // end stmt != null
                      // Add the post program point to continue the traversal
                    KilledAndAlloced postResults = getOrCreate(facts, pp.post());
                    postResults.meet(current);
                    q.add(pp.post());
                } // end other pre program point
            } // end pre program point
            else if (ipp instanceof PostProgramPoint) {
                Set<ProgramPoint> ppSuccs = pp.succs();
                // Add all the successor program points
                for (ProgramPoint succ : ppSuccs) {
                    KilledAndAlloced succResults = getOrCreate(facts, succ.pre());
                    succResults.meet(current);
                    q.add(succ.pre());
                }
            } // end post program point
            else {
                throw new IllegalArgumentException("Don't know about this kind of interprogrampoint");
            }
        } // queue is now empty

        PreProgramPoint normExitIPP = summ.getNormalExitPP().pre();
        PreProgramPoint exExitIPP = summ.getExceptionExitPP().pre();

        MethodSummaryKillAndAllocChanges changed = recordMethodReachability(cgNode,
                                                   getOrCreate(facts, normExitIPP),
                                                   getOrCreate(facts, exExitIPP),
                                                   tunnel);
        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            this.ppr.methodReachTime.addAndGet(System.currentTimeMillis() - start);
        }
        return changed;
    }


    /**
     * A MethodSummaryKillAndAlloc records the reachability results of a single method in a context, that is, which for
     * a subset of the source and destination pairs of InterProgramPointReplica in the method and context, what are the
     * KilledAndAlloced sets that summarize all paths from the source to the destination.
     *
     * This object must be thread safe.
     */
    static class MethodSummaryKillAndAlloc {
        /**
         * For all paths from the entry node of this method to the normal exit, what are the illed and alloced things?
         */
        KilledAndAlloced normalExitSummary;
        /**
         * For all paths from the entry node of this method to the exceptional exit, what are the illed and alloced
         * things?
         */
        KilledAndAlloced exceptionalExitSummary;

        /**
         * Map from call graph nodes to KilledAndAlloced info. Let n be a call graph node, and let m be the call graph
         * node that this object is a summary for. If tunnel.contains(n) is true, then the entry program point of n is
         * reachable from the entry program point of m, and moreover, tunnel.get(n) describes the kill and alloc set,
         * i.e., all paths from the entry point of m to the entry point of n will kill the PointsToGraphNodes in
         * tunnel.get(n).killed and allocate the InstanceKeyRecencys in tunnel.get(n).alloced. If n is not in the domain
         * of tunnel (i.e., tunnel.contains(n) is false), then (to the best of our current knowledge) the entry point of
         * n is not reachable from the entry point of m.
         */
        final ConcurrentIntMap<KilledAndAlloced> tunnel = AnalysisUtil.createConcurrentIntMap();

        private MethodSummaryKillAndAlloc(/*OrderedPair<IMethod, Context>*/int cgnode) {
            if (ProgramPointReachability.USE_TUNNELS) {
                // the method that this summary is for is reachable without killing or allocating anything.
                KilledAndAlloced kaa = KilledAndAlloced.createThreadSafeUnreachable();
                kaa.setEmpty();
                this.tunnel.put(cgnode, kaa);
            }
            this.normalExitSummary = KilledAndAlloced.createThreadSafeUnreachable();
            this.exceptionalExitSummary = KilledAndAlloced.createThreadSafeUnreachable();
        }

        /**
         * Create a result in which every result is unreachable. cgnode indicates the call graph node that this summary
         * is for.
         *
         * @return
         */
        public static MethodSummaryKillAndAlloc createInitial(/*OrderedPair<IMethod, Context>*/int cgnode) {
            return new MethodSummaryKillAndAlloc(cgnode);
        }

        public KilledAndAlloced getNormalExitResult() {
            return this.normalExitSummary;
        }

        public KilledAndAlloced getExceptionalExitResult() {
            return this.exceptionalExitSummary;
        }

        /**
         * Update the summary. Return a description of how this object was modified, or null if no modifications were
         * made.
         *
         * @return
         */
        public MethodSummaryKillAndAllocChanges update(KilledAndAlloced normalExitSummary,
                                                       KilledAndAlloced exceptionalExitSummary,
                              IntMap<KilledAndAlloced> tunnel) {
            boolean resultsChanged = false;
            resultsChanged |= this.normalExitSummary.meet(normalExitSummary);
            resultsChanged |= this.exceptionalExitSummary.meet(exceptionalExitSummary);

            MutableIntSet changedTunnels = MutableSparseIntSet.makeEmpty();
            if (ProgramPointReachability.USE_TUNNELS) {
                IntIterator iter = tunnel.keyIterator();
                while (iter.hasNext()) {
                    int key = iter.next();
                    KilledAndAlloced kaa = this.tunnel.get(key);
                    if (kaa == null) {
                        kaa = KilledAndAlloced.createThreadSafeUnreachable();
                        KilledAndAlloced existing = this.tunnel.putIfAbsent(key, kaa);
                        if (existing != null) {
                            kaa = existing;
                        }
                    }
                    if (kaa.meet(tunnel.get(key))) {
                        changedTunnels.add(key);
                    }
                }
            }
            if (resultsChanged || !changedTunnels.isEmpty()) {
                return new MethodSummaryKillAndAllocChanges(resultsChanged, changedTunnels);
            }
            return null;
        }

        @Override
        public int hashCode() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean equals(Object obj) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("normalExit: ");
            sb.append(this.normalExitSummary);
            sb.append("exExit: ");
            sb.append(this.exceptionalExitSummary);
            sb.append("\nTunnels:");
            IntIterator cgnodes = tunnel.keyIterator();
            while (cgnodes.hasNext()) {
                int cgnode = cgnodes.next();
                sb.append(cgnode + " -> " + tunnel.get(cgnode));
            }
            return sb.toString();
        }
    }

    /**
     * A MethodSummaryKillAndAllocChanges class describes the changes that have been made to a MethodSummaryKillAndAlloc
     * when it is updated.
     */
    static class MethodSummaryKillAndAllocChanges {
        private boolean resultsChanged;
        private MutableIntSet changedTunnels;

        public MethodSummaryKillAndAllocChanges(boolean resultsChanged, MutableIntSet changedTunnels) {
            assert resultsChanged || !changedTunnels.isEmpty();
            this.resultsChanged = resultsChanged;
            this.changedTunnels = changedTunnels;
        }

        public void merge(MethodSummaryKillAndAllocChanges changed) {
            this.resultsChanged |= changed.resultsChanged;
            this.changedTunnels.addAll(changed.changedTunnels);
        }

        public boolean resultsChanged() {
            return this.resultsChanged;
        }

        public IntSet changedTunnels() {
            return this.changedTunnels;
        }

    }


    /**
     * We need to reanalyze the method results for "dependee" if the reachability results for callGraphNode changes.
     *
     * @param dependee
     * @param callGraphNode
     */
    private void addMethodMethodDependency(/*OrderedPair<IMethod, Context>*/int dependee,
    /*OrderedPair<IMethod, Context>*/int callGraphNode) {
        long start = 0L;
        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
        }
        /*Set<OrderedPair<IMethod, Context>>*/MutableIntSet s = methodMethodDependencies.get(callGraphNode);
        if (s == null) {
            s = AnalysisUtil.createConcurrentIntSet();
            MutableIntSet existing = methodMethodDependencies.putIfAbsent(callGraphNode, s);
            if (existing != null) {
                s = existing;
            }
        }
        s.add(dependee);
        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            this.ppr.methodCallerDepTime.addAndGet(System.currentTimeMillis() - start);
        }
    }

    /**
     * This is invoked by the PointsToGraph (via the this.ppr object) to let us know that a new edge has been added to
     * the call graph. This allows us to retrigger computation as needed.
     *
     */
    public void addCallGraphEdge(/*ProgramPointReplica*/int callerSite, /*OrderedPair<IMethod, Context>*/
                                 int calleeCGNode) {
        // the call site changed! Recompute the reachability of the caller, if we have computed it previously
        ProgramPointReplica callerSitePPR = this.g.lookupCallSiteReplicaDictionary(callerSite);
        OrderedPair<IMethod, Context> caller = new OrderedPair<IMethod, Context>(callerSitePPR.getPP()
                                                                                              .getContainingProcedure(),
                                                                                 callerSitePPR.getContext());

        int callerCGNode = g.lookupCallGraphNodeDictionary(caller);
        if (methodSummaryMemoization.containsKey(callerCGNode)) {
            MutableIntSet s = MutableSparseIntSet.createMutableSparseIntSet(2);
            s.add(callerCGNode);
            recomputeMethodReachability(s);
        }
    }


    /**
     * This is invoked by the PointsToGraph (via the this.ppr object) to let us know that a call site has been marked as
     * appoximate, meaning we should assume can terminate normally and exceptionally, even though it has no callees.
     */
    public void addApproximateCallSite(int callSite) {
        // the call site changed! Recompute the reachability of the caller, if we have computed it previously
        ProgramPointReplica callerSitePPR = this.g.lookupCallSiteReplicaDictionary(callSite);
        OrderedPair<IMethod, Context> caller = new OrderedPair<IMethod, Context>(callerSitePPR.getPP()
                                                                                              .getContainingProcedure(),
                                                                                 callerSitePPR.getContext());

        int callerCGNode = g.lookupCallGraphNodeDictionary(caller);
        if (methodSummaryMemoization.containsKey(callerCGNode)) {
            MutableIntSet s = MutableSparseIntSet.createMutableSparseIntSet(2);
            s.add(callerCGNode);
            recomputeMethodReachability(s);
        }
    }

    private void addKillMethodDependency(/*OrderedPair<IMethod, Context>*/int callGraphNode,
                                         ReferenceVariableReplica readDependencyForKillField) {
        long start = 0L;
        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
        }
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
        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            this.ppr.killCallerDepTime.addAndGet(System.currentTimeMillis() - start);
        }
    }

    private void removeKillMethodDependency(/*OrderedPair<IMethod, Context>*/int callGraphNode,
                                            ReferenceVariableReplica readDependencyForKillField) {
        long start = 0L;
        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            start = System.currentTimeMillis();
        }
        if (readDependencyForKillField == null) {
            return;
        }
        int n = g.lookupDictionary(readDependencyForKillField);

        /*Set<OrderedPair<IMethod, Context>>*/MutableIntSet s = killMethodDependencies.get(n);
        if (s != null) {
            s.remove(callGraphNode);
        }
        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            this.ppr.killCallerDepTime.addAndGet(System.currentTimeMillis() - start);
        }
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
            res = KilledAndAlloced.createLocalUnreachable();
            results.put(ipp, res);
        }
        return res;
    }

    void addApproximateFieldAssign(/*ReferenceVariableReplica*/int killDependency) {
        /*Set<OrderedPair<IMethod, Context>>*/IntSet meths = killMethodDependencies.get(killDependency);
        if (meths != null) {
            recomputeMethodReachability(MutableSparseIntSet.make(meths));
        }

    }

    void checkPointsToGraphDelta(GraphDelta delta) {
        IntIterator domainIter = delta.domainIterator();
        MutableIntSet toRecompute = MutableSparseIntSet.makeEmpty();
        while (domainIter.hasNext()) {
            int n = domainIter.next();
            /*Set<OrderedPair<IMethod, Context>>*/IntSet meths = killMethodDependencies.get(n);
            if (meths != null) {
                toRecompute.addAll(meths);
            }
        }
        if (!toRecompute.isEmpty()) {
            recomputeMethodReachability(toRecompute);
        }
    }

}
