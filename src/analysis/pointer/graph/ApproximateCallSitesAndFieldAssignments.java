package analysis.pointer.graph;

import java.util.Iterator;
import java.util.Set;

import util.OrderedPair;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.statements.PointsToStatement;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
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
     * Known empty call sites
     */
    private final/*Set<ProgramPointReplica>*/MutableIntSet emptyCallSites = AnalysisUtil.createConcurrentIntSet();
    /**
     * Known no-receiver field accesses
     */
    private final Set<StmtAndContext> emptyTargetFieldAssignments = AnalysisUtil.createConcurrentSet();

    /**
     * Points-to graph
     */
    private final PointsToGraph g;

    public void addEmptyCallSite(/*ProgramPointReplica*/int callSite) {
        this.emptyCallSites.add(callSite);
    }

    public void removeEmptyCallSite(/*ProgramPointReplica*/int callSite) {
        this.emptyCallSites.remove(callSite);
    }

    public void addEmptyTargetFieldAssignment(StmtAndContext sac) {
        this.emptyTargetFieldAssignments.add(sac);
    }

    public void removeEmptyTargetFieldAssignment(StmtAndContext sac) {
        this.emptyTargetFieldAssignments.remove(sac);
    }
    /**
     * Create a new instance of the algorithm for computing approximate call sites and data structures to store them
     *
     * @param g points-to graph
     */
    public ApproximateCallSitesAndFieldAssignments(PointsToGraph g) {
        this.g = g;
    }

    public boolean isApproximate(/*ProgramPointReplica*/int callSite) {
        return approxCallSites.contains(callSite);
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

    public int checkAndApproximateCallSites() {
        int count = 0;
        System.err.println("Check and approx call sites");
        while (!emptyCallSites.isEmpty()) {
            IntIterator iter = emptyCallSites.intIterator();
            while (iter.hasNext()) {
                int i = iter.next();
                if (emptyCallSites.remove(i)) {
                    // if call site i really has no targets, then approximate it.
                    if (g.getCalleesOf(i).isEmpty()) {
                        if (approxCallSites.add(i)) {
                            g.ppReach.addApproximateCallSite(i);
                            count++;
                            System.err.println(" added " + i);
                        }
                    }
                }
            }
        }
        return count;
    }

    public int checkAndApproximateFieldAssignments() {
        int count = 0;
        System.err.println("Check and approx field assignments: " + emptyTargetFieldAssignments.size());
        while (!emptyTargetFieldAssignments.isEmpty()) {
            Iterator<StmtAndContext> iter = emptyTargetFieldAssignments.iterator();
            while (iter.hasNext()) {
                StmtAndContext i = iter.next();
                iter.remove();
                // if field really has no targets, then approximate it.
                OrderedPair<Boolean, PointsToGraphNode> killed = i.stmt.killsNode(i.context, g);
                if (!killed.fst()) {
                    if (approxFieldAssignments.add(i)) {
                        g.ppReach.addApproximateFieldAssign(i);
                        System.err.println(" added " + i);
                        count++;
                    }
                }
            }
            System.err.println("  -- A: Check and approx field assignments: " + emptyTargetFieldAssignments.size()
                    + "  count is " + count + " iterator has next?" + emptyTargetFieldAssignments.iterator().hasNext());
        }
        System.err.println("  -- B: Check and approx field assignments: " + emptyTargetFieldAssignments.size()
                + "  count is " + count);
        return count;
    }
}
