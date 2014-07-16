package analysis.pointer.engine;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;

import util.Histogram;
import util.intmap.DenseIntMap;
import util.intmap.IntMap;
import util.intmap.SparseIntMap;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.PointsToStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

/**
 * Single-threaded implementation of a points-to graph solver. Given a set of
 * constraints, {@link PointsToStatement}s, compute the fixed point.
 */
public class PointsToAnalysisSingleThreadedSCCSorted extends PointsToAnalysis {

    /**
     * If true then a debug pass will be run after the analysis reaches a fixed
     * point
     */
    public static boolean DEBUG_SOLVED = false;

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
    public PointsToAnalysisSingleThreadedSCCSorted(HeapAbstractionFactory haf) {
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
        PointsToGraph g = new PointsToGraph(registrar, this.haf);

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

    /**
     * A placeholder object to indicate that we should process a statement without a GraphDelta.
     */
    private static final GraphDelta NO_DELTA = new GraphDelta(null);

    /**
     * Generate a points-to graph by tracking dependencies and only analyzing statements that are reachable from the
     * entry point
     *
     * @param registrar points-to statement registrar
     * @param registerOnline Whether to generate points-to statements during the points-to analysis, otherwise the
     *            registrar will already be populated
     * @return Points-to graph
     */
    public PointsToGraph solveSmarter(StatementRegistrar registrar,
                                      boolean registerOnline) {
        PointsToGraph g = new PointsToGraph(registrar, this.haf);
        System.err.println("Starting points to engine using " + this.haf);
        this.startTime = System.currentTimeMillis();
        this.nextMilestone = this.startTime - 1;

        Queue<StmtAndContext> queue = new SCCSortQueue();
        Map<StmtAndContext, GraphDelta> deltas = new HashMap<>();

        // Add initial contexts
        for (IMethod m : registrar.getInitialContextMethods()) {
            for (PointsToStatement s : registrar.getStatementsForMethod(m)) {
                for (Context c : g.getContexts(s.getMethod())) {
                    StmtAndContext sac = new StmtAndContext(s, c);
                    queue.add(sac);
                    deltas.put(sac, NO_DELTA);
                }
            }
        }

        this.lastTime = this.startTime;
        Set<StmtAndContext> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            StmtAndContext sac = queue.poll();
            GraphDelta delta = deltas.remove(sac);
            if (delta == NO_DELTA) {
                delta = null;
            }
            this.processSaC(sac, delta, g, registrar, queue, deltas, registerOnline);
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
        System.err.println("   Num with that had existing delta in queue " + (this.existingDelta));
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
            for (Integer vals : virtCalls.values()) {
                h.record(vals);
            }
            System.err.println("Histogram of number of time VirtualMethodCallStatements were re-executed: \n "
                    + h);
        }
        System.err.println("   Finding more cycles...");
        g.findCycles();
        System.err.println("   Cycles now removed " + g.cycleRemovalCount()
                           + " nodes");

        this.processAllStatements(g, registrar);
        if (outputLevel >= 5) {
            System.err.println("****************************** CHECKING ******************************");
            PointsToGraph.DEBUG = true;
            DEBUG_SOLVED = true;
            this.processAllStatements(g, registrar);
        }
        return g;
    }

