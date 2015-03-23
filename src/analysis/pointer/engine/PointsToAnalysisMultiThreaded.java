package analysis.pointer.engine;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import main.AccrueAnalysisMain;
import util.intmap.ConcurrentIntMap;
import util.print.CFGWriter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.AddNonMostRecentOrigin;
import analysis.pointer.graph.AddToSetOriginMaker.AddToSetOrigin;
import analysis.pointer.graph.AllocationDepender;
import analysis.pointer.graph.ApproximateCallSitesAndFieldAssignments;
import analysis.pointer.graph.CallGraphReachability;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ProgramPointReachability;
import analysis.pointer.graph.ProgramPointSubQuery;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.StatementRegistrar.StatementListener;
import analysis.pointer.statements.PointsToStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.MutableIntSet;

public class PointsToAnalysisMultiThreaded extends PointsToAnalysis {

    /**
     * Delay incoming tasks other than (statement, context) pairs to be processed rather than adding them directly to
     * the work queue
     */
    private static final boolean DELAY_OTHER_TASKS = true;
    /**
     * Should we print program point graph, call graph and points-to graph
     */
    private static final boolean PRINT_ALL = false;
    /**
     * Should we print the type of tasks being added when we empty a particular type of queue
     */
    private static final boolean PRINT_QUEUE_SWAPS = false;

    /**
     * Should we print intermittent status messages
     */
    private static final boolean PRINT_DIAGNOSTICS = true;
    /**
     * Print the CG nodes which can reach the most callees
     */
    private static final boolean PRINT_CG_NODES = true && CallGraphReachability.USE_CALL_GRAPH_REACH;

    /**
     * An interesting dependency from node n to StmtAndContext sac exists when a modification to the pointstoset of n
     * (i.e., if n changes to point to more things) requires reevaluation of sac. Many dependencies are just copy
     * dependencies (which are not interesting dependencies).
     */
    ConcurrentIntMap<Set<StmtAndContext>> interestingDepedencies = AnalysisUtil.createConcurrentIntMap();

    /**
     * An allocation dependency from InstanceKeyRecency ikr to StmtAndContext sac exists when a modification to the
     * program points set that allocate ikr requires reevaluation of sac.
     */
    private ConcurrentIntMap<Set<AllocationDepender>> allocationDepedencies = AnalysisUtil.createConcurrentIntMap();

    /**
     * Useful handle to pass around.
     */
    private PointsToAnalysisHandleImpl analysisHandle;
    /**
     * Number of threads to use for the points-to analysis
     */
    final int numThreads;
    /**
     * Number of call sites that have no callees but are approximated as returning normally
     */
    int approximatedCallSites;
    /**
     * Number of field assignments that have no receivers but are approximated as having an empty kill set
     */
    int approximatedFieldAssigns;

