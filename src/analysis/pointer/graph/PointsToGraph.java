package analysis.pointer.graph;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import util.OrderedPair;
import util.intmap.ConcurrentIntMap;
import util.intmap.IntMap;
import util.intmap.SimpleConcurrentIntMap;
import util.intmap.SparseIntMap;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.DependencyRecorder;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.collections.EmptyIntIterator;
import com.ibm.wala.util.collections.IntStack;
import com.ibm.wala.util.intset.EmptyIntSet;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

/**
 * Graph mapping local variables (in a particular context) and fields to
 * abstract heap locations (representing zero or more actual heap locations)
 */
public class PointsToGraph {

    public static final String ARRAY_CONTENTS = "[contents]";

    /**
     * InstanceKey counter, for unique integers for InstanceKeys
     */
    private final AtomicInteger instanceKeyCounter = new AtomicInteger(0);
    /**
     * Dictionary for mapping ints to InstanceKeys.
     */
    private final ConcurrentMap<Integer, InstanceKey> instanceKeyDictionary = new ConcurrentHashMap<>();
    /**
     * Dictionary for mapping InstanceKeys to ints
     */
    private final ConcurrentMap<InstanceKey, Integer> reverseInstanceKeyDictionary = new ConcurrentHashMap<>();

    /**
     * Dictionary to record the concrete type of instance keys.
     */
    final ConcurrentMap<Integer, IClass> concreteTypeDictionary = new ConcurrentHashMap<>();

    /**
     * GraphNode counter, for unique integers for GraphNodes
     */
    private final AtomicInteger graphNodeCounter = new AtomicInteger(0);

    /**
     * Dictionary for mapping PointsToGraphNodes to ints
     */
    private final ConcurrentMap<PointsToGraphNode, Integer> reverseGraphNodeDictionary = new ConcurrentHashMap<>();


    /*
     * The pointsto graph is represented using two relations. If base.get(n).contains(i) then i is in the pointsto set
     * of n. If isSubsetOf.get(n).contains(<m, f>), then the filtered pointsto set of n is a subset of the points to set
     * of m. The superset relation is just the reverse of subset.
     *
     * Map from PointsToGraphNode to sets of InstanceKeys
     */
    private final ConcurrentIntMap<MutableIntSet> basex = new SimpleConcurrentIntMap<>();

    /**
     * Map from PointsToGraphNode to set of PointsToGraphNode
     */
    private final ConcurrentIntMap<MutableIntSet> isUnfilteredSubsetOfx = new SimpleConcurrentIntMap<>();

    /**
     * Map from PointsToGraphNode to set of PointsToGraphNode
     */
    private final ConcurrentIntMap<MutableIntSet> isUnfilteredSupersetOfx = new SimpleConcurrentIntMap<>();

    /**
     * Map from PointsToGraphNode to map from PointsToGraphNode to TypeFilter
     */
    private final ConcurrentIntMap<ConcurrentIntMap<Set<TypeFilter>>> isSubsetOfx = new SimpleConcurrentIntMap<>();

    /**
     * Map from PointsToGraphNode to map from PointsToGraphNode to TypeFilter
     */
    private final ConcurrentIntMap<ConcurrentIntMap<Set<TypeFilter>>> isSupersetOfx = new SimpleConcurrentIntMap<>();

    /**
     * Map from PointsToGraphNodes to PointsToGraphNodes
     */
    private final ConcurrentIntMap<Integer> representativex = new SimpleConcurrentIntMap<>();
    /**
     * The contexts that a method may appear in.
     */
    private final ConcurrentMap<IMethod, Set<Context>> contextsx = new ConcurrentHashMap<>();

    /**
     * The classes that will be loaded (i.e., we need to analyze their static
     * initializers).
     */
    private final Set<IMethod> classInitializersx = AnalysisUtil.createConcurrentSet();

    /**
     * Entry points added during the pointer analysis
     */
    private final Set<IMethod> entryPointsx = AnalysisUtil.createConcurrentSet();

    /**
     * Heap abstraction factory.
     */
    private final HeapAbstractionFactory haf;

    private final HafCallGraph callGraph;

    private final DependencyRecorder depRecorder;

    /*
     * A cache for realized points to sets.
     */
    RealizedSetCache cache = new RealizedSetCache();

    private int outputLevel = 0;

    public static boolean DEBUG = false;

    public PointsToGraph(StatementRegistrar registrar,
 HeapAbstractionFactory haf, DependencyRecorder depRecorder) {
        this.depRecorder = depRecorder;

        this.haf = haf;
        this.callGraph = new HafCallGraph(haf);

        this.populateInitialContexts(registrar.getInitialContextMethods());
    }

    /**
     * Populate the contexts map by adding the initial context for all the given
     * methods
     *
     * @param haf abstraction factory defining the initial context
     * @param initialMethods methods to be paired with the initial context
     * @return mapping from each method in the given set to the singleton set
     *         containing the initial context
     */
    private void populateInitialContexts(Set<IMethod> initialMethods) {
        for (IMethod m : initialMethods) {
            this.getOrCreateContextSet(m).add(this.haf.initialContext());
        }
    }

    public IntMap<MutableIntSet> getBaseNodes() {
        return this.basex;
    }

    // Return the immediate supersets of PointsToGraphNode n. That is, any node m such that n is an immediate subset of m
    public OrderedPair<IntSet, IntMap<Set<TypeFilter>>> immediateSuperSetsOf(int n) {
        n = this.getRepresentative(n);

        IntSet unfilteredsupersets = this.isUnfilteredSubsetOfx.get(n);
        IntMap<Set<TypeFilter>> supersets = this.isSubsetOfx.get(n);
        return new OrderedPair<>(unfilteredsupersets, supersets);
    }

