package analysis.pointer.engine;

import java.lang.management.ManagementFactory;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import main.AccrueAnalysisMain;
import util.intmap.ConcurrentIntMap;
import util.intmap.ConcurrentMonotonicIntHashMap;
import util.intmap.IntMap;
import util.intset.ConcurrentMonotonicIntHashSet;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.strings.StringLikeLocationReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.ConstraintStatement;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.StringStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;

public class PointsToAnalysisMultiThreaded extends PointsToAnalysis {
    /**
     * Should we use one task for all the statements associated with a GraphDelta?
     */
    private static final boolean ONE_TASK_PER_DELTA = false;

    /**
     * An interesting dependency from node n to StmtAndContext sac exists when a modification to the pointstoset of n
     * (i.e., if n changes to point to more things) requires reevaluation of sac. Many dependencies are just copy
     * dependencies (which are not interesting dependencies).
     */
    private ConcurrentIntMap<Set<StmtAndContext>> interestingDepedencies = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * An object that helps us track dependencies for the string analysis
     */
    private StringDependencies stringDependencies = new StringDependencies();
    /**
     * If true then the analysis will reprocess all points-to statements after reaching a fixed point to make sure there
     * are no changes.
     */
    private static boolean paranoidMode = false;

    static int numThreads() {
        return AnalysisUtil.numThreads;
    }

    public PointsToAnalysisMultiThreaded(HeapAbstractionFactory haf) {
        super(haf);
    }

    @Override
    public PointsToGraph solve(StatementRegistrar registrar) {
        return this.solveConcurrently(registrar);
    }

