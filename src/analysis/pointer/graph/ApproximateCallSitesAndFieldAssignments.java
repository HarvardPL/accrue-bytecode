package analysis.pointer.graph;

import java.util.Set;

import util.OrderedPair;
import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.LocalToFieldStatement;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * Call sites with no non-null receiver that will be approximated by assuming that the call can terminate normally or
 * field assignments with no receiver that are approximated by assuming they have an empty kill set. This allows program
 * point reachability queries to succeed even in the presence of these calls or assignments. This may be imprecise if a
 * call site or field access actually has no targets, but there are some instances (e.g. reflection) where unsoundness
 * in the analysis causes this issue.
 */
public class ApproximateCallSitesAndFieldAssignments {

    /**
     * Print the call sites and field assigns that are being approximated
     */
    private static final boolean PRINT_APPROXIMATIONS = false;

    /**
     * THE ANALYSIS IS UNSOUND IF TRUE
     */
    private static final boolean DO_NOT_RUN = false;
    /**
     * All no-target call sites that have been approximated so far
     */
    private final/*Set<ProgramPointReplica>*/MutableIntSet approxCallSites = AnalysisUtil.createConcurrentIntSet();
    /**
     * All no-receiver field accesses that have been approximated so far
     */
    private final Set<StmtAndContext> approxFieldAssignments = AnalysisUtil.createConcurrentSet();
    /**
     * All program point replicas that have been visited by this algorithm
     */
    private Set<ProgramPointReplica> allVisited = AnalysisUtil.createConcurrentSet();
    /**
     * The last program point replica to be visited (null if no pp has been visited yet)
     */
    private ProgramPointReplica lastVisited = null;
    /**
     * Has the algorithm visited every node in the interprocedural program point graph
     */
    private boolean finished = false;
    /**
     * Points-to graph
     */
    private final PointsToGraph g;

    /**
     * Create a new instance of the algorithm for computing approximate call sites and data structures to store them
     *
     * @param g points-to graph
     */
    public ApproximateCallSitesAndFieldAssignments(PointsToGraph g) {
        this.g = g;
    }

    private boolean recordApproximateCallSite(/*ProgramPointReplica*/int callSite) {
        return approxCallSites.add(callSite);
    }

    public boolean isApproximate(/*ProgramPointReplica*/int callSite) {
        return approxCallSites.contains(callSite);
    }