    // Return the immediate supersets of n. That is, any node m such that n is an immediate superset of m
    // The first element of the pair returned is the IntSet of unfiltered PointsToGraphNodes,
    // and the second element is the IntMap for filtered PointsToGraphNodes.
    public OrderedPair<IntSet, IntMap<Set<TypeFilter>>> immediateSubSetsOf(/*PointsToGraphNode*/int n) {
        n = this.getRepresentative(n);
        IntSet unfilteredsubsets = this.isUnfilteredSupersetOfx.get(n);
        IntMap<Set<TypeFilter>> subsets = this.isSupersetOfx.get(n);
        return new OrderedPair<>(unfilteredsubsets, subsets);
    }


    Integer getImmediateRepresentative(/*PointsToGraphNode*/int n) {
        return this.representativex.get(n);
    }

    public/*PointsToGraphNode*/int getRepresentative(/*PointsToGraphNode*/int n) {
        int orig = n;
        int rep;
        Integer x = n;
        do {
            rep = x;
            x = this.representativex.get(x);
        } while (x != null);
        return rep;
    }

    /**
     * Add an edge from node to heapContext in the graph.
     *
     * @param node
     * @param heapContext
     * @return
     */
    public GraphDelta addEdge(PointsToGraphNode node, InstanceKey heapContext) {
        assert node != null && heapContext != null;

        int n = lookupDictionary(node);

        n = this.getRepresentative(n);

        MutableIntSet pointsToSet = this.getOrCreateBaseSet(n);

        boolean add;
        Integer h = this.reverseInstanceKeyDictionary.get(heapContext);
        if (h == null) {
            // not in the dictionary yet
            h = this.instanceKeyCounter.getAndIncrement();
            // try a put if absent
            Integer existing = this.reverseInstanceKeyDictionary.putIfAbsent(heapContext, h);
            if (existing == null) {
                this.instanceKeyDictionary.put(h, heapContext);
                this.concreteTypeDictionary.put(h, heapContext.getConcreteType());
            }
            else {
                h = existing;
            }
            add = true;
        }
        else {
            add = !pointsToSet.contains(h);
        }

        GraphDelta delta = new GraphDelta(this);
        if (add) {
            delta.add(n, h);
            pointsToSet.add(h);

            // update the cache for the changes
            this.cache.updateForDelta(delta);
        }
        return delta;
    }

    protected int lookupDictionary(PointsToGraphNode node) {
        Integer n = this.reverseGraphNodeDictionary.get(node);
        if (n == null) {
            // not in the dictionary yet
            n = graphNodeCounter.getAndIncrement();
            Integer existing = this.reverseGraphNodeDictionary.putIfAbsent(node, n);
            if (existing != null) {
                return existing;
            }
        }
        return n;
    }
    /**
     * Copy the pointsto set of the source to the pointsto set of the target.
     * This should be used when the pointsto set of the target is a supserset of
     * the pointsto set of the source.
     *
     * @param source
     * @param target
     * @return
     */
    public GraphDelta copyEdges(PointsToGraphNode source, PointsToGraphNode target) {
        int s = this.getRepresentative(lookupDictionary(source));
        int t = this.getRepresentative(lookupDictionary(target));

        if (s == t) {
            // don't bother adding
            return new GraphDelta(this);
        }
        // source is a subset of target, target is a superset of source.
        MutableIntSet sourceSubset = this.getOrCreateUnfilteredSubsetSet(s);

        GraphDelta changed = new GraphDelta(this);
        if (!sourceSubset.contains(t)) {
            // For the current design, it's important that we tell delta about the copyEdges before actually updating it.
            // XXX!@! AND I'M GOING TO BREAK THAT NOW...

            sourceSubset.add(t);
            // make sure the superset relation stays consistent
            MutableIntSet targetSuperset = this.getOrCreateUnfilteredSupersetSet(t);
            targetSuperset.add(s);

            changed.addCopyEdges(s, null, t); // XXX THIS WAS EARLIER

            // update the cache for the changes
            this.cache.updateForDelta(changed);
        }
        return changed;
    }

    /**
     * Copy the pointsto set of the source to the pointsto set of the target.
     * This should be used when the pointsto set of the target is a supserset of
     * the pointsto set of the source.
     *
     * @param source
     * @param target
     * @return
     */
    public GraphDelta copyFilteredEdges(PointsToGraphNode source,
                                        TypeFilter filter,
                                        PointsToGraphNode target) {
        // source is a subset of target, target is a subset of source.
        if (TypeFilter.IMPOSSIBLE.equals(filter)) {
            // impossible filter! Don't bother adding the relationship.
            return new GraphDelta(this);
        }

        int s = this.getRepresentative(lookupDictionary(source));
        int t = this.getRepresentative(lookupDictionary(target));

        if (s == t) {
            // don't bother adding
            return new GraphDelta(this);
        }

        IntMap<Set<TypeFilter>> sourceSubset = this.getOrCreateSubsetSet(s);

        GraphDelta changed = new GraphDelta(this);
        if (!sourceSubset.containsKey(t) || !sourceSubset.get(t).contains(filter)) {
            // For the current design, it's important that we tell delta about the copyEdges before actually updating it.
            // XXX!@! AND I'M GOING TO BREAK THAT NOW...

            addFilter(sourceSubset, t, filter);
            // make sure the superset relation stays consistent
            IntMap<Set<TypeFilter>> targetSuperset = this.getOrCreateSupersetSet(t);
            addFilter(targetSuperset, s, filter);

            changed.addCopyEdges(s, filter, t); // XXX THIS WAS EARLIER

            // update the cache for the changes
            this.cache.updateForDelta(changed);
        }
        return changed;
    }

    private static void addFilter(IntMap<Set<TypeFilter>> supersets, /*PointsToGraphNode*/int n, TypeFilter filter) {
        if (supersets.containsKey(n)) {
            supersets.get(n).add(filter);
        }
        else {
            Set<TypeFilter> set = new HashSet<>(10);
            set.add(filter);
            supersets.put(n, set);
        }
    }

    private static void addFilters(IntMap<Set<TypeFilter>> supersets, /*PointsToGraphNode*/int n,
                                   Set<TypeFilter> filters) {
        if (supersets.containsKey(n)) {
            supersets.get(n).addAll(filters);
        }
        else {
            Set<TypeFilter> set = new HashSet<>(filters.size() + 10);
            set.addAll(filters);
            supersets.put(n, set);
        }
    }