    public PointsToAnalysisMultiThreaded(HeapAbstractionFactory haf, int numThreads) {
        super(haf);
        this.numThreads = numThreads;
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
        System.err.println("Starting points to engine using " + this.haf + " (multithreaded, " + this.numThreads
                + " thread(s))");
        PointsToAnalysis.startTime = System.currentTimeMillis();


        final ExecutorServiceCounter execService = new ExecutorServiceCounter(new ForkJoinPool(this.numThreads));

        DependencyRecorder depRecorder = new DependencyRecorder() {

            @Override
            public void recordRead(int n, StmtAndContext sac) {
                addInterestingDependency(n, sac);
            }

            @Override
            public void recordAllocationDependency(int ikr, AllocationDepender origin) {
                addAllocationDependency(ikr, origin);
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



        this.analysisHandle = new PointsToAnalysisHandleImpl(execService);

        final PointsToGraph g = new PointsToGraph(registrar, this.haf, depRecorder, analysisHandle);
        execService.setGraphAndRegistrar(g, registrar);
        this.analysisHandle.setPointsToGraph(g);

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
        while (execService.isWorkToFinish()) {
            execService.waitUntilAllFinished();
        }

        // all the tasks are done.
        // Shut down the executer service
        execService.shutdownAndAwaitTermination();

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - PointsToAnalysis.startTime;
        if (AccrueAnalysisMain.testMode) {
            System.out.println(totalTime / 1000.0);
        }
        g.ppReach.printDiagnostics();
        System.err.println("\n\n  ***************************** \n\n");
        System.out.println(totalTime / 1000.0);
        System.err.println("   Number of threads used : " + this.numThreads);
        System.err.println("   Num graph source nodes : " + g.numPointsToGraphNodes());

        if (!AccrueAnalysisMain.testMode) {
            //        System.err.println("   Cycles removed         : " + g.cycleRemovalCount() + " nodes");
            System.err.println("   Memory utilization     : "
                    + (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1000000));
            System.gc();
            System.err.println("   Memory utilization post GC: "
                    + (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1000000));
            System.err.println("\n\n");

        }

        if (ProgramPointReachability.PRINT_DIAGNOSTICS) {
            System.err.println("APPROXIMATED call sites " + approximatedCallSites);
            System.err.println("OVERAPPROXIMATED call sites "
                    + ProgramPointReachability.nonEmptyApproximatedCallSites.size());
            System.err.println("APPROXIMATED field assignments " + approximatedFieldAssigns);
            System.err.println("OVERAPPROXIMATED field assignments "
                    + ProgramPointReachability.nonEmptyApproximatedKillSets.size());
        }

        if (paranoidMode) {
            // check that nothing went wrong, and that we have indeed reached a fixed point.
            System.err.println("########################");
            g.ppReach.clearCaches();
            System.err.println("Start garbage collection");
            System.gc();
            System.err.println("Finished garbage collection");
            this.processAllStatements(g, registrar);
            System.err.println("CHECKED all statements.");
            System.err.println("########################");
        }

        g.constructionFinished();
        if (PRINT_ALL) {
            registrar.dumpProgramPointSuccGraphToFile("tests/programPointSuccGraph");
            g.dumpPointsToGraphToFile("tests/pointsToGraph");
            g.getCallGraph().dumpCallGraphToFile("tests/callGraph", false);
            for (IMethod m : registrar.getRegisteredMethods()) {
                if (m.toString().contains("main")) {
                    CFGWriter.writeToFile(m);
                    break;
                }
            }
        }

        if (!AccrueAnalysisMain.testMode) {
            System.gc();
            System.err.println("   Memory post compression: "
                    + (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1000000) + "MB");
            System.err.println("\n\n");
        }

        if (PRINT_CG_NODES) {
            System.err.println("\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            System.out.println(g.ppReach.getCallGraphReachability().toString(500));
            System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n");
        }
        return g;
    }


    void processSaC(StmtAndContext sac, GraphDelta delta, ExecutorServiceCounter execService) {
        PointsToStatement s = sac.stmt;
        Context c = sac.context;

        GraphDelta changes = s.process(c, this.haf, execService.g, delta, execService.registrar, sac);

        handleChanges(changes, execService);
    }

    void handleChanges(GraphDelta changes, ExecutorServiceCounter execService) {
        if (changes.isEmpty()) {
            // nothing to do.
            return;
        }

        IntIterator iter = changes.domainIterator();
        while (iter.hasNext()) {
            int n = iter.next();
            for (StmtAndContext depSaC : this.getInterestingDependencies(n)) {
                execService.submitTask(depSaC, changes);
            }
        }
        iter = changes.newAllocationSitesIterator();
        while (iter.hasNext()) {
            int ikr = iter.next();
            for (AllocationDepender depSaC : this.getAllocationDependencies(ikr)) {
                StmtAndContext s = depSaC.getStmtAndContext();
                if (s == null) {
                    depSaC.trigger(this.analysisHandle, changes);
                }
            }
        }

        execService.g.ppReach.checkPointsToGraphDelta(changes);
    }


    class ExecutorServiceCounter {
        public PointsToGraph g;
        public StatementRegistrar registrar;
        private ForkJoinPool exec;

        /**
         * The number of tasks currently to be executed
         */
        private AtomicLong numRemainingTasks;

        /*
         * The following fields are for statistics purposes
         */
        private AtomicLong totalStmtAndCtxtNoDeltaTasks;
        private AtomicLong totalStmtAndCtxtWithDeltaTasks;
        private AtomicLong totalAddNonMostRecentOriginTasks;
        private AtomicLong totalAddToSetTasks;
        private AtomicLong totalPPSubQueryTasks;

        private AtomicLong approximationTime;


        /*
         * Additional queues for other tasks that should have lower priority
         * than StmtAndContexts. The order that these queues are declared in
         * is the intended order of priority, i.e., all pending AddToSetOrigin
         * tasks should be performed before all pending  AddNonMostRecentOrigin tasks, etc.
         */
        private AtomicReference<Set<AddToSetOrigin>> pendingAddToSetOrigin;
        private AtomicReference<Set<AddNonMostRecentOrigin>> pendingAddNonMostRecentOrigin;
        private AtomicReference<Set<ProgramPointSubQuery>> pendingPPSubQuery;

        /**
         * The following atomic boolean is used as a synchronization mechanism to figure out if some thread is currently
         * emptying the queues, so that we don't get two threads both racing to empty the queues. The result of a race
         * is that we may start addressing, e.g., pending ProgramPointSubQuery before we have addressed all of the
         * pending AddToSetOrigin.
         */
        private AtomicBoolean isSomeThreadEmptyingQueues;
        /**
         * After reaching a fixed point, approximate call-sites and field assigns with no targets to get sound
         * reachability results even when there are unsoundnesses in the analysis (e.g. reflection)
         */
        private boolean approximationFinished = false;

        public ExecutorServiceCounter(ForkJoinPool exec) {
            this.exec = exec;
            // Statement and context tasks
            this.numRemainingTasks = new AtomicLong(0);
            this.totalStmtAndCtxtNoDeltaTasks = new AtomicLong(0);
            this.totalStmtAndCtxtWithDeltaTasks = new AtomicLong(0);

            // Other tasks
            this.totalAddNonMostRecentOriginTasks = new AtomicLong(0);
            this.totalAddToSetTasks = new AtomicLong(0);
            this.totalPPSubQueryTasks = new AtomicLong(0);

            this.pendingAddToSetOrigin = new AtomicReference<>(AnalysisUtil.<AddToSetOrigin> createConcurrentSet());
            this.pendingAddNonMostRecentOrigin = new AtomicReference<>(AnalysisUtil.<AddNonMostRecentOrigin> createConcurrentSet());
            this.pendingPPSubQuery = new AtomicReference<>(AnalysisUtil.<ProgramPointSubQuery> createConcurrentSet());

            this.isSomeThreadEmptyingQueues = new AtomicBoolean(false);

            this.approximationTime = new AtomicLong(0);
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
            this.numRemainingTasks.incrementAndGet();
            if (PRINT_DIAGNOSTICS) {
                if (delta == null) {
                    this.totalStmtAndCtxtNoDeltaTasks.incrementAndGet();
                }
                else {
                    this.totalStmtAndCtxtWithDeltaTasks.incrementAndGet();
                }
            }
            exec.execute(new RunnablePointsToTask(new StmtAndContextTask(sac, delta)));
        }

        public void submitMethodReachabilityRecomputeTask(/*Set<OrderedPair<IMethod,Context>>*/MutableIntSet toRecompute) {
            this.numRemainingTasks.incrementAndGet();
            exec.execute(new RunnablePointsToTask(new MethodReachabilityRecomputeTask(toRecompute)));
        }

        public void submitTask(ProgramPointSubQuery sq) {
            // make sure we add it to the current pending set,
            // by looping until we know that we added it to the set that is in the reference.
            Set<ProgramPointSubQuery> s;
            do {
                s = this.pendingPPSubQuery.get();
                s.add(sq);
            } while (this.pendingPPSubQuery.get() != s);
        }

        public void submitTask(AddNonMostRecentOrigin task) {
            // make sure we add it to the current pending set,
            // by looping until we know that we added it to the set that is in the reference.
            Set<AddNonMostRecentOrigin> s;
            do {
                s = this.pendingAddNonMostRecentOrigin.get();
                s.add(task);
            } while (this.pendingAddNonMostRecentOrigin.get() != s);
        }


        public void submitTask(AddToSetOrigin task) {
            // make sure we add it to the current pending set,
            // by looping until we know that we added it to the set that is in the reference.
            Set<AddToSetOrigin> s;
            do {
                s = this.pendingAddToSetOrigin.get();
                s.add(task);
            } while (this.pendingAddToSetOrigin.get() != s);
        }

        public void finishedTask() {
            // Don't decrement the number of remaining tasks until the end,
            // to avoid a possible race where the exec service gets shutdown
            // while there are still pending tasks that haven't been
            // added to the service.

            // Check if we should add any pending tasks.
            int bound = (int) (exec.getParallelism() * 1.5);
            if (this.numRemainingTasks.get() <= bound) {
                // the number of remaining tasks is below the threshold, so possibly we want to empty
                // the pending queues.
                boolean otherThreadAdding = this.isSomeThreadEmptyingQueues.getAndSet(true);
                if (!otherThreadAdding) {
                    checkPendingQueues(bound);

                    // we were the thread designated to do the adding
                    boolean b = this.isSomeThreadEmptyingQueues.getAndSet(false);
                    assert b : "Concurrency protocol went awry...";
                }
            }
            // Now that we have finished adding pending tasks,
            // decrement the number of remaining tasks, and see if we are
            // ready to shutdown.
            if (this.numRemainingTasks.decrementAndGet() == 0) {
                // this was the last task. Check if there's anything in the pending queues.
                if (!checkPendingQueues(bound)) {
                    // we have finished!
                    // Notify anyone that was waiting.
                    synchronized (this) {
                        this.notifyAll();
                    }
                }
            }
        }

        /**
         * Check if the number of remaining tasks is less than the bound, and if so, add tasks from the pending queues,
         * in the appropriate priority order.
         *
         * @return whether we added anything
         */
        private boolean checkPendingQueues(int bound) {
            while (!containsPending() && isWorkToFinish()) {
                // There are no tasks left to process approximate any remaining call-sites and field assignments with no targets
                if (PRINT_QUEUE_SWAPS) {
                    printDiagnostics();
                }
                printQueueSwap("approximating");
                long start = 0L;
                if (PRINT_DIAGNOSTICS) {
                    start = System.currentTimeMillis();
                }
                ApproximateCallSitesAndFieldAssignments approx = g.ppReach.getApproximateCallSitesAndFieldAssigns();
                int numApproximated = approx.checkAndApproximateCallSitesAndFieldAssignments();
                if (PRINT_DIAGNOSTICS) {
                    approximatedCallSites = approx.numApproximatedCallSites();
                    approximatedFieldAssigns = approx.numApproximatedFieldAssigns();
                    approximationTime.addAndGet(System.currentTimeMillis() - start);
                }
                if (numApproximated == 0) {
                    approximationFinished = true;
                }
                System.err.println("in checkPendingQueues: " + numApproximated + " and approx finished: "
                        + approximationFinished
                        + ". ContainsPending: " + containsPending() + " isWorkToFinish:" + isWorkToFinish());
                printDiagnostics();
            }


            boolean changed = false;
            if (this.numRemainingTasks.get() <= bound) {
                if (PRINT_QUEUE_SWAPS) {
                    printDiagnostics();
                }
                printQueueSwap("executePendingAddToSetOrigin");
                changed |= executePendingAddToSetOrigin();
                if (this.numRemainingTasks.get() <= bound) {
                    printQueueSwap("executePendingAddNonMostRecentOrigin");
                    changed |= executePendingAddNonMostRecentOrigin();
                    if (this.numRemainingTasks.get() <= bound) {
                        printQueueSwap("executePendingPPSubQuery");
                        changed |= executePendingPPSubQuery();
                    }
                }
            }
            if (PRINT_QUEUE_SWAPS) {
                printDiagnostics();
            }
            return changed;
        }

        /**
         * If the PRINT_QUEUE_SWAPS constant is true then indicate the queue swap to the given queue
         *
         * @param s queue name
         */
        private void printQueueSwap(String s) {
            if (PRINT_QUEUE_SWAPS) {
                System.err.println((System.currentTimeMillis() - startTime) / 1000.0 + " *********** " + s);
            }
        }

        private boolean executePendingAddToSetOrigin() {
            Set<AddToSetOrigin> s = pendingAddToSetOrigin.getAndSet(AnalysisUtil.<AddToSetOrigin> createConcurrentSet());
            boolean changed = false;
            for (AddToSetOrigin t : s) {
                changed = true;
                this.numRemainingTasks.incrementAndGet();
                if (PRINT_DIAGNOSTICS) {
                    this.totalAddToSetTasks.incrementAndGet();
                }
                exec.execute(new RunnablePointsToTask(t));
            }
            return changed;
        }

        private boolean executePendingAddNonMostRecentOrigin() {
            Set<AddNonMostRecentOrigin> s = pendingAddNonMostRecentOrigin.getAndSet(AnalysisUtil.<AddNonMostRecentOrigin> createConcurrentSet());
            boolean changed = false;
            for (AddNonMostRecentOrigin t : s) {
                changed = true;
                this.numRemainingTasks.incrementAndGet();
                if (PRINT_DIAGNOSTICS) {
                    this.totalAddNonMostRecentOriginTasks.incrementAndGet();
                }
                exec.execute(new RunnablePointsToTask(t));
            }
            return changed;
        }

        private boolean executePendingPPSubQuery() {
            Set<ProgramPointSubQuery> s = pendingPPSubQuery.getAndSet(AnalysisUtil.<ProgramPointSubQuery> createConcurrentSet());
            boolean changed = false;
            for (ProgramPointSubQuery sq : s) {
                changed = true;
                this.numRemainingTasks.incrementAndGet();
                if (PRINT_DIAGNOSTICS) {
                    this.totalPPSubQueryTasks.incrementAndGet();
                }
                exec.execute(new RunnablePointsToTask(new PPSubQueryTask(sq)));
            }
            return changed;
        }


        public boolean containsPending() {
            return numRemainingTasks.get() > 0 || !pendingAddNonMostRecentOrigin.get().isEmpty()
                    || !pendingAddToSetOrigin.get().isEmpty() || !pendingPPSubQuery.get().isEmpty();
        }

        public boolean isWorkToFinish() {
            return containsPending() || !approximationFinished;
        }

        public long numRemainingTasks() {
            return numRemainingTasks.get();
        }

        public void waitUntilAllFinished() {
            if (this.isWorkToFinish()) {
                synchronized (this) {
                    try {
                        // XXX could add timeout stuff here
                        this.wait(100000);
                        printDiagnostics();
                    }
                    catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        private void printDiagnostics() {
            if (PRINT_DIAGNOSTICS) {
                StringBuilder sb = new StringBuilder();
                sb.append((System.currentTimeMillis() - startTime) / 1000.0 + " numRemainingTasks: "
                        + numRemainingTasks.get() + "; pendingAddToSetOrigin: " + pendingAddToSetOrigin.get().size()
                        + "; pendingAddNonMostRecentOrigin: " + pendingAddNonMostRecentOrigin.get().size()
                        + "; pendingPPSubQuery: " + pendingPPSubQuery.get().size() + "; approximationTime: "
                        + approximationTime.get() / 1000.0 + "; numExpired: " + g.ppReach.numExpired
                        + "; approxCGNodes: " + g.getApproxCallGraphSize() + "\n");
                sb.append("\ttotalStmtAndCtxtNoDeltaTasks: " + totalStmtAndCtxtNoDeltaTasks.get()
                        + "; totalStmtAndCtxtWithDeltaTasks: " + totalStmtAndCtxtWithDeltaTasks.get()
                        + "; totalAddNonMostRecentOriginTasks: " + totalAddNonMostRecentOriginTasks.get()
                        + "; totalAddToSetTasks: " + totalAddToSetTasks.get() + "; totalPPSubQueryTasks: "
                        + totalPPSubQueryTasks.get() + "; approximatedCallSites: " + approximatedCallSites
                        + "; approximatedFieldAssigns: " + approximatedFieldAssigns);
                System.err.println(sb.toString());
                g.ppReach.printDiagnostics();
            }
        }

        public class RunnablePointsToTask implements Runnable {
            final PointsToTask t;

            RunnablePointsToTask(PointsToTask t) {
                this.t = t;
            }

            @SuppressWarnings("synthetic-access")
            @Override
            public void run() {
                try {
                    t.process(PointsToAnalysisMultiThreaded.this.analysisHandle);
                    ExecutorServiceCounter.this.finishedTask();
                }
                catch (ConcurrentModificationException e) {
                    System.err.println("ConcurrentModificationException!!!");
                    e.printStackTrace();
                    System.exit(0);
                    // No seriously DIE!
                    Runtime.getRuntime().halt(0);
                }
                catch (Throwable t) {
                    System.err.println("Exception " + t);
                    t.printStackTrace();
                    System.exit(0);
                    // No seriously DIE!
                    Runtime.getRuntime().halt(0);
                }
            }

        }

        public class StmtAndContextTask implements PointsToTask {
            private final StmtAndContext sac;
            private final GraphDelta delta;

            public StmtAndContextTask(StmtAndContext stmtAndContext, GraphDelta delta) {
                this.sac = stmtAndContext;
                this.delta = delta;
            }

            @Override
            public void process(PointsToAnalysisHandle analysisHandle) {
                processSaC(sac, delta, ExecutorServiceCounter.this);
            }

        }

        public class MethodReachabilityRecomputeTask implements PointsToTask {
            private final MutableIntSet toRecompute;
            public MethodReachabilityRecomputeTask(MutableIntSet toRecompute) {
                this.toRecompute = toRecompute;
            }

            @Override
            public void process(PointsToAnalysisHandle analysisHandle) {
                analysisHandle.pointsToGraph().ppReach.processMethodReachabilityRecomputation(toRecompute);
            }

        }

        public class PPSubQueryTask implements PointsToTask {
            private final ProgramPointSubQuery sq;

            public PPSubQueryTask(ProgramPointSubQuery sq) {
                this.sq = sq;
            }

            @Override
            public void process(PointsToAnalysisHandle analysisHandle) {
                analysisHandle.pointsToGraph().ppReach.processSubQuery(sq);
            }

        }
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

    /**
     * An allocation dependency from InstanceKeyRecency ikr to a depender exists when a modification to the program
     * points set that allocate ikr requires reevaluation of sac.
     *
     * @param ikr instance key to get dependencies for
     * @return set of dependencies consisting of a points-to statement and an action to invoke when a new allocation
     *         occurs
     */
    private Set<AllocationDepender> getAllocationDependencies(/*InstanceKeyRecency*/int ikr) {
        Set<AllocationDepender> sacs = this.allocationDepedencies.get(ikr);
        if (sacs == null) {
            return Collections.emptySet();
        }
        return sacs;
    }

    /**
     * Record that depender needs to be notified if there is a new allocation site for ikr
     */
    boolean addAllocationDependency(/*InstanceKeyRecency*/int ikr, AllocationDepender depender) {
        // use double checked approach...

        Set<AllocationDepender> s = this.allocationDepedencies.get(ikr);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<AllocationDepender> existing = this.allocationDepedencies.putIfAbsent(ikr, s);
            if (existing != null) {
                s = existing;
            }
        }
        return s.add(depender);
    }

    public class PointsToAnalysisHandleImpl implements PointsToAnalysisHandle {
        PointsToGraph g;
        ExecutorServiceCounter execService;

        public PointsToAnalysisHandleImpl(ExecutorServiceCounter execService) {
            this.execService = execService;
        }
        void setPointsToGraph(PointsToGraph g) {
            this.g = g;
        }

        @Override
        public void submitStmtAndContext(StmtAndContext sac) {
            execService.submitTask(sac);
        }

        @Override
        public void submitStmtAndContext(StmtAndContext sac, GraphDelta delta) {
            execService.submitTask(sac, delta);
        }

        @Override
        public void submitMethodReachabilityRecomputation(MutableIntSet toRecompute) {
            execService.submitMethodReachabilityRecomputeTask(toRecompute);
        }

        @Override
        public void submitAddNonMostRecentTask(AddNonMostRecentOrigin task) {
            if (DELAY_OTHER_TASKS) {
                execService.submitTask(task);
            }
            else {
                task.process(analysisHandle);
            }
        }

        @Override
        public void submitAddToSetTask(AddToSetOrigin task) {
            if (DELAY_OTHER_TASKS) {
                execService.submitTask(task);
            }
            else {
                task.process(analysisHandle);
            }
        }

        @Override
        public void submitReachabilityQuery(ProgramPointSubQuery sq) {
            if (DELAY_OTHER_TASKS) {
                execService.submitTask(sq);
            }
            else {
                g.ppReach.processSubQuery(sq);
            }
        }

        @Override
        public PointsToGraph pointsToGraph() {
            return g;
        }

        @Override
        public void handleChanges(GraphDelta changes) {
            PointsToAnalysisMultiThreaded.this.handleChanges(changes, execService);
        }


        @Override
        public int numThreads() {
            return numThreads;
        }
    }
}