    int numProcessed = 0;
    int lastNumProcessed = 0;
    int numNoDeltaProcessed = 0;
    int lastNumNoDeltaProcessed = 0;
    int processedWithNoChange = 0;
    int existingDelta = 0;
    long registrationTime = 0;
    long topoSortTime = 0;
    long nextMilestone;
    long lastTime;
    long startTime;
    Map<String, Map<StmtAndContext, Integer>> counts = new HashMap<>();
    private void processSaC(StmtAndContext sac,
                            GraphDelta delta,
                            PointsToGraph g,
                            StatementRegistrar registrar,
                            Queue<StmtAndContext> queue,
 Map<StmtAndContext, GraphDelta> deltas,
                            boolean registerOnline) {
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
        GraphDelta changed = s.process(c, this.haf, g, delta, registrar);

        // Get the changes from the graph
        Map<IMethod, Set<Context>> newContexts = g.getAndClearNewContexts();
        IntSet readNodes = g.getAndClearReadNodes();
        IntSet newlyCombinedNodes = g.getAndClearNewlyCollapsedNodes();

        // Add new contexts
        for (IMethod m : newContexts.keySet()) {
            if (registerOnline) {
                // Add statements for the given method to the registrar
                long start = System.currentTimeMillis();
                registrar.registerMethod(m);
                long end = System.currentTimeMillis();
                this.registrationTime += end - start;
            }

            for (PointsToStatement stmt : registrar.getStatementsForMethod(m)) {
                for (Context context : newContexts.get(m)) {
                    StmtAndContext newSaC = new StmtAndContext(stmt, context);
                    deltas.put(newSaC, NO_DELTA);
                    queue.add(newSaC);
                }
            }
        }

        // Now add the dependencies.

        // Read nodes are nodes that the current statement depends on
        IntIterator iter = readNodes.intIterator();
        while (iter.hasNext()) {
            int n = iter.next();
            this.addInterestingDependency(n, sac);
        }

        // now update the interesting dependencies.
        iter = newlyCombinedNodes.intIterator();
        while (iter.hasNext()) {
            int n = iter.next();
            Set<StmtAndContext> deps = this.interestingDepedencies.remove(n);
            if (deps != null) {
                for (StmtAndContext depSac : deps) {
                    this.addInterestingDependency(g.getRepresentative(n),
                                                  depSac);
                }
            }
        }

        if (changed.isEmpty()) {
            this.processedWithNoChange++;
        }
        this.handleChanges(queue, deltas, changed, g);

        long currTime = System.currentTimeMillis();
        if (currTime > this.nextMilestone) {
            do {
                this.nextMilestone = this.nextMilestone + 1000 * 30; // 30
                // seconds
            } while (currTime > this.nextMilestone);

            System.err.println("PROCESSED: " + this.numProcessed + " in "
                    + (currTime - this.startTime) / 1000 + "s;  graph="
                    + g.getBaseNodes().size() + " base nodes; cycles removed "
 + g.cycleRemovalCount()
                    + " nodes ; queue=" + queue.size() + " ("
                    + (this.numProcessed - this.lastNumProcessed) + " in "
                    + (currTime - this.lastTime) / 1000 + "s) existingDelta: " + this.existingDelta);
            this.lastTime = currTime;
            this.lastNumProcessed = this.numProcessed;
            this.lastNumNoDeltaProcessed = this.numNoDeltaProcessed;
            this.processedWithNoChange = 0;
        }
    }

