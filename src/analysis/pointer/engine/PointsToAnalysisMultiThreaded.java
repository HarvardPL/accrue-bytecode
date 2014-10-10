package analysis.pointer.engine;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import util.intmap.ConcurrentIntHashMap;
import util.intmap.ConcurrentIntMap;
import util.intset.ConcurrentIntHashSet;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.StatementRegistrar.StatementListener;
import analysis.pointer.statements.PointsToStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.MutableIntSet;

public class PointsToAnalysisMultiThreaded extends PointsToAnalysis {


    /**
     * An interesting dependency from node n to StmtAndContext sac exists when a modification to the pointstoset of n
     * (i.e., if n changes to point to more things) requires reevaluation of sac. Many dependencies are just copy
     * dependencies (which are not interesting dependencies).
     */
    private ConcurrentIntMap<Set<StmtAndContext>> interestingDepedencies = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();
    /**
     * If true then the analysis will reprocess all points-to statements after reaching a fixed point to make sure there
     * are no changes.
     */
    private static boolean paranoidMode = false;

    int numThreads() {
        //return 1;
        return Runtime.getRuntime().availableProcessors();
    }

    public PointsToAnalysisMultiThreaded(HeapAbstractionFactory haf) {
        super(haf);
    }

    @Override
    public PointsToGraph solve(StatementRegistrar registrar) {
        return this.solveConcurrently(registrar, false);
    }

    @Override
    public PointsToGraph solveAndRegister(StatementRegistrar onlineRegistrar) {
        onlineRegistrar.registerMethod(AnalysisUtil.getFakeRoot());
        return this.solveConcurrently(onlineRegistrar, true);
    }

    public PointsToGraph solveConcurrently(final StatementRegistrar registrar, final boolean registerOnline) {
        System.err.println("Starting points to engine using " + this.haf);
        long startTime = System.currentTimeMillis();


        final ExecutorServiceCounter execService = new ExecutorServiceCounter(Executors.newFixedThreadPool(this.numThreads()));
        //final ExecutorServiceCounter execService = new ExecutorServiceCounter(new ForkJoinPool(this.numThreads()));
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
            public void finishCollapseNode(int n, int rep) {
                // remove the old dependency.
                Set<StmtAndContext> deps = interestingDepedencies.get(n);
                if (deps != null) {
                    interestingDepedencies.remove(n);
                }
            }

            @Override
            public void recordNewContext(IMethod callee, Context calleeContext) {
                if (registerOnline) {
                    // Add statements for the given method to the registrar
                    registrar.registerMethod(callee);
                }

                for (PointsToStatement stmt : registrar.getStatementsForMethod(callee)) {
                    StmtAndContext newSaC = new StmtAndContext(stmt, calleeContext);
                    execService.submitTask(newSaC);
                }
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
        }

        if (registerOnline) {
            StatementListener stmtListener = new StatementListener() {

                @Override
                public void newStatement(PointsToStatement stmt) {
                    if (stmt.getMethod().equals(registrar.getEntryPoint())) {
                        // it's a new special instruction. Let's make sure it gets evaluated.
                        execService.submitTask(new StmtAndContext(stmt, haf.initialContext()));
                    }

                }

            };
            registrar.setStatementListener(stmtListener);
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
        System.err.println("\n\n  ***************************** \n\n");
        System.err.println("   Total time             : " + totalTime / 1000 + "s.");
        System.err.println("   Number of threads used : " + this.numThreads());
        System.err.println("   Num graph source nodes : " + g.numPointsToGraphNodes());
        System.err.println("   Cycles removed         : " + g.cycleRemovalCount() + " nodes");
        System.gc();
        System.err.println("   Memory utilization     : "
                + (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1000000) + "MB");
        System.err.println("\n\n");

        if (paranoidMode) {
            // check that nothing went wrong, and that we have indeed reached a fixed point.
            this.processAllStatements(g, registrar);
        }
        g.constructionFinished();
        return g;
    }


    void processSaC(StmtAndContext sac, GraphDelta delta, ExecutorServiceCounter execService) {
        PointsToStatement s = sac.stmt;
        Context c = sac.context;

        GraphDelta changes = s.process(c, this.haf, execService.g, delta, execService.registrar, sac);

        if (changes.isEmpty()) {
            return;
        }
        IntIterator iter = changes.domainIterator();
        while (iter.hasNext()) {
            int n = iter.next();
            for (StmtAndContext depSaC : this.getInterestingDependencies(n)) {
                execService.submitTask(depSaC, changes);
            }
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
                catch (ConcurrentModificationException e) {
                    System.err.println("ConcurrentModificationException!!!");
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
                        System.err.println("uhoh Failed on " + s + "\n    Delta is " + d);
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
            s = makeConcurrentSet();
            Set<StmtAndContext> existing = this.interestingDepedencies.putIfAbsent(n, s);
            if (existing != null) {
                s = existing;
            }
        }
        return s.add(sac);
    }

    public static <T> Set<T> makeConcurrentSet() {
        return Collections.newSetFromMap(new ConcurrentHashMap<T, Boolean>());
    }

    public static MutableIntSet makeConcurrentIntSet() {
        return new ConcurrentIntHashSet();
        //        return new MutableIntSetFromMap(PointsToAnalysisMultiThreaded.<Boolean> makeConcurrentIntMap());
    }

    public static <T> ConcurrentIntMap<T> makeConcurrentIntMap() {
        return new ConcurrentIntHashMap<>();
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
