package analysis.pointer.graph;

import java.util.Set;

import util.OrderedPair;
import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * Call sites with no receiver that will be approximated by assuming that the call can terminate normally. This allows
 * program point reachability queries to succeed even in the presence of these calls. This may be imprecise if a call
 * site actually has no targets, but there are some instances (e.g. reflection) where unsoundness in the analysis causes
 * there to be no callee targets.
 */
public class ApproximateCallSites {

    /**
     * All no-target call sites that have been approximated so far
     */
    private final/*Set<ProgramPointReplica>*/MutableIntSet approxCallSites = AnalysisUtil.createConcurrentIntSet();
    /**
     * All program point replicas that have been visited by this algorithm
     */
    private Set<ProgramPointReplica> allVisited = AnalysisUtil.createConcurrentSet();
    /**
     * The last program point replica to be visited (-1 if no pp has been visited yet)
     */
    private/*ProgramPointReplica*/int lastVisited = -1;
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
    public ApproximateCallSites(PointsToGraph g) {
        this.g = g;
    }

    private boolean recordApproximateCallSite(/*ProgramPointReplica*/int callSite) {
        return approxCallSites.add(callSite);
    }

    public boolean isApproximate(/*ProgramPointReplica*/int callSite) {
        return approxCallSites.contains(callSite);
    }

    /**
     * Find the next call-site-program-point replica that must be approximated
     *
     * @return the next call-site to be processed
     */
    public/*ProgramPointReplica*/int findNextApproximateCallSite() {
        // XXX Reset the visited set and start from the beginning
        allVisited = AnalysisUtil.createConcurrentSet();
        lastVisited = -1;

        ProgramPointReplica first;
        if (lastVisited == -1) {
            first = getEntryPP();
        }
        else {
            first = g.lookupCallSiteReplicaDictionary(lastVisited);
        }
        WorkQueue<ProgramPointReplica> q = new WorkQueue<>();
        allVisited.add(first);
        q.add(first);

        while (!q.isEmpty()) {
            ProgramPointReplica current = q.poll();
            ProgramPoint pp = current.getPP();

            if (pp instanceof CallSiteProgramPoint) {
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

                int currentInt = g.lookupCallSiteReplicaDictionary(current);
                if (!isApproximate(currentInt)) {
                    /*Set<OrderedPair<IMethod,Context>>*/IntSet callees = g.getCalleesOf(currentInt);
                    if (callees.isEmpty()) {
                        // 1. This is a call-site
                        // 2. It has not yet been approximated
                        // 3. It has no callees
                        // This means it is the next approximated call-site
                        recordApproximateCallSite(currentInt);
                        lastVisited = currentInt;
                        System.err.println("APPROXIMATING " + PrettyPrinter.methodString(cspp.getCallee()) + " from "
                                + PrettyPrinter.methodString(cspp.getCaller()) + " in " + current.getContext());
                        return currentInt;
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
        return -1;
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
}