    /**
     * Find the next approximation
     *
     * @return the next approximation to be made
     */
    public Approximation findNextApproximation() {
        if (DO_NOT_RUN) {
            return Approximation.NONE;
        }

        // XXX Reset the visited set and start from the beginning
        allVisited = AnalysisUtil.createConcurrentSet();
        lastVisited = null;

        ProgramPointReplica first;
        if (lastVisited == null) {
            first = getEntryPP();
        }
        else {
            first = lastVisited;
        }
        WorkQueue<ProgramPointReplica> q = new WorkQueue<>();
        allVisited.add(first);
        q.add(first);

        while (!q.isEmpty()) {
            ProgramPointReplica current = q.poll();
            ProgramPoint pp = current.getPP();
            if (pp instanceof CallSiteProgramPoint) {
                int currentInt = g.lookupCallSiteReplicaDictionary(current);
                CallSiteProgramPoint cspp = (CallSiteProgramPoint) pp;
                if (cspp.isClinit() && !g.getClassInitializers().contains(cspp.getClinit())) {
                    // This is a class initializer that has not been added to the call graph yet.
                    // Add the next program point anyway. This is potentially imprecise, but sound.

                    // We do this since some of the clinit program points are for classes that we
                    // (imprecisely) think could be initialized (based on type information) before starting
                    // the points-to analysis, but once we have more precise information it turns out
                    // they never can be initialized.
                    addSuccs(current, q);
                    continue;
                }

                if (!isApproximate(currentInt)) {
                    /*Set<OrderedPair<IMethod,Context>>*/IntSet callees = g.getCalleesOf(currentInt);
                    if (callees.isEmpty()) {
                        // 1. This is a call-site
                        // 2. It has not yet been approximated
                        // 3. It has no callees
                        // This means it is the next approximated call-site
                        recordApproximateCallSite(currentInt);
                        lastVisited = current;
                        if (PRINT_APPROXIMATIONS) {
                            System.err.println("APPROXIMATING " + PrettyPrinter.methodString(cspp.getCallee()) + " from "
                                    + PrettyPrinter.methodString(cspp.getCaller()) + " in " + current.getContext());
                        }
                        return Approximation.getCallSiteApproximation(currentInt);
                    }

                    // This is a call-site with callees continue the search in the callees
                    IntIterator iter = callees.intIterator();
                    while (iter.hasNext()) {
                        OrderedPair<IMethod, Context> callee = g.lookupCallGraphNodeDictionary(iter.next());
                        ProgramPoint calleeEntryPP = g.getRegistrar().getMethodSummary(callee.fst()).getEntryPP();
                        ProgramPointReplica calleeEntryRep = calleeEntryPP.getReplica(callee.snd());
                        if (allVisited.add(calleeEntryRep)) {
                            q.add(calleeEntryRep);
                        }
                    }
                    continue;
                }
                // The call site has already been approximated add the normal exit
                ProgramPointReplica normalExit = cspp.getNormalExit().getReplica(current.getContext());
                if (allVisited.add(normalExit)) {
                    q.add(normalExit);
                }
            } // end call site program point

            if (g.getRegistrar().getStmtAtPP(pp) instanceof LocalToFieldStatement) {
                // This is the program point for a field assignment
                LocalToFieldStatement stmt = (LocalToFieldStatement) g.getRegistrar().getStmtAtPP(pp);
                OrderedPair<Boolean, PointsToGraphNode> killed = stmt.killsNode(current.getContext(), g);
                if (!killed.fst() && !isApproximateKillSet(stmt, current.getContext())) {
                    // The receiver of the field access has an empty point-to set
                    // Previously we have _unsoundly_ assumed that it kills any field with the same name and type
                    // From now on we _soundly_ but _imprecisely_ assume that it kills nothing
                    if (PRINT_APPROXIMATIONS) {
                        System.err.println("APPROXIMATING kill set for " + stmt + " in " + current.getContext());
                    }
                    approxFieldAssignments.add(new StmtAndContext(stmt, current.getContext()));
                    lastVisited = current;
                    return Approximation.getFieldAssignApproximation(new OrderedPair<>(stmt, current.getContext()));
                }
            }

            if (pp.isExceptionExitSummaryNode()) {
                // This is an exceptional exit find the call site and add the exceptional successor
                // XXX return to the same call-site that was added?
                OrderedPair<IMethod, Context> cgNode = new OrderedPair<>(current.getPP().getContainingProcedure(),
                                                                         current.getContext());
                int currentCallGraphNode = g.lookupCallGraphNodeDictionary(cgNode);
                /*Set<OrderedPair<CallSiteProgramPoint, Context>>*/IntSet callers = g.getCallersOf(currentCallGraphNode);
                if (callers == null) {
                    // no callers
                    continue;
                }
                /*Iterator<OrderedPair<CallSiteProgramPoint, Context>>*/IntIterator callerIter = callers.intIterator();
                while (callerIter.hasNext()) {
                    ProgramPointReplica callerSite = g.lookupCallSiteReplicaDictionary(callerIter.next());
                    CallSiteProgramPoint cspp = (CallSiteProgramPoint) callerSite.getPP();
                    ProgramPointReplica callerSiteReplica = cspp.getExceptionExit().getReplica(callerSite.getContext());
                    if (allVisited.add(callerSiteReplica)) {
                        q.add(callerSiteReplica);
                    }
                }
                continue;
            }

            if (pp.isNormalExitSummaryNode()) {
                // This is a normal exit find the call site and add the normal successor
                // XXX return to the same call-site that was added?
                OrderedPair<IMethod, Context> cgNode = new OrderedPair<>(current.getPP().getContainingProcedure(),
                                                                         current.getContext());
                int currentCallGraphNode = g.lookupCallGraphNodeDictionary(cgNode);
                /*Set<OrderedPair<CallSiteProgramPoint, Context>>*/IntSet callers = g.getCallersOf(currentCallGraphNode);
                if (callers == null) {
                    // no callers
                    continue;
                }
                /*Iterator<OrderedPair<CallSiteProgramPoint, Context>>*/IntIterator callerIter = callers.intIterator();
                while (callerIter.hasNext()) {
                    ProgramPointReplica callerSite = g.lookupCallSiteReplicaDictionary(callerIter.next());
                    CallSiteProgramPoint cspp = (CallSiteProgramPoint) callerSite.getPP();
                    if (cspp.isClinit()) {
                        // The normal exit for a CLINIT is the call-site, add the successors instead
                        addSuccs(callerSite, q);
                        continue;
                    }
                    ProgramPointReplica callerSiteReplica = cspp.getNormalExit().getReplica(callerSite.getContext());
                    if (allVisited.add(callerSiteReplica)) {
                        q.add(callerSiteReplica);
                    }
                }
                continue;
            }
            // This is a normal program point add the successors
            addSuccs(current, q);
        } // The queue is empty

        finished = true;
        return Approximation.NONE;
    }

