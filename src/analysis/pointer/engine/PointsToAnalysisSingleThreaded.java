package analysis.pointer.engine;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
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
    private Map<PointsToGraphNode, Set<StmtAndContext>> interestingDepedencies =
            new HashMap<>();

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

    int numProcessed = 0;

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
    public PointsToGraph solveSmarter(StatementRegistrar registrar,
            boolean registerOnline) {
        PointsToGraph g = new PointsToGraph(registrar, haf);
        System.err.println("Starting points to engine using " + haf);
        long startTime = System.currentTimeMillis();

        // !@!XXX
        // Cycle detection
        // Better cache management

        /*
         * We use a least recently fired (LRF) comparator. 
         * See Some directed graph algorithms and their application to pointer analysis. 
         * David J. Pearce. Ph.D. Thesis, Imperial College of Science, Technology and Medicine, University of London, February 2005.
         */
        /*        Comparator<OrderedPair<StmtAndContext, GraphDelta>> lrfComparator =
                        new Comparator<OrderedPair<StmtAndContext, GraphDelta>>() {

                            @Override
                            public int compare(
                                    OrderedPair<StmtAndContext, GraphDelta> o1,
                                    OrderedPair<StmtAndContext, GraphDelta> o2) {
                                // return a negative number if o1 is "less than" or more important than o2
                                Integer n1 = lastFired.get(o1.fst());
                                Integer n2 = lastFired.get(o2.fst());
                                if (n1 == null && n2 == null) {
                                    // neither has been fired.
                                    return 0;
                                }
                                if (n1 == null) {
                                    // n1 hasn't been fired, but n2 has
                                    return -1;
                                }
                                if (n2 == null) {
                                    // n2 hasn't been fired, but n1 has
                                    return 1;
                                }
                                // both n1 and n2 have been fired.
                                return n1 < n2 ? -1 : n1 == n2 ? 0 : 1;
                            }

                        };*/

        Queue<OrderedPair<StmtAndContext, GraphDelta>> queue =
                Collections.asLifoQueue(new ArrayDeque<OrderedPair<StmtAndContext, GraphDelta>>());
        Queue<OrderedPair<StmtAndContext, GraphDelta>> nextQueue =
                Collections.asLifoQueue(new ArrayDeque<OrderedPair<StmtAndContext, GraphDelta>>());
//        Queue<OrderedPair<StmtAndContext, GraphDelta>> queue =
//                new PriorityQueue<>(10000, lrfComparator);
//        Queue<OrderedPair<StmtAndContext, GraphDelta>> queue =
//                new CustomQueue();

        // Add initial contexts
        for (IMethod m : registrar.getInitialContextMethods()) {
            for (PointsToStatement s : registrar.getStatementsForMethod(m)) {
                for (Context c : g.getContexts(s.getMethod())) {
                    queue.add(new OrderedPair<StmtAndContext, GraphDelta>(new StmtAndContext(s,
                                                                                             c),
                                                                          null));
                }
            }
        }

        long nextMilestone = startTime;
        long lastNumProcessed = 0;
        long lastTime = startTime;
        long noDeltaProcessed = 0;
        long processedWithNoChange = 0;
        Set<StmtAndContext> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            // get the next sac, and the delta for it.
            OrderedPair<StmtAndContext, GraphDelta> next = queue.poll();

            StmtAndContext sac = next.fst();
            GraphDelta delta = next.snd();
            incrementCounter(sac);

            numProcessed++;
            if (delta == null) {
                noDeltaProcessed++;
            }
            if (outputLevel >= 1) {
                visited.add(sac);
            }

            GraphDelta changed =
                    processSaC(sac,
                               delta,
                               g,
                               registrar,
                               nextQueue,
                               registerOnline);

            if (changed.isEmpty()) {
                processedWithNoChange++;
            }
            handleChanges(nextQueue, changed, g);

            long currTime = System.currentTimeMillis();
            if (currTime > nextMilestone) {
                do {
                    nextMilestone = nextMilestone + 1000 * 30; // 30 seconds 
                } while (currTime > nextMilestone);

                System.err.println("PROCESSED: "
                        + numProcessed
                        + (outputLevel >= 1 ? " (" + visited.size()
                                + " unique)" : "") + " "
                        + (currTime - startTime) / 1000 + "s;  graph="
                        + g.getBaseNodes().size()
                        + " base nodes; cycles removed "
                        + g.cycleRemovalCount() + " nodes ; queue="
                        + queue.size() + " nextQueue=" + nextQueue.size()
                        + " Processed: nochange, nodelta: "
                        + processedWithNoChange + ",  " + noDeltaProcessed
                        + " (" + (numProcessed - lastNumProcessed) + " in "
                        + (currTime - lastTime) / 1000 + "s)");
                lastTime = currTime;
                lastNumProcessed = numProcessed;
                noDeltaProcessed = 0;
                processedWithNoChange = 0;
            }
            if (queue.isEmpty()) {
                Queue<OrderedPair<StmtAndContext, GraphDelta>> t = queue;
                queue = nextQueue;
                nextQueue = t;
            }
        }

        long endTime = System.currentTimeMillis();
        System.err.println("Processed " + numProcessed
                + " (statement, context) pairs"
                + (outputLevel >= 1 ? " (" + visited.size() + " unique)" : "")
                + ". It took " + (endTime - startTime) + "ms.");

        processAllStatements(g, registrar);
        if (outputLevel >= 5) {
            System.err.println("****************************** CHECKING ******************************");
            PointsToGraph.DEBUG = true;
            DEBUG_SOLVED = true;
            processAllStatements(g, registrar);
        }
        return g;
    }

    private GraphDelta processSaC(StmtAndContext sac, GraphDelta delta,
            PointsToGraph g, StatementRegistrar registrar,
            Queue<OrderedPair<StmtAndContext, GraphDelta>> queue,
            boolean registerOnline) {
        PointsToStatement s = sac.stmt;
        Context c = sac.context;

        if (outputLevel >= 3) {
            System.err.println("\tPROCESSING: " + sac);
        }
        GraphDelta changed = s.process(c, haf, g, delta, registrar);

        // Get the changes from the graph
        Map<IMethod, Set<Context>> newContexts = g.getAndClearNewContexts();
        Set<PointsToGraphNode> readNodes = g.getAndClearReadNodes();
        Set<PointsToGraphNode> newlyCombinedNodes =
                g.getAndClearNewlyCombinedNodes();

        // Add new contexts
        for (IMethod m : newContexts.keySet()) {
            if (outputLevel >= 4) {
                System.err.println("\tNEW CONTEXTS for "
                        + PrettyPrinter.methodString(m));
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
                    queue.add(new OrderedPair<StmtAndContext, GraphDelta>(new StmtAndContext(stmt,
                                                                                             context),
                                                                          null));
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

        // now update the interesting dependecies.
        for (PointsToGraphNode n : newlyCombinedNodes) {
            Set<StmtAndContext> deps = interestingDepedencies.remove(n);
            if (deps != null) {
                for (StmtAndContext depSac : deps) {
                    addInterestingDependency(g.getRepresentative(n), depSac);
                }
            }
        }

        if (outputLevel >= 4 && !changed.isEmpty()) {
            // for (PointsToGraphNode n : changed.domain()) {
            // System.err.println("\tCHANGED: " + n + "(now " + g.getPointsToSet(n) + ")");
            // g.getAndClearReadNodes();// clear the read nodes so the read above wasn't significant.
            // if (!getInterestingDependencies(n).isEmpty()) {
            // System.err.println("\tDEPS:");
            // for (StmtAndContext dep : getInterestingDependencies(n)) {
            // System.err.println("\t\t" + dep);
            // }
            // }
            // }
        }
        return changed;
    }

    private void handleChanges(
            Queue<OrderedPair<StmtAndContext, GraphDelta>> queue,
            GraphDelta changes, PointsToGraph g) {
        // handleChangesMassiveDelta(queue, initialChanges, g);
        //handleChangesSmallDeltas(queue, initialChanges, g);
        Iterator<PointsToGraphNode> iter = changes.domainIterator();
        while (iter.hasNext()) {
            PointsToGraphNode n = iter.next();
            for (StmtAndContext depsac : getInterestingDependencies(n)) {
                queue.add(new OrderedPair<>(depsac, changes));
            }
        }

    }

    /*

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
            // combine the initial changes with the massive delta
            massiveDelta = massiveDelta.combine(initialChanges);

            // Now we handle all the interesting dependencies, using the massive delta
            for (PointsToGraphNode n : massiveDelta.domain()) {
                for (StmtAndContext depsac : getInterestingDependencies(n)) {
                    queue.addLast(new OrderedPair<>(depsac, massiveDelta));
                }
            }
        }

        private void handleChangesSmallDeltas(
                LinkedList<OrderedPair<StmtAndContext, GraphDelta>> queue,
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
                    Map<PointsToGraphNode, Set<OrderedPair<TypeFilter, StmtAndContext>>> m =
                            copyDepedencies.get(src);
                    if (m != null) {
                        for (PointsToGraphNode trg : m.keySet()) {
                            for (OrderedPair<TypeFilter, StmtAndContext> p : m.get(trg)) {
                                TypeFilter filter = p.fst();
                                GraphDelta newChanges;
                                if (filter == null) {
                                    newChanges =
                                            g.copyEdgesWithDelta(src, trg, changes);
                                }
                                else {
                                    newChanges =
                                            g.copyFilteredEdgesWithDelta(src,
                                                                         filter,
                                                                         trg,
                                                                         changes);
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
    */
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
        if (i >= 10000) {
            for (StmtAndContext sac : iterations.keySet()) {
                int iter = iterations.get(sac);
                String iterString = String.valueOf(iter);
                if (iter < 100) {
                    iterString = "0" + iterString;
                }
                if (iter < 10) {
                    iterString = "0" + iterString;
                }
                if (iter > 50) {
                    System.err.println(iterString + ", " + sac);
                }
            }
            throw new RuntimeException("Analyzed the same statement and context "
                    + i + " times: " + s);
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
    private boolean processAllStatements(PointsToGraph g,
            StatementRegistrar registrar) {
        boolean changed = false;
        System.err.println("Processing all statements for good luck: "
                + registrar.size());
        int failcount = 0;
        for (IMethod m : registrar.getRegisteredMethods()) {
            for (PointsToStatement s : registrar.getStatementsForMethod(m)) {
                for (Context c : g.getContexts(s.getMethod())) {
                    GraphDelta d = s.process(c, haf, g, null, registrar);
                    if (d == null) {
                        throw new RuntimeException("s returned null "
                                + s.getClass() + " : " + s);
                    }
                    changed |= !d.isEmpty();
                    if (!d.isEmpty()) {

                        System.err.println("uhoh Failed on " + s
                                + "\n    Delta is " + d);
                        failcount++;
                        if (failcount > 10) {
                            System.err.println("\nThere may be more failures, but exiting now...");
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
    private boolean addInterestingDependency(PointsToGraphNode n,
            StmtAndContext sac) {
        Set<StmtAndContext> s = interestingDepedencies.get(n);
        if (s == null) {
            s = new LinkedHashSet<>();
            interestingDepedencies.put(n, s);
        }
        return s.add(sac);
    }

    private class CustomQueue extends
            AbstractQueue<OrderedPair<StmtAndContext, GraphDelta>> {
        final Queue<StmtAndContext> q;
        final Map<StmtAndContext, GraphDelta> deltas = new HashMap<>();
        final Set<StmtAndContext> deltaFree = new HashSet<>();

        CustomQueue() {

            Comparator<StmtAndContext> cmpr = new Comparator<StmtAndContext>() {

                @Override
                public int compare(StmtAndContext o1, StmtAndContext o2) {
                    // first, run no deltas, then small delta, then the large delta.

                    // return a negative number if o1 is "less than" or more important than o2
                    boolean o1DeltaFree = deltaFree.contains(o1);
                    boolean o2DeltaFree = deltaFree.contains(o2);

                    if (o1DeltaFree && !o2DeltaFree) {
                        return -1; // o1 first
                    }
                    if (o2DeltaFree && !o1DeltaFree) {
                        return 1; // o2 first
                    }
                    if (o1DeltaFree && o2DeltaFree) {
                        return 0; // Hmmm, maybe some other way to break the tie... 
                    }

                    // both should have deltas
                    GraphDelta o1Delta = deltas.get(o1);
                    GraphDelta o2Delta = deltas.get(o2);
                    int o1Size = o1Delta == null ? -1 : o1Delta.extendedSize();
                    int o2Size = o2Delta == null ? -1 : o2Delta.extendedSize();

                    return o1Size > o2Size ? 1 : o1Size == o2Size ? 0 : -1;
                }

            };
//            q = new PriorityQueue<>(10000, cmpr);
            q = new LinkedList<>();
        }

        @Override
        public int size() {
            return q.size();
        }

        @Override
        public boolean offer(OrderedPair<StmtAndContext, GraphDelta> e) {
            StmtAndContext sac = e.fst();
            GraphDelta delta = e.snd();
            if (delta == null) {
                // we are going to run the sac without any delta.
                deltas.remove(sac);
                deltaFree.add(sac);
                q.offer(sac);
                return true;
            }
            if (deltaFree.contains(sac)) {
                // we are already going to run the sac without a delta.
                // ignore the delta, don't re-add the sac.
                return true;
            }
            GraphDelta existing = deltas.get(sac);
            if (existing != null) {
                // there is already a delta.
                // combine them.
                deltas.put(sac, existing.combine(delta));
            }
            else {
                deltas.put(sac, delta);
            }
            q.offer(sac);
            return true;
        }

        @Override
        public OrderedPair<StmtAndContext, GraphDelta> poll() {
            StmtAndContext sac = q.poll();
            if (sac == null) {
                return null;
            }
            GraphDelta delta = null;
            if (deltaFree.contains(sac)) {
                // nothing to do, delta is null
                deltaFree.remove(sac);
            }
            else {
                delta = deltas.remove(sac);
            }
            return new OrderedPair<PointsToAnalysis.StmtAndContext, GraphDelta>(sac,
                                                                                delta);
        }

        @Override
        public OrderedPair<StmtAndContext, GraphDelta> peek() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<OrderedPair<StmtAndContext, GraphDelta>> iterator() {
            throw new UnsupportedOperationException();
        }
    }
}
