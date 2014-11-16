package analysis.pointer.engine;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import util.Histogram;
import util.OrderedPair;
import util.intmap.IntMap;
import util.intmap.SparseIntMap;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.StatementRegistrar.StatementListener;
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

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.shrikeBT.IInstruction;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.util.intset.IntIterator;

/**
 * Single-threaded implementation of a points-to graph solver. Given a set of
 * constraints, {@link PointsToStatement}s, compute the fixed point.
 */
public class PointsToAnalysisSingleThreaded extends PointsToAnalysis {

    /**
     * An interesting dependency from node n to StmtAndContext sac exists when a
     * modification to the pointstoset of n (i.e., if n changes to point to more
     * things) requires reevaluation of sac. Many dependencies are just copy
     * dependencies (which are not interesting dependencies).
     */
    private IntMap<Set<StmtAndContext>> interestingDepedencies = new SparseIntMap<>();

    /**
     * New pointer analysis engine
     *
     * @param haf Abstraction factory for this points-to analysis
     */
    public PointsToAnalysisSingleThreaded(HeapAbstractionFactory haf) {
        super(haf);
    }

    @Override
    public PointsToGraph solve(StatementRegistrar registrar) {
        return this.solveSmarter(registrar, false);
    }

    @Override
    public PointsToGraph solveAndRegister(StatementRegistrar onlineRegistrar) {
        onlineRegistrar.registerMethod(AnalysisUtil.getFakeRoot());
        return this.solveSmarter(onlineRegistrar, true);
    }

    /**
     * Slow naive implementation suitable for testing
     *
     * @param registrar Point-to statement registrar
     *
     * @return Points-to graph
     */
    @Deprecated
    public PointsToGraph solveSimple(StatementRegistrar registrar) {
        PointsToGraph g = new PointsToGraph(registrar, this.haf, null);

        boolean changed = true;
        int count = 0;
        while (changed) {
            changed = this.processAllStatements(g, registrar);
            count++;
            if (count % 100 == 0) {
                break;
            }
        }
        System.err.println(count + " iterations ");
        return g;
    }

    private Set<IMethod> printed = new HashSet<>();
    public long lines2 = 0;
    public long instructions = 0;