    @SuppressWarnings("synthetic-access")
    public PointsToGraph solveConcurrently(final StatementRegistrar registrar) {
        System.err.println("Starting points to engine using " + this.haf + " "
                + PointsToAnalysisMultiThreaded.numThreads() + " threads " + " intmap is "
                + this.makeConcurrentIntMap().getClass().getName());
        long startTime = System.currentTimeMillis();


        //final ExecutorServiceCounter execService = new ExecutorServiceCounter(Executors.newFixedThreadPool(this.numThreads()));
        final ExecutorServiceCounter execService = new ExecutorServiceCounter(new ForkJoinPool(PointsToAnalysisMultiThreaded.numThreads()));
        //        final ExecutorServiceCounter execService = new ExecutorServiceCounter(new ForkJoinPool(this.numThreads(),
        //                                                                                               ForkJoinPool.defaultForkJoinWorkerThreadFactory,
        //                                                                                               null,
        //                                                                                               true));

        DependencyRecorder depRecorder = new DependencyRecorder() {

            @Override
            public void recordRead(int n, StmtAndContext sac) {
                addInterestingDependency(n, sac);
            }

            @Override
            public void startCollapseNode(int n, int rep) {
                // add the new dependencies.
                Set<StmtAndContext> deps = interestingDepedencies.get(n);
                if (deps != null) {
                    for (StmtAndContext depSac : deps) {
                        addInterestingDependency(rep, depSac);
                    }
                }
            }

            @Override
            public void finishCollapseNode(int n) {
                // remove the old dependencies.
                interestingDepedencies.remove(n);
            }

            @Override
            public void recordNewContext(IMethod callee, Context calleeContext) {
                for (ConstraintStatement stmt : registrar.getStatementsForMethod(callee)) {
                    StmtAndContext newSaC = new StmtAndContext(stmt, calleeContext);
                    execService.submitTask(newSaC);
                }
                for (ConstraintStatement stmt : registrar.getStringStatementsForMethod(callee)) {
                    StmtAndContext newSaC = new StmtAndContext(stmt, calleeContext);
                    execService.submitTask(newSaC);
                }
            }

            @Override
            public void recordRead(StringLikeLocationReplica v, StmtAndContext sac) {
                stringDependencies.recordRead(v, sac);
            }

            @Override
            public void recordWrite(StringLikeLocationReplica v, StmtAndContext sac) {
                stringDependencies.recordWrite(v, sac);
            }

            @Override
            public void printStringDependencyTree(StringLikeLocationReplica v) {
                stringDependencies.printStringDependencyTree(v, "  ");
            }
        };

        PointsToGraph g = new PointsToGraph(registrar, this.haf, depRecorder);
        execService.setGraphAndRegistrar(g, registrar);

        // Add initial contexts
        for (IMethod m : registrar.getInitialContextMethods()) {
            for (PointsToStatement s : registrar.getStatementsForMethod(m)) {
                for (Context c : g.getContexts(s.getMethod())) {
                    StmtAndContext sac = new StmtAndContext(s, c);
                    execService.submitTask(sac);
                }
            }
            for (StringStatement s : registrar.getStringStatementsForMethod(m)) {
                for (Context c : g.getContexts(s.getMethod())) {
                    StmtAndContext sac = new StmtAndContext(s, c);
                    execService.submitTask(sac);
                }
            }
        }

        // start up...

        while (execService.containsPending()) {
            execService.waitUntilAllFinished();
        }
        // all the tasks are done.
        // Shut down the executer service
        execService.shutdownAndAwaitTermination();

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        int ptgNodes = g.numPointsToGraphNodes();

        // Compute the number of edges
        IntMap<MutableIntSet> graph = g.getPointsToGraph();
        IntIterator nodes = graph.keyIterator();
        long totalEdges = 0;
        while (nodes.hasNext()) {
            int n = nodes.next();
            if (g.isCollapsedNode(n)) {
                // don't double count this node
                continue;
            }
            IntSet s = graph.get(n);
            totalEdges += s.size();
        }

        // number of call graph nodes
        int numCGNodes = g.getNumberOfCallGraphNodes();

        System.out.println(totalTime / 1000.0);
        System.err.println("\n\n  ***************************** \n\n");
        System.err.println("   Total time             : " + totalTime / 1000.0 + "s.");
        System.err.println("   Number of threads used : " + PointsToAnalysisMultiThreaded.numThreads());
        System.err.println("   Num graph source nodes : " + ptgNodes);
        System.err.println("   Num nodes collapsed    : " + g.cycleRemovalCount());
        System.err.println("   Num graph edges        : " + totalEdges);
        System.err.println("   Num CG nodes           : " + numCGNodes);

        System.err.println("\n\nENTRY: " + AnalysisUtil.entryPoint);
        System.err.println(numCGNodes);
        System.err.println(ptgNodes);
        System.err.println(totalEdges);
        System.err.println(numCGNodes + " " + ptgNodes + " " + totalEdges);
        System.err.println("\t&\t" + NumberFormat.getNumberInstance(Locale.US).format(numCGNodes) + "\t&\t"
                + NumberFormat.getNumberInstance(Locale.US).format(ptgNodes) + "\t&\t"
                + NumberFormat.getNumberInstance(Locale.US).format(totalEdges) + "\t \\\\ \\hline");
        System.err.println("\n");

        if (!AccrueAnalysisMain.testMode) {

            //        System.err.println("   Cycles removed         : " + g.cycleRemovalCount() + " nodes");
            System.err.println("   Memory utilization     : "
                    + (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1000000) + "MB");
            System.gc();
            System.err.println("   Memory utilization post GC : "
                    + (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1000000) + "MB");
            System.err.println("\n\n");
        }
        if (paranoidMode) {
            // check that nothing went wrong, and that we have indeed reached a fixed point.
            this.processAllStatements(g, registrar);
            GraphDelta delta = g.findCycles();
            assert delta == null || delta.isEmpty() : delta.toString();
            System.err.println("   New num nodes collapsed    : " + g.cycleRemovalCount());
            long totalEdges2 = 0;
            graph = g.getPointsToGraph();
            nodes = graph.keyIterator();
            while (nodes.hasNext()) {
                int n = nodes.next();
                if (g.isCollapsedNode(n)) {
                    // don't double count this node
                    continue;
                }
                IntSet s = graph.get(n);
                totalEdges2 += s.size();
            }
            System.err.println("   New num graph edges        : " + totalEdges2);

        }
        if (!AccrueAnalysisMain.testMode) {
            g.constructionFinished();
            System.gc();
            System.err.println("   Memory post compression: "
                    + (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1000000) + "MB");
            System.err.println("\n\n");
        }


        return g;
    }


