package analysis.pointer.engine;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
import analysis.pointer.statements.ArrayToLocalStatement;
import analysis.pointer.statements.CallStatement;
import analysis.pointer.statements.ClassInitStatement;
import analysis.pointer.statements.ExceptionAssignmentStatement;
import analysis.pointer.statements.FieldToLocalStatement;
import analysis.pointer.statements.LocalToArrayStatement;
import analysis.pointer.statements.LocalToFieldStatement;
import analysis.pointer.statements.LocalToLocalStatement;
import analysis.pointer.statements.LocalToStaticFieldStatement;
import analysis.pointer.statements.NewStatement;
import analysis.pointer.statements.PhiStatement;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.ReturnStatement;
import analysis.pointer.statements.StaticFieldToLocalStatement;

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
    public PointsToGraph solveSmarter(StatementRegistrar registrar,
            boolean registerOnline) {
        PointsToGraph g = new PointsToGraph(registrar, haf);
        System.err.println("Starting points to engine using " + haf);
        startTime = System.currentTimeMillis();
        nextMilestone = startTime - 1;

        Queue<OrderedPair<GraphDelta, ? extends Collection<StmtAndContext>>> queue =
                Collections.asLifoQueue(new ArrayDeque<OrderedPair<GraphDelta, ? extends Collection<StmtAndContext>>>());
        Queue<OrderedPair<GraphDelta, ? extends Collection<StmtAndContext>>> nextQueue =
                Collections.asLifoQueue(new ArrayDeque<OrderedPair<GraphDelta, ? extends Collection<StmtAndContext>>>());
        Queue<StmtAndContext> noDeltaQueue = new TopoSortQueue();

        // Add initial contexts
        for (IMethod m : registrar.getInitialContextMethods()) {
            for (PointsToStatement s : registrar.getStatementsForMethod(m)) {
                for (Context c : g.getContexts(s.getMethod())) {
                    noDeltaQueue.add(new StmtAndContext(s, c));
                }
            }
        }

        lastTime = startTime;
        Set<StmtAndContext> visited = new HashSet<>();
        while (!queue.isEmpty() || !nextQueue.isEmpty()
                || !noDeltaQueue.isEmpty()) {
            if (queue.isEmpty()) {
                // the queue is now empty, swap it with nextQueue
                Queue<OrderedPair<GraphDelta, ? extends Collection<StmtAndContext>>> t =
                        queue;
                queue = nextQueue;
                nextQueue = t;
            }

            // get the next sac, and the delta for it.
            if (queue.isEmpty()) {
                // get the next from the noDelta queue
                StmtAndContext sac = noDeltaQueue.poll();
                processSaC(sac,
                           null,
                           g,
                           registrar,
                           nextQueue,
                           noDeltaQueue,
                           registerOnline);

            }
            else {
                OrderedPair<GraphDelta, ? extends Collection<StmtAndContext>> next =
                        queue.poll();
                GraphDelta delta = next.fst();
                Collection<StmtAndContext> sacs = next.snd();

                for (StmtAndContext sac : sacs) {
                    processSaC(sac,
                               delta,
                               g,
                               registrar,
                               nextQueue,
                               noDeltaQueue,
                               registerOnline);
                }
            }
        }

        long endTime = System.currentTimeMillis();
        System.err.println("Processed " + numProcessed
                + " (statement, context) pairs"
                + (outputLevel >= 1 ? " (" + visited.size() + " unique)" : ""));
        long totalTime = endTime - startTime;
        System.err.println("   Total time       : " + totalTime / 1000 + "s.");
        System.err.println("   Registration time: " + registrationTime / 1000
                + "s.");
        System.err.println("   => Analysis time : "
                + (totalTime - registrationTime) / 1000 + "s.");
        System.err.println("   Topo sort time   : " + topoSortTime / 1000
                + "s.");
        System.err.println("   => Analysis time - topo sort : "
                + (totalTime - (registrationTime + topoSortTime)) / 1000 + "s.");
        System.err.println("   Num no delta processed " + numNoDeltaProcessed);
        System.err.println("   Num with delta processed "
                + (numProcessed - numNoDeltaProcessed));
        System.err.println("   Cycles removed " + g.cycleRemovalCount()
                + " nodes");
        System.err.println("   Finding more cycles...");
        g.findCycles();
        System.err.println("   Cycles now removed " + g.cycleRemovalCount()
                + " nodes");
        processAllStatements(g, registrar);
        if (outputLevel >= 5) {
            System.err.println("****************************** CHECKING ******************************");
            PointsToGraph.DEBUG = true;
            DEBUG_SOLVED = true;
            processAllStatements(g, registrar);
        }
        return g;
    }

    int numProcessed = 0;
    int lastNumProcessed = 0;
    int numNoDeltaProcessed = 0;
    int lastNumNoDeltaProcessed = 0;
    int processedWithNoChange = 0;
    long registrationTime = 0;
    long topoSortTime = 0;
    long nextMilestone;
    long lastTime;
    long startTime;

    private void processSaC(
            StmtAndContext sac,
            GraphDelta delta,
            PointsToGraph g,
            StatementRegistrar registrar,
            Queue<OrderedPair<GraphDelta, ? extends Collection<StmtAndContext>>> queue,
            Queue<StmtAndContext> noDeltaQueue, boolean registerOnline) {
        numProcessed++;
        if (delta == null) {
            numNoDeltaProcessed++;
        }

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
                g.getAndClearNewlyCollapsedNodes();

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
                long start = System.currentTimeMillis();
                registrar.registerMethod(m);
                long end = System.currentTimeMillis();
                registrationTime += end - start;
            }

            for (PointsToStatement stmt : registrar.getStatementsForMethod(m)) {
                if (outputLevel >= 4) {
                    System.err.println("\t\tADDING " + stmt);
                }

                for (Context context : newContexts.get(m)) {
                    noDeltaQueue.add(new StmtAndContext(stmt, context));
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

        // now update the interesting dependencies.
        for (PointsToGraphNode n : newlyCombinedNodes) {
            Set<StmtAndContext> deps = interestingDepedencies.remove(n);
            if (deps != null) {
                for (StmtAndContext depSac : deps) {
                    addInterestingDependency(g.getRepresentative(n), depSac);
                }
            }
        }

        if (changed.isEmpty()) {
            processedWithNoChange++;
        }
        handleChanges(queue, changed, g);

        long currTime = System.currentTimeMillis();
        if (currTime > nextMilestone) {
            do {
                nextMilestone = nextMilestone + 1000 * 30; // 30 seconds 
            } while (currTime > nextMilestone);

            System.err.println("PROCESSED: " + numProcessed + " in "
                    + (currTime - startTime) / 1000 + "s;  graph="
                    + g.getBaseNodes().size() + " base nodes; cycles removed "
                    + g.cycleRemovalCount() + " nodes ; queue=" + queue.size()
                    + " noDeltaQueue=" + noDeltaQueue.size() + " ("
                    + (numProcessed - lastNumProcessed) + " in "
                    + (currTime - lastTime) / 1000 + "s)");
            lastTime = currTime;
            lastNumProcessed = numProcessed;
            lastNumNoDeltaProcessed = numNoDeltaProcessed;
            processedWithNoChange = 0;
        }
    }

    private void handleChanges(
            Queue<OrderedPair<GraphDelta, ? extends Collection<StmtAndContext>>> queue,
            GraphDelta changes, PointsToGraph g) {
        if (changes.isEmpty()) {
            return;
        }
        Iterator<PointsToGraphNode> iter = changes.domainIterator();
        while (iter.hasNext()) {
            PointsToGraphNode n = iter.next();
            queue.add(new OrderedPair<>(changes, getInterestingDependencies(n)));
        }

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
            s = new HashSet<>();
            interestingDepedencies.put(n, s);
        }
        return s.add(sac);
    }

    static class PartitionedQueue extends
            AbstractQueue<PointsToAnalysis.StmtAndContext> {
        Queue<PointsToAnalysis.StmtAndContext> base = new LinkedList<>();
        //Collections.asLifoQueue(new ArrayDeque<StmtAndContext>());
        Queue<StmtAndContext> localAssigns =
        //        new LinkedList<>();
                Collections.asLifoQueue(new ArrayDeque<StmtAndContext>());
        Queue<StmtAndContext> fieldReads =
        //        new LinkedList<>();
                Collections.asLifoQueue(new ArrayDeque<StmtAndContext>());
        Queue<StmtAndContext> fieldWrites =
        //        new LinkedList<>();
                Collections.asLifoQueue(new ArrayDeque<StmtAndContext>());
        Queue<StmtAndContext> calls =
        //        new LinkedList<>();
                Collections.asLifoQueue(new ArrayDeque<StmtAndContext>());

        ArrayList<Queue<StmtAndContext>> ordered = new ArrayList<>();

        {
            ordered.add(localAssigns);
            ordered.add(fieldWrites);
            ordered.add(fieldReads);
            ordered.add(base);
            ordered.add(calls);
        }

        @Override
        public boolean offer(StmtAndContext sac) {
            PointsToStatement stmt = sac.stmt;
            if (stmt instanceof NewStatement) {
                return base.offer(sac);
            }
            if (stmt instanceof CallStatement
                    || stmt instanceof ClassInitStatement) {
                return calls.offer(sac);
            }
            if (stmt instanceof FieldToLocalStatement
                    || stmt instanceof ArrayToLocalStatement) {
                return fieldReads.offer(sac);
            }
            if (stmt instanceof LocalToFieldStatement
                    || stmt instanceof LocalToArrayStatement) {
                return fieldWrites.offer(sac);
            }
            if (stmt instanceof LocalToLocalStatement
                    || stmt instanceof LocalToStaticFieldStatement
                    || stmt instanceof PhiStatement
                    || stmt instanceof ReturnStatement
                    || stmt instanceof StaticFieldToLocalStatement
                    || stmt instanceof ExceptionAssignmentStatement) {
                return localAssigns.offer(sac);
            }
            throw new IllegalArgumentException("Don't know how to handle a "
                    + stmt.getClass());
        }

        @Override
        public StmtAndContext poll() {
            for (Queue<StmtAndContext> q : ordered) {
                if (!q.isEmpty()) {
                    return q.poll();
                }
            }
            return null;
        }

        @Override
        public StmtAndContext peek() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<StmtAndContext> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int size() {
            int size = 0;
            for (Queue<StmtAndContext> q : ordered) {
                size += q.size();
            }
            return size;
        }

        @Override
        public boolean isEmpty() {
            for (int i = ordered.size() - 1; i >= 0; i--) {
                Queue<StmtAndContext> q = ordered.get(i);
                if (!q.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

    }

    class TopoSortQueue extends AbstractQueue<StmtAndContext> {
        ArrayList<StmtAndContext> current = new ArrayList<>();
        int sinceLastSorted = 0;

        ArrayList<StmtAndContext> next = new ArrayList<>();

        @Override
        public boolean offer(StmtAndContext e) {
            next.add(e);//XXX next.add(e)
            sinceLastSorted++;
            if (sinceLastSorted > 500) {
                current.addAll(next);
                next.clear();
                current = topoSortArray(current);
                sinceLastSorted = 0;
            }
            return true;
        }

        @Override
        public StmtAndContext poll() {
            if (current.isEmpty()) {
                next = topoSortArray(next);
                ArrayList<StmtAndContext> t = current;
                current = next;
                next = t;
                sinceLastSorted = 0;
            }
            return current.remove(current.size() - 1);
        }

        /*
         * Topo sort the array
         */
        private ArrayList<StmtAndContext> topoSortArray(
                ArrayList<StmtAndContext> arr) {
            long start = System.currentTimeMillis();

            // First, set up a map for which RVRs are read and written
            Map<Object, Set<StmtAndContext>> readsRVR = new HashMap<>();
            for (StmtAndContext sac : arr) {
                for (Object read : sac.getRVRReads(haf)) {
                    Set<StmtAndContext> set = readsRVR.get(read);
                    if (set == null) {
                        set = new HashSet<>();
                        readsRVR.put(read, set);
                    }
                    set.add(sac);
                }
            }

            Set<StmtAndContext> done = new HashSet<>();
            Set<StmtAndContext> visiting = new HashSet<>();
            ArrayList<StmtAndContext> sorted = new ArrayList<>(arr.size());
            for (int i = arr.size() - 1; i >= 0; i--) {
                StmtAndContext sac = arr.get(i);
                visit(sac, done, visiting, sorted, readsRVR);
            }
            long end = System.currentTimeMillis();
            topoSortTime += end - start;
            return sorted;

        }

        private void visit(StmtAndContext sac, Set<StmtAndContext> done,
                Set<StmtAndContext> visiting, ArrayList<StmtAndContext> sorted,
                Map<Object, Set<StmtAndContext>> readsRVR) {
            if (visiting.contains(sac)) {
                // this is a cycle. ignore this edge
                return;
            }
            if (!done.contains(sac)) {
                visiting.add(sac);
                for (StmtAndContext child : children(sac, readsRVR)) {
                    visit(child, done, visiting, sorted, readsRVR);
                }
                done.add(sac);
                visiting.remove(sac);
                sorted.add(sac);
            }
        }

        private Collection<StmtAndContext> children(StmtAndContext sac,
                Map<Object, Set<StmtAndContext>> readsRVR) {
            // The children of sac are the StmtAndContexts that read a variable that sac writes.
            HashSet<StmtAndContext> set = new HashSet<>();
            for (Object written : sac.getRVRWrites(haf)) {
                Set<StmtAndContext> v = readsRVR.get(written);
                if (v != null) {
                    set.addAll(v);
                }
            }
            return set;
        }

        @Override
        public StmtAndContext peek() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<StmtAndContext> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int size() {
            return current.size() + next.size();
        }

        @Override
        public boolean isEmpty() {
            return current.isEmpty() && next.isEmpty();
        }
    }
}
