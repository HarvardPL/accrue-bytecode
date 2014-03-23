package pointer.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.PointsToGraph;
import pointer.graph.PointsToGraphNode;
import pointer.statements.PointsToStatement;
import pointer.statements.StatementRegistrar;
import util.WorkQueue;
import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Single-threaded implementation of a points-to graph solver. Given a set of
 * constraints, {@link PointsToStatement}s, compute the fixed point.
 */
public class PointsToAnalysisSingleThreaded extends PointsToAnalysis {

    /**
     * Map from points-to graph nodes to statements that depend on them. If the
     * key changes then everything in the value set should be recomputed.
     */
    private final Map<PointsToGraphNode, Set<StmtAndContext>> dependencies = new HashMap<>();

    /**
     * New pointer analysis engine
     * 
     * @param haf
     *            Abstraction factory for this points-to analysis
     * @param util
     *            WALA utility classes
     */
    public PointsToAnalysisSingleThreaded(HeapAbstractionFactory haf, AnalysisUtil util) {
        super(haf, util);
    }

    @Override
    public PointsToGraph solve(StatementRegistrar registrar) {
        return solveSmarter(registrar);
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
        PointsToGraph g = new PointsToGraph(util, registrar, haf);

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
     * Generate a points-to graph by tracking dependencies and only analyzing
     * statements that are reachable from the entry point
     * 
     * @param registrar
     *            points-to statement registrar
     * @return Points-to graph
     */
    public PointsToGraph solveSmarter(StatementRegistrar registrar) {
        PointsToGraph g = new PointsToGraph(util, registrar, haf);
        System.err.println("Starting points to engine using " + haf);
        long startTime = System.currentTimeMillis();

        WorkQueue<StmtAndContext> q = new WorkQueue<>();

        // Add initial contexts
        // TODO Just add class inits that are needed
        // maybe keep a list of class inits for each statement and add them as
        // they come up?
        for (PointsToStatement s : registrar.getAllStatements()) {
            for (Context c : g.getContexts(s.getCode().getMethod())) {
                q.add(new StmtAndContext(s, c));
            }
        }

        int count = 0;
        while (!q.isEmpty()) {
            StmtAndContext sac = q.poll();
            count++;
            PointsToStatement s = sac.stmt;
            Context c = sac.context;
            s.process(c, haf, g, registrar);

            // Get the changes from the graph
            Map<IMethod, Set<Context>> newContexts = g.getAndClearNewContexts();
            Set<PointsToGraphNode> changedNodes = g.getAndClearChangedNodes();
            Set<PointsToGraphNode> readNodes = g.getAndClearReadNodes();

            // Add new contexts
            for (IMethod m : newContexts.keySet()) {
                for (PointsToStatement stmt : registrar.getStatementsForMethod(m)) {
                    for (Context context : newContexts.get(m)) {
                        q.add(new StmtAndContext(stmt, context));
                    }
                }
            }

            // Read nodes are nodes that the current statement depends on
            for (PointsToGraphNode n : readNodes) {
                addDependency(n, sac);
            }

            // Add dependencies to the queue
            for (PointsToGraphNode n : changedNodes) {
                q.addAll(getDependencies(n));
            }
        }
        
        // Run through one more time to check results
        boolean changed = processAllStatements(g, registrar);
        if (changed) {
            System.err.println("Something wrong in points-to analysis.");
        }
        
        long endTime = System.currentTimeMillis();
        System.err.println("Processed " + count + " (statement, context) pairs. It took " + (endTime - startTime)
                + "ms.");

        return g;
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
        for (PointsToStatement s : registrar.getAllStatements()) {
            for (Context c : g.getContexts(s.getCode().getMethod())) {
                changed |= s.process(c, haf, g, registrar);
            }
        }
        return changed;
    }

    /**
     * Get any (statement,context) pairs that depend on the given points-to
     * graph node
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
     * Add the (statement, context) pair as a dependency of the points-to graph
     * node
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
            s = new HashSet<>();
            dependencies.put(n, s);
        }
        return s.add(sac);
    }
}