    /**
     * Provide an interatory for the things that n points to. Note that we may
     * not return a set, i.e., some InstanceKeys may be returned multiple times.
     * XXX we may change this in the future...
     *
     * @param n
     * @return
     */
    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode n, StmtAndContext origninator) {
        return new IntToInstanceKeyIterator(this.pointsToIntIterator(lookupDictionary(n), origninator));
    }

    public int graphNodeToInt(PointsToGraphNode n) {
        return lookupDictionary(n);
    }

    public IntIterator pointsToIntIterator(PointsToGraphNode n, StmtAndContext origninator) {
        return pointsToIntIterator(lookupDictionary(n), origninator);
    }
    public IntIterator pointsToIntIterator(/*PointsToGraphNode*/int n, StmtAndContext origninator) {
        n = this.getRepresentative(n);
        this.recordRead(n, origninator);
        return this.cache.getPointsToSet(n).intIterator();
    }

    /**
     * Does n point to ik?
     */
    public boolean pointsTo(/*PointsToGraphNode*/int n, int ik) {
        return this.pointsTo(n, ik, null);
    }

    /**
     * Does n point to ik?
     */
    private boolean pointsTo(/*PointsToGraphNode*/int n, int ik, MutableIntSet visited) {
        if (visited != null && visited.contains(n)) {
            return false;
        }

        IntSet s = this.cache.getPointsToSet(n);
        if (s != null) {
            return s.contains(ik);
        }

        // we don't have a cached version of the points to set. Let's try to be cunning.
        if (this.basex.containsKey(n)) {
            // we have a base node,
            if (this.basex.get(n).contains(ik)) {
                return true;
            }
        }
        if (visited == null) {
            visited = MutableSparseIntSet.makeEmpty();
        }
        visited.add(n);

        // let's try the immediate subsets of n
        OrderedPair<IntSet, IntMap<Set<TypeFilter>>> subsets = this.immediateSubSetsOf(n);
        // First go through the unfiltered subsets
        IntSet unfilteredSubsets = subsets.fst();
        IntMap<Set<TypeFilter>> filteredSubsets = subsets.snd();

        if (unfilteredSubsets != null) {
            IntIterator unfilteredSubsetsIter = unfilteredSubsets.intIterator();
            while (unfilteredSubsetsIter.hasNext()) {
                int ss = unfilteredSubsetsIter.next();
                if (this.pointsTo(ss, ik, visited)) {
                    return true;
                }
            }
        }
        // Now the filtered...
        if (filteredSubsets != null) {
            IntIterator filteredSubsetsIter = subsets.snd().keyIterator();
            while (filteredSubsetsIter.hasNext()) {
                int ss = filteredSubsetsIter.next();
                Set<TypeFilter> filters = subsets.snd().get(ss);
                if (satisfiesAny(filters, this.concreteTypeDictionary.get(ik))) {
                    if (this.pointsTo(ss, ik, visited)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean satisfiesAny(Set<TypeFilter> filters, IClass type) {
        for (TypeFilter f : filters) {
            if (f.satisfies(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * XXXX DOCO TODO.
     *
     */
    @SuppressWarnings("deprecation")
    public synchronized boolean addCall(CallSiteReference callSite, IMethod caller,
                           Context callerContext, IMethod callee,
                           Context calleeContext) {
        // XXX !@! This method is synchronized, and is possibly a bottle neck. Should do something better with the call graph.

        CGNode src;
        CGNode dst;

        try {
            src = this.callGraph.findOrCreateNode(caller, callerContext);
            dst = this.callGraph.findOrCreateNode(callee, calleeContext);
        }
        catch (CancelException e) {
            throw new RuntimeException(e + " cannot add call graph edge from "
                    + PrettyPrinter.methodString(caller) + " to "
                    + PrettyPrinter.methodString(callee));
        }

        // We are building a call graph so it is safe to call this "deprecated" method
        if (!src.addTarget(callSite, dst)) {
            // not a new target
            return false;
        }
        if (this.outputLevel >= 2) {
            System.err.println("ADDED\n\t" + PrettyPrinter.methodString(caller)
                               + " in " + callerContext + " to\n\t"
                               + PrettyPrinter.methodString(callee) + " in "
                               + calleeContext);
        }

        this.recordContext(callee, calleeContext);
        return true;
    }

    /**
     * Record a callee context for the given method
     *
     * @param callee method
     * @param calleeContext context
     */
    private void recordContext(IMethod callee, Context calleeContext) {
        if (this.outputLevel >= 1) {
            System.err.println("RECORDING: " + callee + " in " + calleeContext
                               + " hc " + calleeContext);
        }
        Set<Context> s = this.contextsx.get(callee);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<Context> existing = this.contextsx.putIfAbsent(callee, s);
            if (existing != null) {
                s = existing;
            }
        }

        if (s.add(calleeContext)) {
            // The context is new
            depRecorder.recordNewContext(callee, calleeContext);
        }
    }

    private MutableIntSet getOrCreateBaseSet(/*PointsToGraphNode*/int node) {
        MutableIntSet s = this.basex.get(node);
        if (s == null) {
            s = PointsToAnalysisMultiThreaded.makeConcurrentIntSet();
            MutableIntSet existing = this.basex.putIfAbsent(node, s);
            if (existing != null) {
                s = existing;
            }
        }
        return s;
    }

    private ConcurrentIntMap<Set<TypeFilter>> getOrCreateSubsetSet(/*PointsToGraphNode*/int node) {
        assert !this.representativex.containsKey(node);
        return getOrCreateIntMap(node, this.isSubsetOfx);
    }

    private IntMap<Set<TypeFilter>> getOrCreateSupersetSet(/*PointsToGraphNode*/int node) {
        assert !this.representativex.containsKey(node);
        return getOrCreateIntMap(node, this.isSupersetOfx);
    }

    private MutableIntSet getOrCreateUnfilteredSubsetSet(/*PointsToGraphNode*/int node) {
        assert !this.representativex.containsKey(node);
        return getOrCreateIntSet(node, this.isUnfilteredSubsetOfx);
    }

    private MutableIntSet getOrCreateUnfilteredSupersetSet(/*PointsToGraphNode*/int node) {
        assert !this.representativex.containsKey(node);
        return getOrCreateIntSet(node, this.isUnfilteredSupersetOfx);
    }

    private Set<Context> getOrCreateContextSet(IMethod callee) {
        return PointsToGraph.<IMethod, Context> getOrCreateSet(callee, this.contextsx);
    }

    static MutableIntSet getOrCreateIntSet(int key, ConcurrentIntMap<MutableIntSet> map) {
        MutableIntSet set = map.get(key);
        if (set == null) {
            set = PointsToAnalysisMultiThreaded.makeConcurrentIntSet();
            MutableIntSet ex = map.putIfAbsent(key, set);
            if (ex != null) {
                set = ex;
            }
        }
        return set;
    }

    private <T> ConcurrentIntMap<T> getOrCreateIntMap(int key, ConcurrentIntMap<ConcurrentIntMap<T>> map) {
        ConcurrentIntMap<T> set = map.get(key);
        if (set == null) {
            set = new SimpleConcurrentIntMap<>();
            ConcurrentIntMap<T> existing = map.putIfAbsent(key, set);
            if (existing != null) {
                set = existing;
            }
        }
        return set;
    }

    static <K, T> Set<T> getOrCreateSet(K key, ConcurrentMap<K, Set<T>> map) {
        Set<T> set = map.get(key);
        if (set == null) {
            // make these concurrent to avoid ConcurrentModificationExceptions
            // set = new HashSet<>();
            set = AnalysisUtil.createConcurrentSet();
            Set<T> ex = map.putIfAbsent(key, set);
            if (ex != null) {
                set = ex;
            }
        }
        return set;
    }

    /**
     * Set of contexts for the given method
     *
     * @param m method reference to get contexts for
     * @return set of contexts for the given method
     */
    public Set<Context> getContexts(IMethod m) {
        Set<Context> s = this.contextsx.get(m);
        if (s == null) {
            return Collections.<Context> emptySet();
        }
        return Collections.unmodifiableSet(this.contextsx.get(m));
    }

    //
    // /**
    // * Print the graph in graphviz dot format to a file
    // *
    // * @param filename
    // * name of the file, the file is put in tests/filename.dot
    // * @param addDate
    // * if true then the date will be added to the filename
    // */
    // public void dumpPointsToGraphToFile(String filename, boolean addDate) {
    // String dir = "tests";
    // String file = filename;
    // if (addDate) {
    // SimpleDateFormat dateFormat = new SimpleDateFormat("-yyyy-MM-dd-HH_mm_ss");
    // Date dateNow = new Date();
    // String now = dateFormat.format(dateNow);
    // file += now;
    // }
    // String fullFilename = dir + "/" + file + ".dot";
    // try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
    // dumpPointsToGraph(out);
    // System.err.println("\nDOT written to: " + fullFilename);
    // } catch (IOException e) {
    // System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
    // }
    // }
    //
    // private static String escape(String s) {
    // return s.replace("\\", "\\\\").replace("\"", "\\\"");
    // }
    //
    // private Writer dumpPointsToGraph(Writer writer) throws IOException {
    // double spread = 1.0;
    // writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
    // + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n"
    // + "edge [fontsize=10]" + ";\n");
    //
    // Map<String, Integer> dotToCount = new HashMap<>();
    // Map<PointsToGraphNode, String> n2s = new HashMap<>();
    // Map<InstanceKey, String> k2s = new HashMap<>();
    //
    // // Need to differentiate between different nodes with the same string
    // for (PointsToGraphNode n : graph.keySet()) {
    // String nStr = escape(n.toString());
    // Integer count = dotToCount.get(nStr);
    // if (count == null) {
    // dotToCount.put(nStr, 1);
    // } else {
    // dotToCount.put(nStr, count + 1);
    // nStr += " (" + count + ")";
    // }
    // n2s.put(n, nStr);
    // }
    // for (InstanceKey k : getAllHContexts()) {
    // String kStr = escape(k.toString());
    // Integer count = dotToCount.get(kStr);
    // if (count == null) {
    // dotToCount.put(kStr, 1);
    // } else {
    // dotToCount.put(kStr, count + 1);
    // kStr += " (" + count + ")";
    // }
    // k2s.put(k, kStr);
    // }
    //
    // for (PointsToGraphNode n : graph.keySet()) {
    // for (InstanceKey ik : graph.get(n)) {
    // writer.write("\t\"" + n2s.get(n) + "\" -> " + "\"" + k2s.get(ik) + "\";\n");
    // }
    // }
    //
    // writer.write("\n};\n");
    // return writer;
    // }

    // /**
    // * Set containing all Heap contexts. This is really expensive. Don't do it unless debugging small graphs.
    // *
    // * @return set with all the Heap contexts
    // */
    // public IntSet getAllHContexts() {
    // IntSet all = new HashSet<>();
    //
    // for (IntSet s : graph.values()) {
    // all.addAll(s);
    // }
    // return all;
    // }

    /**
     * Get the procedure call graph
     *
     * @return call graph
     */
    public CallGraph getCallGraph() {
        return this.callGraph;
    }

    private void recordRead(/*PointsToGraphNode*/int node, StmtAndContext sac) {
        this.depRecorder.recordRead(node, sac);
    }

    /**
     * When we have detected that the points to sets of two nodes are identical,
     * we can collapse them.
     *
     * @param n
     * @param rep
     */
    void collapseNodes(/*PointsToGraphNode*/int n, /*PointsToGraphNode*/int rep) {
        assert n != rep : "Can't collapse a node with itself";
        if (true) {
            throw new UnsupportedOperationException(); //how to do this concurrently();
        }
        // it is possible that since n and rep were registered, one or both of them were already merged.
        n = this.getRepresentative(n);
        rep = this.getRepresentative(rep);

        if (n == rep) {
            // they have already been merged.
            return;
        }

        // update the cache.
        this.cache.removed(n);

        // Notify the dependency recorder
        depRecorder.startCollapseNode(n, rep);

        // update the subset and superset graphs.
        IntSet unfilteredSubsetOf = this.isUnfilteredSubsetOfx.remove(n);
        IntSet unfilteredSupersetOf = this.isUnfilteredSupersetOfx.remove(n);

        MutableIntSet repUnfilteredSubsetOf = this.getOrCreateUnfilteredSubsetSet(rep);
        MutableIntSet repUnfilteredSupersetOf = this.getOrCreateUnfilteredSupersetSet(rep);

        if (unfilteredSubsetOf != null) {
            IntIterator iter = unfilteredSubsetOf.intIterator();
            while (iter.hasNext()) {
                int x = iter.next();
                // n is an unfiltered subset of x, so n is in the isUnfilteredSupersets of x
                MutableIntSet s = this.isUnfilteredSupersetOfx.get(x);
                if (x != rep) {
                    s.add(rep);
                    // add x to the representative's...
                    repUnfilteredSubsetOf.add(x);
                }
                s.remove(n);
            }
        }

        if (unfilteredSupersetOf != null) {
            IntIterator iter = unfilteredSupersetOf.intIterator();
            while (iter.hasNext()) {
                int x = iter.next();
                // n is an unfiltered superset of x, so n is in the isUnfilteredSubsets of x
                MutableIntSet s = this.isUnfilteredSubsetOfx.get(x);
                if (x != rep) {
                    s.add(rep);
                    // add x to the representative's...
                    repUnfilteredSupersetOf.add(x);
                }
                s.remove(n);
            }
        }

        IntMap<Set<TypeFilter>> filteredSubsetOf = this.isSubsetOfx.remove(n);
        IntMap<Set<TypeFilter>> filteredSupersetOf = this.isSupersetOfx.remove(n);

        IntMap<Set<TypeFilter>> repFilteredSubsetOf = this.getOrCreateSubsetSet(rep);
        IntMap<Set<TypeFilter>> repFilteredSupersetOf = this.getOrCreateSupersetSet(rep);

        if (filteredSubsetOf != null) {
            IntIterator iter = filteredSubsetOf.keyIterator();
            while (iter.hasNext()) {
                int m = iter.next();
                Set<TypeFilter> filters = filteredSubsetOf.get(m);
                // n is a filtered subset of m, so n is in the isSupersets of m
                IntMap<Set<TypeFilter>> s = this.isSupersetOfx.get(m);
                if (m != rep) {
                    assert !s.containsKey(rep);
                    addFilters(s, rep, filters);
                    // add x to the representative's...
                    addFilters(repFilteredSubsetOf, m, filters);
                }
                s.remove(n);
            }
        }

        if (filteredSupersetOf != null) {
            IntIterator iter = filteredSupersetOf.keyIterator();
            while (iter.hasNext()) {
                int m = iter.next();
                Set<TypeFilter> filters = filteredSupersetOf.get(m);
                // n is a filtered superset of m, so n is in the isSubsets of m
                IntMap<Set<TypeFilter>> s = this.isSubsetOfx.get(m);
                if (m != rep) {
                    addFilters(s, rep, filters);
                    // add x to the representative's...
                    addFilters(repFilteredSupersetOf, m, filters);
                }
                s.remove(n);
            }
        }

        // update the base nodes.
        assert !this.basex.containsKey(n) : "Base nodes shouldn't be collapsed with other nodes";

        this.representativex.put(n, rep);
        depRecorder.finishCollapseNode(n, rep);

    }

    public void setOutputLevel(int outputLevel) {
        this.outputLevel = outputLevel;
    }

    public int clinitCount = 0;

    /**
     * Add class initialization methods
     *
     * @param classInits list of class initializer is initialization order (i.e.
     *            element j is a super class of element j+1)
     * @return true if the call graph changed as a result of this call, false
     *         otherwise
     */
    public boolean addClassInitializers(List<IMethod> classInits) {
        boolean cgChanged = false;
        for (int j = classInits.size() - 1; j >= 0; j--) {
            IMethod clinit = classInits.get(j);
            if (this.classInitializersx.add(clinit)) {
                // new initializer
                cgChanged = true;
                Context c = this.haf.initialContext();
                CGNode initNode;
                try {
                    initNode = this.callGraph.findOrCreateNode(clinit, c);
                }
                catch (CancelException e) {
                    throw new RuntimeException(e);
                }
                this.recordContext(clinit, c);
                this.callGraph.registerEntrypoint(initNode);
                this.clinitCount++;
            }
            else {
                // Already added an initializer and thus must have added initializers for super classes. These are all
                // that are left to process since we are adding from sub class to super class order

                // If any were added then j would have been decremented
                return cgChanged;
            }
        }
        // Should always be true
        assert cgChanged : "Reached the end of the loop without adding any clinits "
        + classInits;
        return cgChanged;
    }

    /**
     * Add new entry point methods (i.e. methods called in the empty context)
     *
     * @param newEntryPoint list of methods to add
     * @return true if the call graph changed as a result of this call, false
     *         otherwise
     */
    public boolean addEntryPoint(IMethod newEntryPoint) {
        boolean changed = this.entryPointsx.add(newEntryPoint);
        if (changed) {
            // new initializer
            Context c = this.haf.initialContext();
            CGNode initNode;
            try {
                initNode = this.callGraph.findOrCreateNode(newEntryPoint, c);
            }
            catch (CancelException e) {
                throw new RuntimeException(e);
            }
            this.recordContext(newEntryPoint, c);
            this.callGraph.registerEntrypoint(initNode);
            this.clinitCount++;
        }
        return changed;
    }

    class FilteredIntSet extends AbstractIntSet implements IntSet {
        final IntSet s;
        final Set<TypeFilter> filters;

        FilteredIntSet(IntSet s, Set<TypeFilter> filters) {
            this.filters = filters;
            assert filters != null && !filters.isEmpty();
            boolean allImpossible = true;
            for (TypeFilter filter : filters) {
                if (TypeFilter.IMPOSSIBLE.equals(filter)) {
                    // nothing will satisfy this filter.
                }
                else {
                    allImpossible = false;
                    break;
                }
            }
            this.s = allImpossible ? EmptyIntSet.instance : s;
        }

        @Override
        public IntIterator intIterator() {
            return new FilteredIterator(this.s.intIterator(), this.filters);
        }

        @Override
        public boolean contains(int o) {
            return this.s.contains(o) && satisfiesAny(filters, PointsToGraph.this.concreteTypeDictionary.get(o));
        }

        @Override
        public boolean containsAny(IntSet set) {
            IntIterator iter = set.intIterator();
            while (iter.hasNext()) {
                if (this.contains(iter.next())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public IntSet intersection(IntSet that) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IntSet union(IntSet that) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEmpty() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int size() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void foreach(IntSetAction action) {
            IntIterator iter = this.intIterator();
            while (iter.hasNext()) {
                action.act(iter.next());
            }
        }

        @Override
        public void foreachExcluding(IntSet X, IntSetAction action) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int max() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean sameValue(IntSet that) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSubset(IntSet that) {
            throw new UnsupportedOperationException();
        }

        public int underlyingSetSize() {
            if (this.s instanceof FilteredIntSet) {
                return ((FilteredIntSet) this.s).underlyingSetSize();
            }
            return this.s.size();
        }

    }

    class FilteredIterator implements IntIterator {
        private final IntIterator iter;
        private final TypeFilter filter;
        private final Set<TypeFilter> filters;
        private int next = -1;

        FilteredIterator(IntIterator iter, TypeFilter filter) {
            this.filter = filter;
            this.filters = null;
            if (TypeFilter.IMPOSSIBLE.equals(filter)) {
                // nothing will satisfy this filter.
                this.iter = EmptyIntIterator.instance();
            }
            else {
                this.iter = iter;
            }

        }

        FilteredIterator(IntIterator iter, Set<TypeFilter> filters) {
            if (filters.size() == 1) {
                this.filter = filters.iterator().next();
                this.filters = null;
            }
            else {
                this.filter = null;
                this.filters = filters;
            }
            if (filter != null && TypeFilter.IMPOSSIBLE.equals(filter)) {
                // nothing will satisfy this filter.
                this.iter = EmptyIntIterator.instance();
            }
            else {
                this.iter = iter;
            }

        }

        @Override
        public boolean hasNext() {
            while (this.next < 0 && this.iter.hasNext()) {
                int i = this.iter.next();
                IClass type = PointsToGraph.this.concreteTypeDictionary.get(i);
                if (this.filter != null && this.filter.satisfies(type) || this.filters != null
                        && satisfiesAny(filters, type)) {
                    this.next = i;
                }
            }

            return this.next >= 0;
        }

        @Override
        public int next() {
            if (this.hasNext()) {
                int x = this.next;
                this.next = -1;
                return x;
            }
            throw new NoSuchElementException();
        }
    }

    class SortedIntSetUnion extends AbstractIntSet implements IntSet {
        final IntSet a;
        final IntSet b;

        SortedIntSetUnion(IntSet a, IntSet b) {
            this.a = a;
            this.b = b;
            if (a == null) {
                throw new IllegalArgumentException("a is null");
            }
            if (b == null) {
                throw new IllegalArgumentException("b is null");
            }
        }

        @Override
        public boolean isEmpty() {
            return this.a.isEmpty() && this.b.isEmpty();
        }

        @Override
        public boolean contains(int x) {
            return this.a.contains(x) || this.b.contains(x);
        }

        @Override
        public IntIterator intIterator() {
            return new SortedIntSetUnionIterator(this.a.intIterator(),
                                                 this.b.intIterator());
        }

    }

    class SortedIntSetUnionIterator implements IntIterator {
        final IntIterator a;
        final IntIterator b;
        int aNext = 0;
        int bNext = 0;
        boolean aNextValid = false;
        boolean bNextValid = false;

        SortedIntSetUnionIterator(IntIterator a, IntIterator b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public boolean hasNext() {
            if (!this.aNextValid && this.a.hasNext()) {
                this.aNext = this.a.next();
                this.aNextValid = true;
            }
            if (!this.bNextValid && this.b.hasNext()) {
                this.bNext = this.b.next();
                this.bNextValid = true;
            }
            return this.aNextValid || this.bNextValid;
        }

        @Override
        public int next() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }
            // at least one of aNext and bNext is non-null
            if (!this.aNextValid) {
                this.bNextValid = false;
                return this.bNext;
            }
            if (!this.bNextValid) {
                this.aNextValid = false;
                return this.aNext;
            }
            // both are non-null
            if (this.aNext == this.bNext) {
                // they are the same value
                this.aNextValid = this.bNextValid = false;
                return this.aNext;
            }
            if (this.aNext < this.bNext) {
                this.aNextValid = false;
                return this.aNext;
            }
            this.bNextValid = false;
            return this.bNext;
        }

    }

    static Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> composeIterators(Set<PointsToGraphNode> unfilteredSet,
                                                                                 Set<OrderedPair<PointsToGraphNode, TypeFilter>> filteredSet) {
        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> unfilteredIterator = unfilteredSet == null
                ? null : new LiftUnfilteredIterator(unfilteredSet.iterator());
        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> filteredIterator = filteredSet == null
                ? null : filteredSet.iterator();

        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> iter;
        if (unfilteredIterator == null) {
            if (filteredIterator == null) {
                iter = Collections.<OrderedPair<PointsToGraphNode, TypeFilter>> emptyIterator();
            }
            else {
                iter = filteredIterator;
            }
        }
        else {
            if (filteredIterator == null) {
                iter = unfilteredIterator;
            }
            else {
                iter = new ComposedIterators<>(unfilteredIterator,
                        filteredIterator);
            }
        }
        return iter;
    }

    public static class ComposedIterators<T> implements Iterator<T> {
        Iterator<T> iter1;
        Iterator<T> iter2;

        public ComposedIterators(Iterator<T> iter1, Iterator<T> iter2) {
            this.iter1 = iter1;
            this.iter2 = iter2;
        }

        @Override
        public boolean hasNext() {
            return this.iter1 != null && this.iter1.hasNext()
                    || this.iter2.hasNext();
        }

        @Override
        public T next() {
            if (this.iter1 != null) {
                if (this.iter1.hasNext()) {
                    return this.iter1.next();
                }
                this.iter1 = null;
            }
            return this.iter2.next();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    public static class LiftUnfilteredIterator implements
    Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> {
        private final Iterator<PointsToGraphNode> iter;

        public LiftUnfilteredIterator(Iterator<PointsToGraphNode> iter) {
            this.iter = iter;
        }

        @Override
        public boolean hasNext() {
            return this.iter.hasNext();
        }

        @Override
        public OrderedPair<PointsToGraphNode, TypeFilter> next() {
            return new OrderedPair<>(this.iter.next(), (TypeFilter) null);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    public class IntToInstanceKeyIterator implements Iterator<InstanceKey> {
        private final IntIterator iter;

        public IntToInstanceKeyIterator(IntIterator iter) {
            this.iter = iter;
        }

        @Override
        public boolean hasNext() {
            return this.iter.hasNext();
        }

        @Override
        public InstanceKey next() {
            return PointsToGraph.this.instanceKeyDictionary.get(this.iter.next());
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

    /**
     * Return the set of InstanceKeys that are in source (and satisfy filter)
     * but are not in target.
     *
     * @param source
     * @param filter
     * @param target
     * @return
     */
    IntSet getDifference(/*PointsToGraphNode*/int source, TypeFilter filter,
    /*PointsToGraphNode*/int target) {
        source = this.getRepresentative(source);

        IntSet s = this.cache.getPointsToSet(source);
        IntIterator srcIter;
        if (filter == null) {
            srcIter = s.intIterator();
        }
        else {
            srcIter = new FilteredIterator(s.intIterator(), filter);
        }
        return this.getDifference(srcIter, target);

    }

    private IntSet getDifference(IntIterator srcIter, /*PointsToGraphNode*/int target) {
        target = this.getRepresentative(target);

        if (!srcIter.hasNext()) {
            // nothing in there, return an empty set.
            return EmptyIntSet.instance;
        }

        MutableIntSet s = MutableSparseIntSet.makeEmpty();
        while (srcIter.hasNext()) {
            int i = srcIter.next();
            if (!this.pointsTo(target, i)) {
                s.add(i);
            }
        }
        return s;

    }

    /**
     * Return whatever is in set which is not in the points to set of target.
     *
     * @param set
     * @param target
     * @return
     */
    public IntSet getDifference(IntSet set, /*PointsToGraphNode*/int target) {
        return this.getDifference(set.intIterator(), target);
    }

    /**
     * This class implements a cache for realized points-to sets. Well, it's not really a cache, it just keeps
     * everything.
     */
    private class RealizedSetCache {
        private final ConcurrentIntMap<MutableIntSet> cachex = new SimpleConcurrentIntMap<>();
        private int hits = 0;
        private int misses = 0;
        private int uncachable = 0;


        /**
         * Update or invalidate caches based on the changes represented by the
         * graph delta
         */
        private void updateForDelta(GraphDelta delta) {
            IntIterator iter = delta.domainIterator();
            while (iter.hasNext()) {
                /*PointsToGraphNode*/int n = iter.next();
                MutableIntSet s = this.cachex.get(n);
                if (s != null) {
                    // the set is in the cache!
                    IntSet deltaSet = delta.pointsToSet(n);
                    s.addAll(deltaSet);
                }
            }
        }

        /**
         * Node n has been removed (e.g., collapsed with another node)
         */
        public void removed(/*PointsToGraphNode*/int n) {
            this.cachex.remove(n);
        }

        //        IntSet getPointsToSetIfNotEvicted(/*PointsToGraphNode*/int n) {
        //            SoftReference<MutableIntSet> sr =
        //            if (sr != null) {
        //                IntSet s = this.cache.get(n);
        //                if (s != null) {
        //                    // put it in the recently used...
        //                    this.recentlyUsedMap.put(n, s);
        //                }
        //                return s;
        //            }
        //            // the points to set hasn't been evicted, so lets get it.
        //            return this.getPointsToSet(n);
        //        }
        //
        public IntSet getPointsToSet(/*PointsToGraphNode*/int n) {
            MutableIntSet s = this.cachex.get(n);
            if (s != null) {
                // we have it in the cache!
                hits++;
                return s;
            }

            return this.realizePointsToSet(n, MutableSparseIntSet.makeEmpty(), new IntStack(), new Stack<Boolean>());

        }

        private IntSet realizePointsToSet(/*PointsToGraphNode*/int n, MutableIntSet currentlyRealizing,
                                          IntStack currentlyRealizingStack, Stack<Boolean> shouldCache) {
            assert !PointsToGraph.this.representativex.containsKey(n) : "Getting points to set of node that has been merged with another node.";

            try {
                MutableIntSet s = this.cachex.get(n);
                if (s != null) {
                    // we have it in the cache!
                    hits++;
                    return s;
                }

                if (currentlyRealizing.contains(n)) {
                    // we are called recursively. Need to handle this specially.
                    // find the index that n first appears at
                    int foundAt = -1;
                    for (int i = 0; i < currentlyRealizingStack.size(); i++) {
                        if (foundAt < 0 && currentlyRealizingStack.get(i) == n) {
                            foundAt = i;
                        }
                        else if (foundAt >= 0) {
                            // it is not safe to cache the result of i. (but will be ok to cache foundAt).
                            shouldCache.setElementAt(false, i);
                        }
                    }
                    // return an empty set, which will let us compute the realization of the
                    // point to set.
                    return EmptyIntSet.instance;
                }
                boolean baseContains = PointsToGraph.this.basex.containsKey(n);

                IntSet unfilteredSubsets = PointsToGraph.this.isUnfilteredSupersetOfx.get(n);

                IntMap<Set<TypeFilter>> filteredSubsets = PointsToGraph.this.isSupersetOfx.get(n);

                boolean hasUnfilteredSubsets = unfilteredSubsets != null
                        && !unfilteredSubsets.isEmpty();
                boolean hasFilteredSubsets = filteredSubsets != null
                        && !filteredSubsets.isEmpty();

                // A case that shouldn't be true...
                if (!baseContains && !hasUnfilteredSubsets
                        && !hasFilteredSubsets) {
                    // doesn't point to anything.
                    return EmptyIntSet.instance;
                }

                if (baseContains
                        && !(hasUnfilteredSubsets || hasFilteredSubsets)) {
                    // n is a base node, and no superset relations
                    return PointsToGraph.this.basex.get(n);
                }


                // We now know we definitely missed in the cache (i.e., not a base node, and not one we choose not to cache).
                this.misses++;

                s = PointsToAnalysisMultiThreaded.makeConcurrentIntSet();
                if (baseContains) {
                    s.addAll(PointsToGraph.this.basex.get(n));
                }
                shouldCache.push(true);
                currentlyRealizing.add(n);
                currentlyRealizingStack.push(n);

                if (hasUnfilteredSubsets) {
                    IntIterator iter = unfilteredSubsets.intIterator();
                    while (iter.hasNext()) {
                        int x = iter.next();
                        s.addAll(this.realizePointsToSet(x,
                                                         currentlyRealizing,
                                                         currentlyRealizingStack,
                                                         shouldCache));
                    }
                }
                if (hasFilteredSubsets) {
                    IntIterator iter = filteredSubsets.keyIterator();
                    while (iter.hasNext()) {
                        int x = iter.next();
                        Set<TypeFilter> filters = filteredSubsets.get(x);
                        s.addAll(new FilteredIntSet(this.realizePointsToSet(x,
                                                                            currentlyRealizing,
                                                                            currentlyRealizingStack,
                                                                            shouldCache), filters));
                    }
                }
                currentlyRealizing.remove(n);
                currentlyRealizingStack.pop();
                if (shouldCache.pop()) {
                    this.storeInCache(n, s);
                }
                else {
                    this.uncachable++;
                }
                return s;
            }
            finally {
                if (this.hits + this.misses >= 1000000) {
                    System.err.println("  Cache: " + (this.hits + this.misses) + " accesses: " + 100 * this.hits
                            / (this.hits + this.misses) + "% hits; " + this.misses + " misses; " + this.uncachable
                            + " of the misses were uncachable; cache size: " + this.cachex.size());
                    this.hits = this.misses = this.uncachable = 0;
                }
            }

        }

        private void storeInCache(/*PointsToGraphNode*/int n, MutableIntSet s) {
            // it is safe for us to cache the result of this realization.

            MutableIntSet ex = this.cachex.putIfAbsent(n, s);
            if (ex != null) {
                // someone beat us to it!
                ex.addAll(s);
            }
        }
    }

    public int cycleRemovalCount() {
        return this.representativex.size();
    }

    public void findCycles() {
        IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();

        MutableIntSet visited = MutableSparseIntSet.makeEmpty();
        IntIterator iter = this.isUnfilteredSupersetOfx.keyIterator();
        while (iter.hasNext()) {
            int n = iter.next();
            this.findCycles(n, visited, MutableSparseIntSet.makeEmpty(), new IntStack(), toCollapse);
        }
        MutableIntSet collapsed = MutableSparseIntSet.makeEmpty();
        IntIterator repIter = toCollapse.keyIterator();
        while (repIter.hasNext()) {
            int rep = repIter.next();
            rep = this.getRepresentative(rep); // it is possible that rep was already collapsed to something else. So we get the representative of it to shortcut things.
            IntIterator nIter = toCollapse.get(rep).intIterator();
            while (nIter.hasNext()) {
                int n = nIter.next();
                if (collapsed.contains(n)) {
                    // we have already collapsed n with something. let's skip it.
                    continue;
                }
                collapsed.add(n);
                this.collapseNodes(n, rep);
            }
        }

    }

    private void findCycles(/*PointsToGraphNode*/int n, MutableIntSet visited, MutableIntSet currentlyVisiting,
                            IntStack currentlyVisitingStack, IntMap<MutableIntSet> toCollapse) {
        if (currentlyVisiting.contains(n)) {
            // we detected a cycle!
            int foundAt = -1;
            for (int i = 0; i < currentlyVisiting.size(); i++) {
                if (foundAt < 0 && currentlyVisitingStack.get(i) == n) {
                    foundAt = i;
                    break;
                }
            }
            // we can collapse some nodes together!
            MutableIntSet toCollapseSet = toCollapse.get(n);
            if (toCollapseSet == null) {
                toCollapseSet = MutableSparseIntSet.makeEmpty();
                toCollapse.put(n, toCollapseSet);
            }
            for (int i = foundAt + 1; i < currentlyVisitingStack.size(); i++) {
                toCollapseSet.add(currentlyVisitingStack.get(i));
            }
            return;
        }

        if (visited.contains(n)) {
            // already recursed or recursing on the children of n
            return;
        }
        visited.add(n);

        // now recurse.
        currentlyVisiting.add(n);
        currentlyVisitingStack.push(n);
        IntSet children = this.isUnfilteredSupersetOfx.get(n);
        if (children == null) {
            children = EmptyIntSet.instance;
        }
        IntIterator childIterator = children.intIterator();
        while (childIterator.hasNext()) {
            int child = childIterator.next();
            this.findCycles(child,
                            visited,
                            currentlyVisiting,
                            currentlyVisitingStack,
                            toCollapse);
        }

        currentlyVisiting.remove(n);
        currentlyVisitingStack.pop();

    }
}