    void processSaC(StmtAndContext sac, GraphDelta delta, ExecutorServiceCounter execService) {
        ConstraintStatement s = sac.stmt;
        Context c = sac.context;

        GraphDelta changes = s.process(c, this.haf, execService.g, delta, execService.registrar, sac);

        if (changes.isEmpty()) {
            return;
        }
        IntIterator iter = changes.domainIterator();
        if (ONE_TASK_PER_DELTA) {
            Set<StmtAndContext> depSaCs = new HashSet<>();
            while (iter.hasNext()) {
                int n = iter.next();
                depSaCs.addAll(this.getInterestingDependencies(n));
            }
            execService.submitTask(depSaCs, changes);
        }
        else {
            while (iter.hasNext()) {
                int n = iter.next();
                for (StmtAndContext depSaC : this.getInterestingDependencies(n)) {
                    execService.submitTask(depSaC, changes);
                }
            }
        }

        // first gather up all the string statements that need to be processed.
        Set<StmtAndContext> reprocess = new HashSet<>();
        for (StringLikeLocationReplica v : changes.getStringConstraintDelta().getNewlyActivated()) {
            reprocess.addAll(stringDependencies.getWriteTo(v));
        }
        for (StringLikeLocationReplica v : changes.getStringConstraintDelta().getUpdated()) {
            reprocess.addAll(stringDependencies.getReadFrom(v));
        }
        // now process them...
        for (StmtAndContext depSaC : reprocess) {
            execService.submitTask(depSaC, changes);
        }
    }

    class ExecutorServiceCounter {
        public PointsToGraph g;
        public StatementRegistrar registrar;
        private ExecutorService exec;

        /**
         * The number of tasks currently to be executed
         */
        private AtomicLong numTasks;

        /*
         * The following fields are for statistics purposes
         */
        private AtomicLong totalTasksNoDelta;
        private AtomicLong totalTasksWithDelta;

        public ExecutorServiceCounter(ExecutorService exec) {
            this.exec = exec;
            this.numTasks = new AtomicLong(0);
            this.totalTasksNoDelta = new AtomicLong(0);
            this.totalTasksWithDelta = new AtomicLong(0);
        }

        public void setGraphAndRegistrar(PointsToGraph g, StatementRegistrar registrar) {
            this.g = g;
            this.registrar = registrar;

        }

