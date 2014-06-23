package analysis.pointer.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.TypeFilter;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.PointsToStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Single-threaded implementation of a points-to graph solver. Given a set of constraints, {@link PointsToStatement}s,
 * compute the fixed point.
 */
public class PointsToAnalysisSingleThreaded extends PointsToAnalysis {

    /**
     * An interesting dependency from node n to StmtAndContext sac exists when a modification to the pointstoset of n
     * (i.e., if n changes to point to more things) requires reevaluation of sac. Many dependencies are just copy
     * dependencies (which are not interesting dependencies).
     */
    private Map<PointsToGraphNode, Set<StmtAndContext>> interestingDepedencies = new HashMap<>();

    /**
     * A copy dependency from node n to node m exists when a modification to the pointstoset of n (i.e., if n changes to
     * point to more things) copies those changes to the points to set of m. That is, the pointsto set of m is a
     * superset of the points to set of n. For efficiency, we don't reevaluate the StmtAndCtxt that created the copy
     * dependency, we just copy the new elements directly. We also track the StmtAndContext that added the copy
     * dependency. (We do not use this at the moment, and it may not be complete, i.e., more than one StmtAndContext may
     * indicate a copy dependency).
     */
    private Map<PointsToGraphNode, Map<PointsToGraphNode, Set<OrderedPair<TypeFilter, StmtAndContext>>>> copyDepedencies = new HashMap<>();

    /**
     * Counters to detect infinite loops
     */
    private final Map<StmtAndContext, Integer> iterations = new HashMap<>();

    /**
     * If true then a debug pass will be run after the analysis reaches a fixed point
     */
    public static boolean DEBUG_SOLVED = false;

    /**
     * New pointer analysis engine
     * 
     * @param haf
     *            Abstraction factory for this points-to analysis
     */
    public PointsToAnalysisSingleThreaded(HeapAbstractionFactory haf) {
        super(haf);
    }

    @Override
    public PointsToGraph solve(StatementRegistrar registrar) {
        return solveSmarter(registrar, false);
    }

    public PointsToGraph solveAndRegister(StatementRegistrar onlineRegistrar) {
        onlineRegistrar.registerMethod(AnalysisUtil.getFakeRoot());
        return solveSmarter(onlineRegistrar, true);
    }

    /**
     * Slow naive implementation suitable for testing
     * 
     * @param registrar
     *            Point-to statement registrar
     * 
     * @return Points-to graph
     */
    @Deprecated
    public PointsToGraph solveSimple(StatementRegistrar registrar) {
        PointsToGraph g = new PointsToGraph(registrar, haf);

        boolean changed = true;
        int count = 0;
        while (changed) {
            changed = processAllStatements(g, registrar);
            count++;
            if (count % 100 == 0) {
                break;
            }
        }
        System.err.println(count + " iterations ");
        return g;
    }

    /**
     * Generate a points-to graph by tracking dependencies and only analyzing statements that are reachable from the
     * entry point
     * 
     * @param registrar
     *            points-to statement registrar
     * @param registerOnline
     *            Whether to generate points-to statements during the points-to analysis, otherwise the registrar will
     *            already be populated
     * @return Points-to graph
     */
    public PointsToGraph solveSmarter(StatementRegistrar registrar, boolean registerOnline) {
        PointsToGraph g = new PointsToGraph(registrar, haf);
        System.err.println("Starting points to engine using " + haf);
        long startTime = System.currentTimeMillis();

        Random rnd = new Random(1636);
        LinkedList<OrderedPair<StmtAndContext, GraphDelta>> queue = new LinkedList<>();

        // Add initial contexts
        for (PointsToStatement s : registrar.getAllStatements()) {
            for (Context c : g.getContexts(s.getMethod())) {
                queue.addLast(new OrderedPair<StmtAndContext, GraphDelta>(new StmtAndContext(s, c), null));
            }
        }

        int front = 0;
        int back = 0;
        int numProcessed = 0;
        Set<StmtAndContext> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            // get the next sac, and the delta for it.
            OrderedPair<StmtAndContext, GraphDelta> next;
            if (queue.size() < 10000 && rnd.nextInt(10000) == 0) {
                next = queue.removeFirst();
                front++;
            }
            else {
                next = queue.removeLast();
                back++;
            }
            StmtAndContext sac = next.fst();
            GraphDelta delta = next.snd();
            incrementCounter(sac);
            numProcessed++;
            if (outputLevel >= 1) {
                visited.add(sac);
            }


            GraphDelta changed = processSaC(sac, delta, g, registrar, queue, registerOnline);

            handleChanges(queue, changed, g);

            if (numProcessed % 100000 == 0) {
                System.err.println("PROCESSED: " + numProcessed
                                                + (outputLevel >= 1 ? " (" + visited.size() + " unique)" : "") + " in "
                                                + (System.currentTimeMillis() - startTime) / 1000 + "s;  graph is "
                                                + g.getNodes().size() + " nodes; queue is " + queue.size()
                                                + "; front / back = " + front + " / " + back + " = "
                                                + ((float) front / back));
                front = back = 0;
            }
        }

