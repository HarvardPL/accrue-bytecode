package analysis.pointer.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.LocalToLocalStatement;
import analysis.pointer.statements.PointsToStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Single-threaded implementation of a points-to graph solver. Given a set of constraints, {@link PointsToStatement}s,
 * compute the fixed point.
 */
public class PointsToAnalysisSingleThreaded extends PointsToAnalysis {

    /**
     * Map from points-to graph nodes to statements that depend on them. If the key changes then everything in the value
     * set should be recomputed.
     */
    private final Map<PointsToGraphNode, Set<StmtAndContext>> dependencies = new HashMap<>();

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

        LinkedList<OrderedPair<StmtAndContext, GraphDelta>> q = new LinkedList<>();

        // Add initial contexts
        for (PointsToStatement s : registrar.getAllStatements()) {
            for (Context c : g.getContexts(s.getMethod())) {
                q.addLast(new OrderedPair<StmtAndContext, GraphDelta>(new StmtAndContext(s, c), null));
            }
        }

        int numProcessed = 0;
        Set<StmtAndContext> visited = new HashSet<>();
        while (!q.isEmpty()) {
            // get the next sac, and the delta for it.
            OrderedPair<StmtAndContext, GraphDelta> next = q.removeFirst();
            StmtAndContext sac = next.fst();
            GraphDelta delta = next.snd();
            incrementCounter(sac);
            numProcessed++;
            if (outputLevel >= 1) {
                visited.add(sac);
            }


            processSaC(sac, delta, g, registrar, q, registerOnline);

            if (numProcessed % 100000 == 0) {
                System.err.println("PROCESSED: " + numProcessed
                                                + (outputLevel >= 1 ? " (" + visited.size() + " unique)" : "") + " in "
                                                + (System.currentTimeMillis() - startTime) / 1000 + "s");
                System.err.println("   current size of graph: " + g.getNodes().size() + " nodes");
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

    private void processSaC(StmtAndContext sac, GraphDelta delta, PointsToGraph g, StatementRegistrar registrar,
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

        // Read nodes are nodes that the current statement depends on
        for (PointsToGraphNode n : readNodes) {
            addDependency(n, sac);
        }

        if (outputLevel >= 4 && !changed.isEmpty()) {
            for (PointsToGraphNode n : changed.domain()) {
                System.err.println("\tCHANGED: " + n + "(now " + g.getPointsToSetNoDep(n) + ")");
                if (!getDependencies(n).isEmpty()) {
                    System.err.println("\tDEPS:");
                    for (StmtAndContext dep : getDependencies(n)) {
                        System.err.println("\t\t" + dep);
                    }
                }
            }
        }

        // Add dependencies to the queue
        for (PointsToGraphNode n : changed.domain()) {
            for (StmtAndContext depsac : getDependencies(n)) {
                queue.addLast(new OrderedPair<>(depsac, changed));
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
        if (i >= 5000) {
            for (StmtAndContext sac : iterations.keySet()) {
                int iter = iterations.get(sac);
                if (iter > 4990) {
                    String iterString = String.valueOf(iter);
                    if (iter < 1000) {
                        iterString = "0" + iterString;
                    }
                    if (iter < 100) {
                        iterString = "0" + iterString;
                    }
                    if (iter < 10) {
                        iterString = "0" + iterString;
                    }
                    System.err.println(iterString + ", " + sac);
                }
            }
            throw new RuntimeException("Analyzed the same statement and context " + i + " times: " + s);
        }
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
                    System.err.println("s returned null " + s.getClass() + " : " + s);
                }
                changed |= !d.isEmpty();
                if (!d.isEmpty()) {
                    if (s instanceof LocalToLocalStatement) {
                        System.err.println("uh oh Failed on " + s + " " + d);
                        failcount++;
                        if (failcount > 10) {
                            System.exit(1);
                        }
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
    private Set<StmtAndContext> getDependencies(PointsToGraphNode n) {
        Set<StmtAndContext> sacs = dependencies.get(n);
        if (sacs == null) {
            return Collections.emptySet();
        }
        return sacs;
    }

    /**
     * Add the (statement, context) pair as a dependency of the points-to graph node
     * 
     * @param n
     *            node the statement depends on
     * @param sac
     *            statement and context that depends on <code>n</code>
     * @return true if the dependency did not already exist
     */
    private boolean addDependency(PointsToGraphNode n, StmtAndContext sac) {
        Set<StmtAndContext> s = dependencies.get(n);
        if (s == null) {
            s = new LinkedHashSet<>();
            dependencies.put(n, s);
        }
        return s.add(sac);
    }
}