    /**
     * Generate a points-to graph by tracking dependencies and only analyzing statements that are reachable from the
     * entry point
     *
     * @param registerOnline
     * @param registrar
     *
     * @param registrar points-to statement registrar
     * @param registerOnline Whether to generate points-to statements during the points-to analysis, otherwise the
     *            registrar will already be populated
     * @return Points-to graph
     */
    public PointsToGraph solveSmarter(final StatementRegistrar registrar, final boolean registerOnline) {
        System.err.println("Starting points to engine using " + this.haf);
        this.startTime = System.currentTimeMillis();
        this.nextMilestone = this.startTime - 1;

        Queue<OrderedPair<StmtAndContext, GraphDelta>> currentQueue = Collections.asLifoQueue(new ArrayDeque<OrderedPair<StmtAndContext, GraphDelta>>());
        Queue<OrderedPair<StmtAndContext, GraphDelta>> nextQueue = Collections.asLifoQueue(new ArrayDeque<OrderedPair<StmtAndContext, GraphDelta>>());
        //Queue<StmtAndContext> noDeltaQueue = new SCCSortQueue();
        final Queue<StmtAndContext> noDeltaQueue = new PartitionedQueue();
        //        Queue<StmtAndContext> noDeltaQueue = Collections.asLifoQueue(new ArrayDeque<StmtAndContext>());

        DependencyRecorder depRecorder = new DependencyRecorder() {

            @Override
            public void recordRead(int n, StmtAndContext sac) {
                PointsToAnalysisSingleThreaded.this.addInterestingDependency(n, sac);
            }

            @Override
            public void startCollapseNode(int n, int rep) {
                // add the new dependencies.
                Set<StmtAndContext> deps = PointsToAnalysisSingleThreaded.this.interestingDepedencies.get(n);
                if (deps != null) {
                    for (StmtAndContext depSac : deps) {
                        PointsToAnalysisSingleThreaded.this.addInterestingDependency(rep, depSac);
                    }
                }
            }

            @Override
            public void finishCollapseNode(int n, int rep) {
                // remove the old dependency.
                Set<StmtAndContext> deps = PointsToAnalysisSingleThreaded.this.interestingDepedencies.get(n);
                if (deps != null) {
                    PointsToAnalysisSingleThreaded.this.interestingDepedencies.remove(n);
                }
            }

            @Override
            public void recordNewContext(IMethod callee, Context calleeContext) {
                if (registerOnline) {
                    // Add statements for the given method to the registrar
                    long start = System.currentTimeMillis();
                    registrar.registerMethod(callee);
                    long end = System.currentTimeMillis();
                    PointsToAnalysisSingleThreaded.this.registrationTime += end - start;
                }

                updateLineCounter(callee);

                for (PointsToStatement stmt : registrar.getStatementsForMethod(callee)) {
                    StmtAndContext newSaC = new StmtAndContext(stmt, calleeContext);
                    noDeltaQueue.add(newSaC);
                }
            }

            private void updateLineCounter(IMethod m) {
                if (printed.add(m)) {
                    if (!m.isNative() && m instanceof IBytecodeMethod && !AnalysisUtil.hasSignature(m)) {
                        IBytecodeMethod bm = (IBytecodeMethod) m;
                        try {
                            IInstruction[] ins = bm.getInstructions();
                            instructions += ins.length;
                            if (ins.length > 0) {
                                int first = bm.getLineNumber(bm.getBytecodeIndex(0));
                                int last = bm.getLineNumber(bm.getBytecodeIndex(ins.length - 1));
                                // Line numbers (add one for the method declaration)
                                lines2++;
                                for (int j = 0; j < ins.length; j++) {
                                    int index = bm.getLineNumber(bm.getBytecodeIndex(j));
                                    if (index < first) {
                                        first = index;
                                    }
                                    if (index > last) {
                                        last = index;
                                    }
                                }
                                if (last > 0) {
                                    lines2 += last - first + 2;
                                }
                            }
                            else {
                                System.out.print("(0, 1)");
                                lines2++;
                            }
                        }
                        catch (InvalidClassFileException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    else {
                        lines2++;
                    }
                }
            }
        };

        PointsToGraph g = new PointsToGraph(registrar, this.haf, depRecorder);
        // Add initial contexts
        for (IMethod m : registrar.getInitialContextMethods()) {
            for (PointsToStatement s : registrar.getStatementsForMethod(m)) {
                for (Context c : g.getContexts(s.getMethod())) {
                    StmtAndContext sac = new StmtAndContext(s, c);
                    noDeltaQueue.add(sac);
                }
            }
        }

        if (registerOnline) {
            StatementListener stmtListener = new StatementListener() {

                @Override
                public void newStatement(PointsToStatement stmt) {
                    if (stmt.getMethod().equals(registrar.getEntryPoint())) {
                        // it's a new special instruction. Let's make sure it gets evaluated.
                        noDeltaQueue.add(new StmtAndContext(stmt, haf.initialContext()));
                    }

                }

            };
            registrar.setStatementListener(stmtListener);
        }

        this.lastTime = this.startTime;
        Set<StmtAndContext> visited = new HashSet<>();
        while (!currentQueue.isEmpty() || !nextQueue.isEmpty() || !noDeltaQueue.isEmpty()) {
            if (currentQueue.isEmpty()) {
                Queue<OrderedPair<StmtAndContext, GraphDelta>> t = nextQueue;
                nextQueue = currentQueue;
                currentQueue = t;
            }
            StmtAndContext sac;
            GraphDelta delta;
            if (currentQueue.isEmpty()) {
                sac = noDeltaQueue.poll();
                delta = null;
            }
            else {
                OrderedPair<StmtAndContext, GraphDelta> sacd = currentQueue.poll();
                sac = sacd.fst();
                delta = sacd.snd();
            }
            this.processSaC(sac, delta, g, registrar, currentQueue, nextQueue, noDeltaQueue);
        }

        long endTime = System.currentTimeMillis();
        System.err.println("Processed " + this.numProcessed
                           + " (statement, context) pairs"
                           + (outputLevel >= 1 ? " (" + visited.size() + " unique)" : ""));
        long totalTime = endTime - this.startTime;
        System.err.println("   Total time       : " + totalTime / 1000 + "s.");
        System.err.println("   Registration time: " + this.registrationTime
                           / 1000 + "s.");
        System.err.println("   => Analysis time : "
                + (totalTime - this.registrationTime) / 1000 + "s.");
        System.err.println("   Topo sort time   : " + this.topoSortTime / 1000
                           + "s.");
        System.err.println("   => Analysis time - topo sort : "
                + (totalTime - (this.registrationTime + this.topoSortTime))
                / 1000 + "s.");
        System.err.println("   Num no delta processed "
                + this.numNoDeltaProcessed);
        System.err.println("   Num with delta processed "
                + (this.numProcessed - this.numNoDeltaProcessed));
        System.err.println("   Cycles removed " + g.cycleRemovalCount()
                           + " nodes");

        System.err.println("  counts: ");
        for (String key : this.counts.keySet()) {
            int counter = 0;
            for (StmtAndContext sac: this.counts.get(key).keySet()) {
                counter += this.counts.get(key).get(sac);
                if (this.counts.get(key).get(sac) > 4000) {
                    System.err.println("        -- "
                            + this.counts.get(key).get(sac) + " : " + sac);
                }
            }
            System.err.println("      " + key + " : " + counter
                               + " sacs processed (" + this.counts.get(key).size()
                               + " distinct sacs)");
        }
        // Now do a histogram of the VirtualCalls
        {

            Map<StmtAndContext, Integer> virtCalls = this.counts.get("VirtualCallStatement");
            Histogram h = new Histogram();
            if (virtCalls != null) {
                for (Integer vals : virtCalls.values()) {
                    h.record(vals);
                }
                System.err.println("Histogram of number of time VirtualMethodCallStatements were re-executed: \n " + h);
            }
            else {
                System.err.println("No virtual calls in this application");
            }
        }
        System.err.println("   Finding more cycles...");
        g.findCycles();
        System.err.println("   Cycles now removed " + g.cycleRemovalCount()
                           + " nodes");

        //        this.processAllStatements(g, registrar);
        g.constructionFinished();
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
    Map<String, Map<StmtAndContext, Integer>> counts = new HashMap<>();

    private void processSaC(StmtAndContext sac, GraphDelta delta, PointsToGraph g, StatementRegistrar registrar,
                            Queue<OrderedPair<StmtAndContext, GraphDelta>> currentQueue,
                            Queue<OrderedPair<StmtAndContext, GraphDelta>> nextQueue, Queue<StmtAndContext> noDeltaQueue) {
        // Do some accounting for debugging/informational purposes.
        this.numProcessed++;
        if (delta == null) {
            this.numNoDeltaProcessed++;
        }
        else {
            String c = sac.stmt.getClass().getSimpleName();
            Map<StmtAndContext, Integer> stmtcnt = this.counts.get(c);
            if (stmtcnt == null) {
                stmtcnt = new HashMap<>();
                this.counts.put(c, stmtcnt);
            }
            Integer cnt = stmtcnt.get(sac);
            if (cnt == null) {
                cnt = 0;
            }
            cnt++;
            stmtcnt.put(sac, cnt);
        }

        PointsToStatement s = sac.stmt;
        Context c = sac.context;

        if (outputLevel >= 3) {
            System.err.println("\tPROCESSING: " + sac);
        }
        GraphDelta changed = s.process(c, this.haf, g, delta, registrar, sac);

        if (changed.isEmpty()) {
            this.processedWithNoChange++;
        }
        this.handleChanges(nextQueue, changed);

        long currTime = System.currentTimeMillis();
        if (currTime > this.nextMilestone) {
            do {
                this.nextMilestone = this.nextMilestone + 1000 * 30; // 30 seconds
            } while (currTime > this.nextMilestone);

            System.err.println("PROCESSED: " + this.numProcessed + " in "
 + (currTime - this.startTime) / 1000
                    + "s; number of source node " + g.numPointsToGraphNodes() + "; cycles removed "
 + g.cycleRemovalCount()
                    + " nodes ; queue=" + currentQueue.size() + "nextQueue=" + nextQueue.size() + "noDeltaQueue="
                    + noDeltaQueue.size()
                    + " ("
                    + (this.numProcessed - this.lastNumProcessed) + " in "
                    + (currTime - this.lastTime) / 1000 + "s)");
            this.lastTime = currTime;
            this.lastNumProcessed = this.numProcessed;
            this.lastNumNoDeltaProcessed = this.numNoDeltaProcessed;
            this.processedWithNoChange = 0;
        }
    }

    private void handleChanges(Queue<OrderedPair<StmtAndContext, GraphDelta>> queue, GraphDelta changes) {
        if (changes.isEmpty()) {
            return;
        }
        IntIterator iter = changes.domainIterator();
        while (iter.hasNext()) {
            int n = iter.next();
            for (StmtAndContext sac : this.getInterestingDependencies(n)) {
                queue.add(new OrderedPair<>(sac, changes));
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
    private boolean processAllStatements(PointsToGraph g,
                                         StatementRegistrar registrar) {
        boolean changed = false;
        System.err.println("Processing all statements for good luck: " + registrar.size() + " from "
                + registrar.getRegisteredMethods().size() + " methods");
        int failcount = 0;
        for (IMethod m : registrar.getRegisteredMethods()) {
            for (PointsToStatement s : registrar.getStatementsForMethod(m)) {
                for (Context c : g.getContexts(s.getMethod())) {
                    GraphDelta d = s.process(c, this.haf, g, null, registrar, new StmtAndContext(s, c));
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
     * Get any (statement,context) pairs that depend on the given points-to
     * graph node
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
     * Add the (statement, context) pair as a dependency of the points-to graph
     * node. This is an "interesting dependency", meaning that if the points to
     * set of n is modified, then sac will need to be processed again.
     *
     * @param n node the statement depends on
     * @param sac statement and context that depends on <code>n</code>
     * @return true if the dependency did not already exist
     */
    boolean addInterestingDependency(/*PointsToGraphNode*/int n,
                                             StmtAndContext sac) {
        Set<StmtAndContext> s = this.interestingDepedencies.get(n);
        if (s == null) {
            s = new HashSet<>();
            this.interestingDepedencies.put(n, s);
        }
        return s.add(sac);
    }

    static class PartitionedQueue extends AbstractQueue<StmtAndContext> {
        Queue<StmtAndContext> base =
        //new LinkedList<>();
        Collections.asLifoQueue(new ArrayDeque<StmtAndContext>());
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
            ordered.add(fieldReads);
            ordered.add(fieldWrites);
            ordered.add(base);
            ordered.add(calls);
        }

        @Override
        public boolean offer(StmtAndContext sac) {
            PointsToStatement stmt = sac.stmt;
            if (stmt instanceof NewStatement) {
                return base.offer(sac);
            }
            if (stmt instanceof CallStatement || stmt instanceof ClassInitStatement) {
                return calls.offer(sac);
            }
            if (stmt instanceof FieldToLocalStatement || stmt instanceof ArrayToLocalStatement) {
                return fieldReads.offer(sac);
            }
            if (stmt instanceof LocalToFieldStatement || stmt instanceof LocalToArrayStatement) {
                return fieldWrites.offer(sac);
            }
            if (stmt instanceof LocalToLocalStatement || stmt instanceof LocalToStaticFieldStatement
                    || stmt instanceof PhiStatement || stmt instanceof ReturnStatement
                    || stmt instanceof StaticFieldToLocalStatement || stmt instanceof ExceptionAssignmentStatement) {
                return localAssigns.offer(sac);
            }
            throw new IllegalArgumentException("Don't know how to handle a " + stmt.getClass());
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

}