    /**
     * Add the successors of the given program point to the queue
     *
     * @param current current program point
     * @param q work queue containing program points to process
     */
    private void addSuccs(ProgramPointReplica current, WorkQueue<ProgramPointReplica> q) {
        Set<ProgramPoint> ppSuccs = current.getPP().succs();
        for (ProgramPoint succ : ppSuccs) {
            ProgramPointReplica succPPR = succ.getReplica(current.getContext());
            if (allVisited.add(succPPR)) {
                q.add(succPPR);
            }
        }
    }

    /**
     * Get the entry point to the interprocedural program point graph
     *
     * @return entry program point for the application
     */
    private ProgramPointReplica getEntryPP() {
        IMethod fakeRoot = g.getRegistrar().getEntryPoint();
        ProgramPoint entry = g.getRegistrar().getMethodSummary(fakeRoot).getEntryPP();
        return entry.getReplica(g.getHaf().initialContext());
    }

    /**
     * Whether we have searched the entire graph
     *
     * @return true if the entire graph has been searched
     */
    public boolean isFinished() {
        return this.finished;
    }

    /**
     * The statement in the given context is assumed to kill nothing
     *
     * @param stmt local-to-field points-to statement that may kill something
     * @param context context we are approximating the kill set for
     * @return true if the kill set should be approximated
     */
    public boolean isApproximateKillSet(PointsToStatement stmt, Context context) {
        return approxFieldAssignments.contains(new StmtAndContext(stmt, context));
    }

    /**
     * Approximation for call sites with no non-null receivers (approximated by assuming to terminate normally) or
     * field assignment with no receivers (approximating the kill set as empty)
     */
    public static final class Approximation {
        /**
         * No approximation
         */
        public static final Approximation NONE = new Approximation(-1, null);
        /**
         * Call site being approximated or -1 if this is a field assignment approximation
         */
        public final int callSite;
        /**
         * Field assignment being approximated or "null" if this is a call site approximation
         */
        public final OrderedPair<LocalToFieldStatement, Context> fieldAssign;

        /**
         * Approximation for call site or field assignment
         *
         * @param callSite Call site being approximated or -1 if this is a field assignment approximation
         * @param fieldAssign Field assignment being approximated or "null" if this is a call site approximation
         */
        private Approximation(int callSite, OrderedPair<LocalToFieldStatement, Context> fieldAssign) {
            this.callSite = callSite;
            this.fieldAssign = fieldAssign;
        }

        static Approximation getCallSiteApproximation(int callSite) {
            return new Approximation(callSite, null);
        }

        static Approximation getFieldAssignApproximation(OrderedPair<LocalToFieldStatement, Context> orderedPair) {
            return new Approximation(-1, orderedPair);
        }
    }
}