    private void handleChanges(Queue<StmtAndContext> queue, Map<StmtAndContext, GraphDelta> deltas,
                               GraphDelta changes, PointsToGraph g) {
        if (changes.isEmpty()) {
            return;
        }
        IntIterator iter = changes.domainIterator();
        while (iter.hasNext()) {
            int n = iter.next();
            for (StmtAndContext sac : this.getInterestingDependencies(n)) {
                if (deltas.containsKey(sac)) {
                    // the statement is already in the queue.
                    existingDelta++;
                    GraphDelta existing = deltas.get(sac);
                    if (existing == NO_DELTA) {
                        // it's already doing everything, don't touch it
                    }
                    else {
                        // Combine the graph deltas
                        //GraphDelta.merge(changes, existing);
                        throw new RuntimeException("Merge not currently supported");
                    }
                }
                else {
                    // statement is not already in the queue
                    deltas.put(sac, changes);
                    queue.add(sac);
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
    private boolean processAllStatements(PointsToGraph g,
                                         StatementRegistrar registrar) {
        boolean changed = false;
        System.err.println("Processing all statements for good luck: "
                + registrar.size());
        int failcount = 0;
        for (IMethod m : registrar.getRegisteredMethods()) {
            for (PointsToStatement s : registrar.getStatementsForMethod(m)) {
                for (Context c : g.getContexts(s.getMethod())) {
                    GraphDelta d = s.process(c, this.haf, g, null, registrar);
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
    private boolean addInterestingDependency(/*PointsToGraphNode*/int n,
                                             StmtAndContext sac) {
        Set<StmtAndContext> s = this.interestingDepedencies.get(n);
        if (s == null) {
            s = new HashSet<>();
            this.interestingDepedencies.put(n, s);
        }
        return s.add(sac);
    }



    /**
     *
     */
    class SCCSortQueue extends AbstractQueue<StmtAndContext> {
        MutableIntSet current;
        //        PriorityQueue<StmtAndContext> current;
        Set<StmtAndContext> next = new HashSet<>();
        Map<Object, Set<StmtAndContext>> readDependencies = new HashMap<>();

        Map<StmtAndContext, Integer> indexMap = new HashMap<>();

        IntMap<StmtAndContext> indexReverseMap = new DenseIntMap<>();

        SCCSortQueue() {
            Comparator<StmtAndContext> cmp = new Comparator<StmtAndContext>() {

                @Override
                public int compare(StmtAndContext o1, StmtAndContext o2) {
                    int i1 = indexMap.get(o1);
                    int i2 = indexMap.get(o2);
                    if (i1 < i2) {
                        return 1;
                    }
                    if (i1 > i2) {
                        return -1;
                    }
                    return 0;
                }
            };
            this.current = MutableSparseIntSet.createMutableSparseIntSet(10000);
            //            this.current = new PriorityQueue<>(10000, cmp);
        }
        @Override
        public boolean offer(StmtAndContext sac) {
            if (indexMap.containsKey(sac)) {
                current.add(indexMap.get(sac));
                //current.add(sac);
            }
            else {
                this.next.add(sac);
                // register it
                for (Object read : sac.getReadDependencies(PointsToAnalysisSingleThreadedSCCSorted.this.haf)) {
                    Set<StmtAndContext> set = this.readDependencies.get(read);
                    if (set == null) {
                        set = new HashSet<>();
                        this.readDependencies.put(read, set);
                    }
                    set.add(sac);
                }
            }
            return true;
        }

        @Override
        public StmtAndContext poll() {
            if (this.current.isEmpty()/* || this.next.size() > 2500*/) {
                this.sccSortNextIntoCurrent();
            }
            int max = this.current.max();
            this.current.remove(max);
            return indexReverseMap.get(max);
            //            return current.poll();
        }

        Set<StmtAndContext> visitingSet;
        Stack<StmtAndContext> visiting;


        /*
         * Topo sort the array
         */
        private void sccSortNextIntoCurrent() {
            long start = System.currentTimeMillis();
            //            this.next.addAll(current);
            this.current.foreach(new IntSetAction() {

                @Override
                public void act(int x) {
                    next.add(indexReverseMap.get(x));
                }
            });
            this.current.clear();

            // reset the index counter.
            this.index = 0;
            this.indexMap.clear();
            this.indexReverseMap = new DenseIntMap<>();
            this.visitingSet = new HashSet<>();
            this.visiting = new Stack<>();
            for (StmtAndContext sac : this.next) {
                if (!this.indexMap.containsKey(sac)) {
                    this.visit(sac);
                }
            }

            this.next.clear();
            this.visitingSet = null;
            this.visiting = null;

            long end = System.currentTimeMillis();
            PointsToAnalysisSingleThreadedSCCSorted.this.topoSortTime += end - start;

        }

        int index;

        private int visit(StmtAndContext sac) {
            int sacIndex = this.index;
            int sacLowLink = this.index;
            this.index++;

            this.indexMap.put(sac, sacIndex);
            this.indexReverseMap.put(sacIndex, sac);
            this.visitingSet.add(sac);
            this.visiting.push(sac);

            for (StmtAndContext w : this.children(sac)) {
                Integer childIndex = this.indexMap.get(w);
                if (childIndex == null) {
                    // successor child has not yet been visited. Recurse
                    int childLowLink = this.visit(w);
                    sacLowLink = Math.min(sacLowLink, childLowLink);

                }
                else if (this.visitingSet.contains(w)) {
                    // child is in the stack, and hence in the current SCC
                    sacLowLink = Math.min(sacLowLink, childIndex);
                }
            }

            // If sac is a root node, pop the stack and generate an SCC
            if (sacIndex == sacLowLink) {
                // new strongly connected component!
                StmtAndContext w;
                do {
                    w = this.visiting.pop();
                    this.visitingSet.remove(w);
                    if (this.next.contains(w)) {
                        assert this.indexMap.containsKey(w);
                        //current.add(w);
                        current.add(indexMap.get(w));
                    }
                } while (w != sac);
            }
            return sacLowLink;
        }

        private Collection<StmtAndContext> children(StmtAndContext sac) {
            // The children of sac are the StmtAndContexts that read a variable
            // that sac writes.
            HashSet<StmtAndContext> set = new HashSet<>();
            for (Object written : sac.getWriteDependencies(PointsToAnalysisSingleThreadedSCCSorted.this.haf)) {
                Set<StmtAndContext> v = this.readDependencies.get(written);
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
            return this.current.size() + this.next.size();
        }

        @Override
        public boolean isEmpty() {
            return this.current.isEmpty() && this.next.isEmpty();
        }
    }

}