        public void shutdownAndAwaitTermination() {
            exec.shutdown();
            boolean finished = false;
            // keep waiting until we successfully finish all the outstanding tasks
            do {
                try {
                    finished = exec.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } while (!finished);

        }

        public void submitTask(StmtAndContext sac) {
            submitTask(sac, null);
        }

        public void submitTask(StmtAndContext sac, GraphDelta delta) {
            this.numTasks.incrementAndGet();
            if (delta == null) {
                this.totalTasksNoDelta.incrementAndGet();
            }
            else {
                this.totalTasksWithDelta.incrementAndGet();
            }
            exec.execute(new RunnableStmtAndContext(sac, delta));
        }

        public void submitTask(Set<StmtAndContext> sacs, GraphDelta delta) {
            this.numTasks.incrementAndGet();
            assert delta != null;
            this.totalTasksWithDelta.incrementAndGet();
            exec.execute(new RunnableStmtAndContextsWithDelta(sacs, delta));
        }

        public void finishedTask() {
            if (this.numTasks.decrementAndGet() == 0) {
                // Notify anyone that was waiting.
                synchronized (this) {
                    this.notifyAll();
                }
            }
        }

        public boolean containsPending() {
            return numTasks.get() > 0;
        }

        public long numRemainingTasks() {
            return numTasks.get();
        }

        public void waitUntilAllFinished() {
            if (this.containsPending()) {
                synchronized (this) {
                    try {
                        // XXX could add timeout stuff here
                        this.wait();
                    }
                    catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        public class RunnableStmtAndContext implements Runnable {
            private final StmtAndContext sac;
            private final GraphDelta delta;

            public RunnableStmtAndContext(StmtAndContext stmtAndContext, GraphDelta delta) {
                this.sac = stmtAndContext;
                this.delta = delta;
            }

            @Override
            public void run() {
                try {
                    processSaC(sac, delta, ExecutorServiceCounter.this);
                    ExecutorServiceCounter.this.finishedTask();
                }
                catch (Throwable e) {
                    e.printStackTrace();
                    System.exit(0);
                    // No seriously DIE!
                    Runtime.getRuntime().halt(0);
                }
            }
        }

        public class RunnableStmtAndContextsWithDelta implements Runnable {
            private final Set<StmtAndContext> sacs;
            private final GraphDelta delta;

            public RunnableStmtAndContextsWithDelta(Set<StmtAndContext> stmtAndContexts, GraphDelta delta) {
                this.sacs = stmtAndContexts;
                this.delta = delta;
            }

            @Override
            public void run() {
                try {
                    for (StmtAndContext sac : sacs) {
                        processSaC(sac, delta, ExecutorServiceCounter.this);
                    }
                    ExecutorServiceCounter.this.finishedTask();
                }
                catch (Throwable e) {
                    e.printStackTrace();
                    System.exit(0);
                    // No seriously DIE!
                    Runtime.getRuntime().halt(0);
                }
            }
        }

    }

    /**
     * Loop through and process all the points-to statements in the registrar.
     *
     * @param g points-to graph (may be modified)
     * @param registrar points-to statement registrar
     * @return true if the points-to graph changed
     */
    private boolean processAllStatements(PointsToGraph g, StatementRegistrar registrar) {
        boolean changed = false;
        System.err.println("Processing all statements for good luck: " + registrar.size() + " from "
                + registrar.getRegisteredMethods().size() + " methods");
        int failcount = 0;
        for (IMethod m : registrar.getRegisteredMethods()) {
            for (PointsToStatement s : registrar.getStatementsForMethod(m)) {
                for (Context c : g.getContexts(s.getMethod())) {
                    GraphDelta d = s.process(c, this.haf, g, null, registrar, new StmtAndContext(s, c));
                    if (d == null) {
                        throw new RuntimeException("s returned null " + s.getClass() + " : " + s);
                    }
                    changed |= !d.isEmpty();
                    if (!d.isEmpty()) {
                        IntIterator iter = d.domainIterator();
                        while (iter.hasNext()) {
                            int n = iter.next();
                            assert !g.isCollapsedNode(n) : "Apparently we added to a collapsed node";
                        }
                        System.err.println("uhoh Failed on " + s + "\n    Delta is " + d);
                        failcount++;
                        if (failcount > 3) {
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
     * @param n node to get the dependencies for
     * @return set of dependencies
     */
    private Set<StmtAndContext> getInterestingDependencies(/*PointsToGraphNode*/int n) {
        Set<StmtAndContext> sacs = this.interestingDepedencies.get(n);
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
     * @param n node the statement depends on
     * @param sac statement and context that depends on <code>n</code>
     * @return true if the dependency did not already exist
     */
    boolean addInterestingDependency(/*PointsToGraphNode*/int n, StmtAndContext sac) {
        // use double checked approach...

        Set<StmtAndContext> s = this.interestingDepedencies.get(n);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<StmtAndContext> existing = this.interestingDepedencies.putIfAbsent(n, s);
            if (existing != null) {
                s = existing;
            }
        }
        return s.add(sac);
    }

    public static MutableIntSet makeConcurrentIntSet() {
        return new ConcurrentMonotonicIntHashSet(AnalysisUtil.numThreads);
        //return new ConcurrentIntHashSet(16, 0.75f, AnalysisUtil.numThreads);
        //return new MutableIntSetFromMap(PointsToAnalysisMultiThreaded.<Boolean> makeConcurrentIntMap());
    }

    public static <T> ConcurrentIntMap<T> makeConcurrentIntMap() {
        return new ConcurrentMonotonicIntHashMap<>(AnalysisUtil.numThreads);
        //return new ConcurrentIntHashMap<>(16, 0.75f, AnalysisUtil.numThreads);
    }

    /**
     * Set the analysis to reprocess all statements (single-threaded) after running the multi-threaded analysis.
     *
     * @param reprocessAllStatements if true then reprocess all statements after reaching a fixed point
     */
    public static void setParanoidMode(boolean reprocessAllStatements) {
        paranoidMode = reprocessAllStatements;
    }

}