        long endTime = System.currentTimeMillis();
        System.err.println("Processed " + numProcessed + " (statement, context) pairs"
                                        + (outputLevel >= 1 ? " (" + visited.size() + " unique)" : "") + ". It took "
                                        + (endTime - startTime) + "ms.");

        processAllStatements(g, registrar);
        if (outputLevel >= 5) {
            System.err.println("****************************** CHECKING ******************************");
            PointsToGraph.DEBUG = true;
            DEBUG_SOLVED = true;
            processAllStatements(g, registrar);
        }
        return g;
    }

    private GraphDelta processSaC(StmtAndContext sac, GraphDelta delta, PointsToGraph g, StatementRegistrar registrar,
                                    LinkedList<OrderedPair<StmtAndContext, GraphDelta>> queue, boolean registerOnline) {
        PointsToStatement s = sac.stmt;
        Context c = sac.context;

        if (outputLevel >= 3) {
            System.err.println("\tPROCESSING: " + sac);
        }
        GraphDelta changed = s.process(c, haf, g, delta, registrar);


        // Get the changes from the graph
        Map<IMethod, Set<Context>> newContexts = g.getAndClearNewContexts();
        Set<PointsToGraphNode> readNodes = g.getAndClearReadNodes();
        Map<PointsToGraphNode, Set<OrderedPair<PointsToGraphNode, TypeFilter>>> copies = g.getAndClearCopies();

        // Add new contexts
        for (IMethod m : newContexts.keySet()) {
            if (outputLevel >= 4) {
                System.err.println("\tNEW CONTEXTS for " + PrettyPrinter.methodString(m));
                for (Context context : newContexts.get(m)) {
                    System.err.println("\t" + context);
                }
            }

            if (registerOnline) {
                // Add statements for the given method to the registrar
                registrar.registerMethod(m);
            }

            for (PointsToStatement stmt : registrar.getStatementsForMethod(m)) {
                if (outputLevel >= 4) {
                    System.err.println("\t\tADDING " + stmt);
                }

                for (Context context : newContexts.get(m)) {
                    // these are new contexts, so use null for the delta
                    queue.addLast(new OrderedPair<StmtAndContext, GraphDelta>(new StmtAndContext(stmt, context), null));
                }
            }
        }

        if (outputLevel >= 4 && !readNodes.isEmpty()) {
            System.err.println("\tREAD:");
            for (PointsToGraphNode read : readNodes) {
                System.err.println("\t\t" + read);
            }
        }

        // Now add the dependencies.

        // Read nodes are nodes that the current statement depends on
        for (PointsToGraphNode n : readNodes) {
            addInterestingDependency(n, sac);
        }
        // Read nodes are nodes that the current statement depends on
        for (PointsToGraphNode n : copies.keySet()) {
            addCopyDependency(n, copies.get(n), sac);
        }

        if (outputLevel >= 4 && !changed.isEmpty()) {
            for (PointsToGraphNode n : changed.domain()) {
                System.err.println("\tCHANGED: " + n + "(now " + g.getPointsToSet(n) + ")");
                g.getAndClearReadNodes();// clear the read nodes so the read above wasn't significant.
                if (!getInterestingDependencies(n).isEmpty()) {
                    System.err.println("\tDEPS:");
                    for (StmtAndContext dep : getInterestingDependencies(n)) {
                        System.err.println("\t\t" + dep);
                    }
                }
            }
        }
        return changed;
    }

    private void handleChanges(LinkedList<OrderedPair<StmtAndContext, GraphDelta>> queue, GraphDelta initialChanges,
                                    PointsToGraph g) {
        // handleChangesMassiveDelta(queue, initialChanges, g);
        handleChangesSmallDeltas(queue, initialChanges, g);
    }

    @SuppressWarnings("unused")
    private void handleChangesMassiveDelta(LinkedList<OrderedPair<StmtAndContext, GraphDelta>> queue,
                                    GraphDelta initialChanges, PointsToGraph g) {
        // First, we will handle the copy dependencies, and build up one massive delta to use for all of the interesting
        // dependencies.
        GraphDelta massiveDelta = new GraphDelta();

        // Do a work queue to handle all the copy dependencies.
        ArrayList<GraphDelta> changesQ = new ArrayList<>();
        changesQ.add(initialChanges);

        while (!changesQ.isEmpty()) {
            GraphDelta changes = changesQ.remove(changesQ.size() - 1);

            // Copy dependencies...
            for (PointsToGraphNode src : changes.domain()) {
                Map<PointsToGraphNode, Set<OrderedPair<TypeFilter, StmtAndContext>>> m = this.copyDepedencies.get(src);
                if (m != null) {
                    for (PointsToGraphNode trg : m.keySet()) {
                        for (OrderedPair<TypeFilter, StmtAndContext> p : m.get(trg)) {
                            TypeFilter filter = p.fst();
                            GraphDelta newChanges;
                            if (filter == null) {
                                newChanges = g.copyEdgesWithDelta(src, trg, changes);
                            }
                            else {
                                newChanges = g.copyFilteredEdgesWithDelta(src, filter, trg, changes);
                            }
                            if (!newChanges.isEmpty()) {
                                massiveDelta = massiveDelta.combine(newChanges);
                                changesQ.add(newChanges);
                            }
                        }
                    }
                }
            }
        }
        // combine the initial changes with the massive delta
        massiveDelta = massiveDelta.combine(initialChanges);

        // Now we handle all the interesting dependencies, using the massive delta
        for (PointsToGraphNode n : massiveDelta.domain()) {
            for (StmtAndContext depsac : getInterestingDependencies(n)) {
                queue.addLast(new OrderedPair<>(depsac, massiveDelta));
            }
        }
    }

    private void handleChangesSmallDeltas(LinkedList<OrderedPair<StmtAndContext, GraphDelta>> queue,
                                    GraphDelta initialChanges, PointsToGraph g) {
        // In this approach, we will create many small deltas, one for each copy.

        // Do a work queue to handle all the copy dependencies.
        ArrayList<GraphDelta> changesQ = new ArrayList<>();
        changesQ.add(initialChanges);

        while (!changesQ.isEmpty()) {
            GraphDelta changes = changesQ.remove(changesQ.size() - 1);

            // Find the interesting dependencies for changes.
            for (PointsToGraphNode n : changes.domain()) {
                for (StmtAndContext depsac : getInterestingDependencies(n)) {
                    queue.addLast(new OrderedPair<>(depsac, changes));
                }
            }

            // Now copy the changes, using copy dependencies...
            for (PointsToGraphNode src : changes.domain()) {
                Map<PointsToGraphNode, Set<OrderedPair<TypeFilter, StmtAndContext>>> m = this.copyDepedencies.get(src);
                if (m != null) {
                    for (PointsToGraphNode trg : m.keySet()) {
                        for (OrderedPair<TypeFilter, StmtAndContext> p : m.get(trg)) {
                            TypeFilter filter = p.fst();
                            GraphDelta newChanges;
                            if (filter == null) {
                                newChanges = g.copyEdgesWithDelta(src, trg, changes);
                            }
                            else {
                                newChanges = g.copyFilteredEdgesWithDelta(src, filter, trg, changes);
                            }
                            if (!newChanges.isEmpty()) {
                                changesQ.add(newChanges);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Increment the counter giving the number of times the given node has been analyzed
     * 
     * @param n
     *            node to increment for
     * @return incremented counter
     */
    private int incrementCounter(StmtAndContext s) {
        Integer i = iterations.get(s);
        if (i == null) {
            i = 0;
        }
        i++;
        iterations.put(s, i);
        return i;
    }

    /**
     * Loop through and process all the points-to statements in the registrar.
     * 
     * @param g
     *            points-to graph (may be modified)
     * @param registrar
     *            points-to statement registrar
     * @return true if the points-to graph changed
     */
    private boolean processAllStatements(PointsToGraph g, StatementRegistrar registrar) {
        boolean changed = false;
        System.err.println("Processing all statements for good luck: " + registrar.getAllStatements().size());
        int failcount = 0;
        for (PointsToStatement s : registrar.getAllStatements()) {
            for (Context c : g.getContexts(s.getMethod())) {
                GraphDelta d = s.process(c, haf, g, null, registrar);
                if (d == null) {
                    throw new RuntimeException("s returned null " + s.getClass() + " : " + s);
                }
                changed |= !d.isEmpty();
                if (!d.isEmpty()) {

                    System.err.println("uhoh Failed on " + s + "\n    Delta is " + d);
                    failcount++;
                    if (failcount > 10) {
                        System.err.println("\nThere may be more failures, but exiting now...");
                        System.exit(1);
                    }
                }
            }
        }
        return changed;
    }

    /**
     * Get any (statement,context) pairs that depend on the given points-to graph node
     * 
     * @param n
     *            node to get the dependencies for
     * @return set of dependencies
     */
    private Set<StmtAndContext> getInterestingDependencies(PointsToGraphNode n) {
        Set<StmtAndContext> sacs = interestingDepedencies.get(n);
        if (sacs == null) {
            return Collections.emptySet();
        }
        return sacs;
    }

    /**
     * Add the (statement, context) pair as a dependency of the points-to graph node. This is an
     * "interesting dependency", meaning that if the points to set of n is modified, then sac will need to be processed
     * again.
     * 
     * @param n
     *            node the statement depends on
     * @param sac
     *            statement and context that depends on <code>n</code>
     * @return true if the dependency did not already exist
     */
    private boolean addInterestingDependency(PointsToGraphNode n, StmtAndContext sac) {
        Set<StmtAndContext> s = interestingDepedencies.get(n);
        if (s == null) {
            s = new LinkedHashSet<>();
            interestingDepedencies.put(n, s);
        }
        return s.add(sac);
    }

    /**
     * Add the attached copy dependencies. This means that if the points to set of source is modified, then targets may
     * need to be updated. For any node t in targets, the points to set of t is a (possibly filtered) superset of the
     * points to set of source.
     * 
     */

    private boolean addCopyDependency(PointsToGraphNode source,
                                    Set<OrderedPair<PointsToGraphNode, TypeFilter>> targets,
                                    StmtAndContext sac) {
        boolean changed = false;
        Map<PointsToGraphNode, Set<OrderedPair<TypeFilter, StmtAndContext>>> m = copyDepedencies.get(source);
        if (m == null) {
            m = new LinkedHashMap<>();
            copyDepedencies.put(source, m);
        }
        for (OrderedPair<PointsToGraphNode, TypeFilter> trg : targets) {
            Set<OrderedPair<TypeFilter, StmtAndContext>> s = m.get(trg.fst());
            if (s == null) {
                s = new LinkedHashSet<>();
                m.put(trg.fst(), s);
            }
            changed |= s.add(new OrderedPair<>(trg.snd(), sac));
        }

        return changed;
    }

}
