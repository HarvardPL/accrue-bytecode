package analysis.pointer.graph;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import util.OrderedPair;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.statements.PointsToStatement;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

/**
 * Call sites with no non-null receiver that will be approximated by assuming that the call can terminate normally or
 * field assignments with no receiver that are approximated by assuming they have an empty kill set. This allows program
 * point reachability queries to succeed even in the presence of these calls or assignments. This may be imprecise if a
 * call site or field access actually has no targets, but there are some instances (e.g. reflection) where unsoundness
 * in the analysis causes this issue.
 */
public class ApproximateCallSitesAndFieldAssignments {

    /**
     * Do we be slower but deterministic? Both are sound, and moreover the faster (non-deterministic) is more precise.
     */
    private static final boolean DETERMINISTIC = false;
    /**
     * All no-target call sites that have been approximated so far
     */
    private final/*Set<ProgramPointReplica>*/MutableIntSet approxCallSites = AnalysisUtil.createConcurrentIntSet();
    /**
     * All no-receiver field accesses that have been approximated so far
     */
    private final Set<StmtAndContext> approxFieldAssignments = AnalysisUtil.createConcurrentSet();

    /**
     * Possible empty call sites
     */
    private final AtomicReference</*Set<ProgramPointReplica>*/MutableIntSet> emptyCallSites = new AtomicReference<>(AnalysisUtil.createConcurrentIntSet());

    /**
     * Possible no-receiver field accesses
     */
    private final AtomicReference<Set<StmtAndContext>> emptyTargetFieldAssignments = new AtomicReference<>(AnalysisUtil.<StmtAndContext> createConcurrentSet());

    /**
     * Points-to graph
     */
    private final PointsToGraph g;

    public void addEmptyCallSite(/*ProgramPointReplica*/int callSite) {
        this.emptyCallSites.get().add(callSite);
    }

    public void addEmptyTargetFieldAssignment(StmtAndContext sac) {
        this.emptyTargetFieldAssignments.get().add(sac);
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

    public int checkAndApproximateCallSitesAndFieldAssignments() {
        int count = 0;

        MutableSparseIntSet newApproxCallSites = DETERMINISTIC ? MutableSparseIntSet.makeEmpty() : null;
        Set<StmtAndContext> newApproxFieldAssignments = DETERMINISTIC ? new LinkedHashSet<StmtAndContext>() : null;
        {
            // first, call sites
            MutableIntSet s, newSet;
            do {
                newSet = AnalysisUtil.createConcurrentIntSet();
                s = this.emptyCallSites.get();
            } while (!this.emptyCallSites.compareAndSet(s, newSet));

            IntIterator iter = s.intIterator();
            while (iter.hasNext()) {
                int i = iter.next();
                // if call site i really has no targets, then approximate it.
                if (g.getCalleesOf(i).isEmpty()) {
                    if (approxCallSites.add(i)) {
                        if (DETERMINISTIC) {
                            newApproxCallSites.add(i);
                        }
                        else {
                            g.ppReach.addApproximateCallSite(i);
                        }
                        count++;
                    }
                }
            }
        }

        {
            // second, field assignments

            Set<StmtAndContext> s, newSet;
            do {
                newSet = AnalysisUtil.createConcurrentSet();
                s = this.emptyTargetFieldAssignments.get();
            } while (!this.emptyTargetFieldAssignments.compareAndSet(s, newSet));

            // s is now the old set, and we installed a new empty set.

            Iterator<StmtAndContext> iter = s.iterator();
            while (iter.hasNext()) {
                StmtAndContext i = iter.next();
                // if field really has no targets, then approximate it.
                OrderedPair<Boolean, PointsToGraphNode> killed = i.stmt.killsNode(i.context, g);
                if (!killed.fst()) {
                    if (approxFieldAssignments.add(i)) {
                        if (DETERMINISTIC) {
                            newApproxFieldAssignments.add(i);
                        }
                        else {
                            g.ppReach.addApproximateFieldAssign(i);
                        }
                        count++;
                    }
                }
            }
        }

        if (DETERMINISTIC) {
            IntIterator iter = newApproxCallSites.intIterator();
            while (iter.hasNext()) {
                g.ppReach.addApproximateCallSite(iter.next());
            }
            for (StmtAndContext i : newApproxFieldAssignments) {
                g.ppReach.addApproximateFieldAssign(i);
            }
        }
        return count;
    }
}
