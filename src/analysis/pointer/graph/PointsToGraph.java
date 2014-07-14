package analysis.pointer.graph;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Stack;

import util.OrderedPair;
import util.intmap.IntMap;
import util.intmap.SparseIntMap;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
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
     * Dictionary for mapping ints to InstanceKeys.
     */
    private final ArrayList<InstanceKey> instanceKeyDictionary = new ArrayList<>();
    /**
     * Dictionary for mapping InstanceKeys to ints
     */
    private final Map<InstanceKey, Integer> reverseInstanceKeyDictionary = new HashMap<>();

    /**
     * Dictionary for mapping PointsToGraphNodes to ints
     */
    private final Map<PointsToGraphNode, Integer> reverseGraphNodeDictionary = new HashMap<>();

    final ArrayList<IClass> concreteTypeDictionary = new ArrayList<>();

    /*
     * The pointsto graph is represented using two relations. If base.get(n).contains(i) then i is in the pointsto set
     * of n. If isSubsetOf.get(n).contains(<m, f>), then the filtered pointsto set of n is a subset of the points to set
     * of m. The superset relation is just the reverse of subset.
     *
     * Map from PointsToGraphNode to sets of InstanceKeys
     */
    private final IntMap<MutableIntSet> base = new SparseIntMap<>();

    /**
     * Map from PointsToGraphNode to set of PointsToGraphNode
     */
    private final IntMap<MutableIntSet> isUnfilteredSubsetOf = new SparseIntMap<>();

    /**
     * Map from PointsToGraphNode to set of PointsToGraphNode
     */
    private final IntMap<MutableIntSet> isUnfilteredSupersetOf = new SparseIntMap<>();

    /**
     * Map from PointsToGraphNode to map from PointsToGraphNode to TypeFilter
     */
    private final IntMap<IntMap<TypeFilter>> isSubsetOf = new SparseIntMap<>();

    /**
     * Map from PointsToGraphNode to map from PointsToGraphNode to TypeFilter
     */
    private final IntMap<IntMap<TypeFilter>> isSupersetOf = new SparseIntMap<>();

    /**
     * Map from PointsToGraphNodes to PointsToGraphNodes
     */
    private final IntMap<Integer> representative = new SparseIntMap<>();
    /**
     * The contexts that a method may appear in.
     */
    private final Map<IMethod, Set<Context>> contexts = new HashMap<>();

    /**
     * The classes that will be loaded (i.e., we need to analyze their static
     * initializers).
     */
    private final Set<IMethod> classInitializers = AnalysisUtil.createConcurrentSet();

    /**
     * Entry points added during the pointer analysis
     */
    private final Set<IMethod> entryPoints = AnalysisUtil.createConcurrentSet();

    /**
     * Heap abstraction factory.
     */
    private final HeapAbstractionFactory haf;

    private final HafCallGraph callGraph;

    // private final DependencyRecorder depRecorder;
    private MutableIntSet readNodes;
    private MutableIntSet newlyCollapsedNodes;

    private Map<IMethod, Set<Context>> newContexts;

    /*
     * A cache for realized points to sets.
     */
    RealizedSetCache cache = new RealizedSetCache();

    private int outputLevel = 0;

    public static boolean DEBUG = false;

    public PointsToGraph(StatementRegistrar registrar,
                         HeapAbstractionFactory haf) {
        this.readNodes = MutableSparseIntSet.makeEmpty();
        this.newlyCollapsedNodes = MutableSparseIntSet.makeEmpty();
        this.newContexts = new HashMap<>();

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
        return this.base;
    }

    // Return the immediate supersets of PointsToGraphNode n. That is, any node m such that n is an immediate subset of m
    public OrderedPair<IntSet, IntMap<TypeFilter>> immediateSuperSetsOf(int n) {
        n = this.getRepresentative(n);

        IntSet unfilteredsupersets = this.isUnfilteredSubsetOf.get(n);
        IntMap<TypeFilter> supersets = this.isSubsetOf.get(n);
        return new OrderedPair<>(unfilteredsupersets, supersets);
    }

    // Return the immediate supersets of n. That is, any node m such that n is an immediate superset of m
    // The first element of the pair returned is the IntSet of unfiltered PointsToGraphNodes,
    // and the second element is the IntMap for filtered PointsToGraphNodes.
    public OrderedPair<IntSet, IntMap<TypeFilter>> immediateSubSetsOf(/*PointsToGraphNode*/int n) {
        n = this.getRepresentative(n);
        IntSet unfilteredsubsets = this.isUnfilteredSupersetOf.get(n);
        IntMap<TypeFilter> subsets = this.isSupersetOf.get(n);
        return new OrderedPair<>(unfilteredsubsets, subsets);
    }

    public int numIsSupersetOf(/*PointsToGraphNode*/int n) {
        IntSet unfSuperSetOf = this.isUnfilteredSupersetOf.get(n);
        IntMap<TypeFilter> filtSuperSetOf = this.isSupersetOf.get(n);

        return (unfSuperSetOf == null ? 0 : unfSuperSetOf.size())
                + (filtSuperSetOf == null ? 0 : filtSuperSetOf.size());
    }


    Integer getImmediateRepresentative(/*PointsToGraphNode*/int n) {
        return this.representative.get(n);
    }

    public/*PointsToGraphNode*/int getRepresentative(/*PointsToGraphNode*/int n) {
        int orig = n;
        int rep;
        Integer x = n;
        do {
            rep = x;
            x = this.representative.get(x);
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
            h = this.instanceKeyDictionary.size();
            this.instanceKeyDictionary.add(heapContext);
            this.concreteTypeDictionary.add(heapContext.getConcreteType());
            this.reverseInstanceKeyDictionary.put(heapContext, h);
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
            n = this.reverseGraphNodeDictionary.size();
            this.reverseGraphNodeDictionary.put(node, n);
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
            changed.addCopyEdges(s, null, t);

            sourceSubset.add(t);
            // make sure the superset relation stays consistent
            MutableIntSet targetSuperset = this.getOrCreateUnfilteredSupersetSet(t);
            targetSuperset.add(s);

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

        IntMap<TypeFilter> sourceSubset = this.getOrCreateSubsetSet(s);

        GraphDelta changed = new GraphDelta(this);
        if (!sourceSubset.containsKey(t)) {
            // For the current design, it's important that we tell delta about the copyEdges before actually updating it.
            changed.addCopyEdges(s, filter, t);

            sourceSubset.put(t, filter);
            // make sure the superset relation stays consistent
            IntMap<TypeFilter> targetSuperset = this.getOrCreateSupersetSet(t);
            targetSuperset.put(s, filter);

            // update the cache for the changes
            this.cache.updateForDelta(changed);
        }
        else {
            // the source subset already contains a filtered edge to t.
            assert sourceSubset.get(t).equals(filter);
        }
        return changed;
    }

    /**
     * Provide an interatory for the things that n points to. Note that we may
     * not return a set, i.e., some InstanceKeys may be returned multiple times.
     * XXX we may change this in the future...
     *
     * @param n
     * @return
     */
    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode n) {
        return new IntToInstanceKeyIterator(this.pointsToIntIterator(lookupDictionary(n)));
    }

    public IntIterator pointsToIntIterator(/*PointsToGraphNode*/int n) {
        n = this.getRepresentative(n);
        this.recordRead(n);
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

        IntSet s = this.cache.getPointsToSetIfNotEvicted(n);
        if (s != null) {
            return s.contains(ik);
        }

        // we don't have a cached version of the points to set. Let's try to be cunning.
        if (this.base.containsKey(n)) {
            // we have a base node,
            if (this.base.get(n).contains(ik)) {
                return true;
            }
        }
        if (visited == null) {
            visited = MutableSparseIntSet.makeEmpty();
        }
        visited.add(n);

        // let's try the immediate subsets of n
        OrderedPair<IntSet, IntMap<TypeFilter>> subsets = this.immediateSubSetsOf(n);
        // First go through the unfiltered subsets
        IntIterator unfilteredSubsets = subsets.fst().intIterator();
        while (unfilteredSubsets.hasNext()) {
            int ss = unfilteredSubsets.next();
            if (this.pointsTo(ss, ik, visited)) {
                return true;
            }
        }
        // Now the filtered...
        IntIterator filteredSubsets = subsets.snd().keyIterator();
        while (filteredSubsets.hasNext()) {
            int ss = filteredSubsets.next();
            TypeFilter filter = subsets.snd().get(ss);
            if (filter.satisfies(this.concreteTypeDictionary.get(ik))) {
                if (this.pointsTo(ss, ik, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * XXXX DOCO TODO.
     *
     */
    @SuppressWarnings("deprecation")
    public boolean addCall(CallSiteReference callSite, IMethod caller,
                           Context callerContext, IMethod callee,
                           Context calleeContext) {
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
        Set<Context> s = this.contexts.get(callee);
        if (s == null) {
            s = new HashSet<>();
            this.contexts.put(callee, s);
        }

        if (s.add(calleeContext)) {
            // The context is new
            Set<Context> n = this.newContexts.get(callee);
            if (n == null) {
                n = new HashSet<>();
                this.newContexts.put(callee, n);
            }
            n.add(calleeContext);
        }
    }

    private MutableIntSet getOrCreateBaseSet(/*PointsToGraphNode*/int node) {
        MutableIntSet s = this.base.get(node);
        if (s == null) {
            s = MutableSparseIntSet.makeEmpty();
            this.base.put(node, s);
        }
        return s;
    }

    private IntMap<TypeFilter> getOrCreateSubsetSet(/*PointsToGraphNode*/int node) {
        assert !this.representative.containsKey(node);
        return getOrCreateIntMap(node, this.isSubsetOf);
    }

    private IntMap<TypeFilter> getOrCreateSupersetSet(/*PointsToGraphNode*/int node) {
        assert !this.representative.containsKey(node);
        return getOrCreateIntMap(node, this.isSupersetOf);
    }

    private MutableIntSet getOrCreateUnfilteredSubsetSet(/*PointsToGraphNode*/int node) {
        assert !this.representative.containsKey(node);
        return getOrCreateIntSet(node, this.isUnfilteredSubsetOf);
    }

    private MutableIntSet getOrCreateUnfilteredSupersetSet(/*PointsToGraphNode*/int node) {
        assert !this.representative.containsKey(node);
        return getOrCreateIntSet(node, this.isUnfilteredSupersetOf);
    }

    private Set<Context> getOrCreateContextSet(IMethod callee) {
        return PointsToGraph.<IMethod, Context> getOrCreateSet(callee,
                                                               this.contexts);
    }

    static MutableIntSet getOrCreateIntSet(int key, IntMap<MutableIntSet> map) {
        MutableIntSet set = map.get(key);
        if (set == null) {
            set = MutableSparseIntSet.makeEmpty();
            map.put(key, set);
        }
        return set;
    }

    private <T> IntMap<T> getOrCreateIntMap(int key, IntMap<IntMap<T>> map) {
        IntMap<T> set = map.get(key);
        if (set == null) {
            set = new SparseIntMap<>();
            map.put(key, set);
        }
        return set;
    }

    static <K, T> Set<T> getOrCreateSet(K key, Map<K, Set<T>> map) {
        Set<T> set = map.get(key);
        if (set == null) {
            // make these concurrent to avoid ConcurrentModificationExceptions
            // set = new HashSet<>();
            set = AnalysisUtil.createConcurrentSet();
            map.put(key, set);
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
        Set<Context> s = this.contexts.get(m);
        if (s == null) {
            return Collections.<Context> emptySet();
        }
        return Collections.unmodifiableSet(this.contexts.get(m));
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

    /**
     * Get new contexts created since this was last called and clear the new
     * context map
     *
     * @return new context map
     */
    public Map<IMethod, Set<Context>> getAndClearNewContexts() {
        Map<IMethod, Set<Context>> newC = this.newContexts;
        this.newContexts = new HashMap<>();
        return newC;
    }

    /**
     * Get the set of nodes that have been read since this was last called and
     * clear the set.
     *
     * @return set of nodes for which the points-to set was retrieved
     */
    public MutableIntSet getAndClearReadNodes() {
        MutableIntSet c = this.readNodes;
        this.readNodes = MutableSparseIntSet.makeEmpty();
        return c;
    }

    private void recordRead(/*PointsToGraphNode*/int node) {
        this.readNodes.add(node);
    }

    public MutableIntSet getAndClearNewlyCollapsedNodes() {
        MutableIntSet c = this.newlyCollapsedNodes;
        this.newlyCollapsedNodes = MutableSparseIntSet.makeEmpty();
        return c;
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

        // it is possible that since n and rep were registered, one or both of them were already merged.
        n = this.getRepresentative(n);
        rep = this.getRepresentative(rep);

        if (n == rep) {
            // they have already been merged.
            return;
        }
        this.newlyCollapsedNodes.add(n);

        // update the cache.
        this.cache.removed(n);

        // update the read nodes
        if (this.readNodes.contains(n)) {
            this.readNodes.remove(n);
            this.readNodes.add(rep);
        }

        // update the subset and superset graphs.
        IntSet unfilteredSubsetOf = this.isUnfilteredSubsetOf.remove(n);
        IntSet unfilteredSupersetOf = this.isUnfilteredSupersetOf.remove(n);

        MutableIntSet repUnfilteredSubsetOf = this.getOrCreateUnfilteredSubsetSet(rep);
        MutableIntSet repUnfilteredSupersetOf = this.getOrCreateUnfilteredSupersetSet(rep);

        if (unfilteredSubsetOf != null) {
            IntIterator iter = unfilteredSubsetOf.intIterator();
            while (iter.hasNext()) {
                int x = iter.next();
                // n is an unfiltered subset of x, so n is in the isUnfilteredSupersets of x
                MutableIntSet s = this.isUnfilteredSupersetOf.get(x);
                s.remove(n);
                if (x != rep) {
                    s.add(rep);
                    // add x to the representative's...
                    repUnfilteredSubsetOf.add(x);
                }
            }
        }

        if (unfilteredSupersetOf != null) {
            IntIterator iter = unfilteredSupersetOf.intIterator();
            while (iter.hasNext()) {
                int x = iter.next();
                // n is an unfiltered superset of x, so n is in the isUnfilteredSubsets of x
                MutableIntSet s = this.isUnfilteredSubsetOf.get(x);
                s.remove(n);
                if (x != rep) {
                    s.add(rep);
                    // add x to the representative's...
                    repUnfilteredSupersetOf.add(x);
                }
            }
        }

        IntMap<TypeFilter> filteredSubsetOf = this.isSubsetOf.remove(n);
        IntMap<TypeFilter> filteredSupersetOf = this.isSupersetOf.remove(n);

        IntMap<TypeFilter> repFilteredSubsetOf = this.getOrCreateSubsetSet(rep);
        IntMap<TypeFilter> repFilteredSupersetOf = this.getOrCreateSupersetSet(rep);

        if (filteredSubsetOf != null) {
            IntIterator iter = filteredSubsetOf.keyIterator();
            while (iter.hasNext()) {
                int m = iter.next();
                TypeFilter f = filteredSubsetOf.get(m);
                // n is a filtered subset of m, so n is in the isSupersets of m
                IntMap<TypeFilter> s = this.isSupersetOf.get(m);
                s.remove(n);
                if (m != rep) {
                    assert !s.containsKey(rep);
                    s.put(rep, f);
                    // add x to the representative's...
                    assert repFilteredSubsetOf.get(m) == null || repFilteredSubsetOf.get(m).equals(f);
                    repFilteredSubsetOf.put(m, f);

                }
            }
        }

        if (filteredSupersetOf != null) {
            IntIterator iter = filteredSupersetOf.keyIterator();
            while (iter.hasNext()) {
                int m = iter.next();
                TypeFilter f = filteredSupersetOf.get(m);
                // n is a filtered superset of m, so n is in the isSubsets of m
                IntMap<TypeFilter> s = this.isSubsetOf.get(m);
                s.remove(n);
                if (m != rep) {
                    assert !s.containsKey(rep);
                    s.put(rep, f);
                    // add x to the representative's...
                    assert repFilteredSupersetOf.get(m) == null || repFilteredSupersetOf.get(m).equals(f);
                    repFilteredSupersetOf.put(m, f);
                }
            }
        }

        // update the base nodes.
        assert !this.base.containsKey(n) : "Base nodes shouldn't be collapsed with other nodes";

        this.representative.put(n, rep);
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
            if (this.classInitializers.add(clinit)) {
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
        boolean changed = this.entryPoints.add(newEntryPoint);
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
        final TypeFilter filter;

        FilteredIntSet(IntSet s, TypeFilter filter) {
            this.filter = filter;
            if (TypeFilter.IMPOSSIBLE.equals(filter)) {
                // nothing will satisfy this filter.
                this.s = EmptyIntSet.instance;
            }
            else {
                this.s = s;
            }
        }

        @Override
        public IntIterator intIterator() {
            return new FilteredIterator(this.s.intIterator(), this.filter);
        }

        @Override
        public boolean contains(int o) {
            return this.s.contains(o)
                    && this.filter.satisfies(PointsToGraph.this.concreteTypeDictionary.get(o));
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
        private int next = -1;

        FilteredIterator(IntIterator iter, TypeFilter filter) {
            this.filter = filter;
            if (TypeFilter.IMPOSSIBLE.equals(filter)) {
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
                if (this.filter.satisfies(PointsToGraph.this.concreteTypeDictionary.get(i))) {
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
     * This class implements a cache for realized points-to sets, using
     * SoftReferences
     */
    private class RealizedSetCache {
        private static final int RECENTLY_USED_LIMIT = 50000;
        private final IntMap<SoftReference<MutableIntSet>> cache = new SparseIntMap<>();
        private int hits = 0;
        private int misses = 0;
        private int evictions = 0;
        private int uncachable = 0;

        /*
         * The following maps are used to have hard reference to sets, to stop them being garbage collected,
         * i.e., to keep them in the cache.
         * Map from PointsToGraphNodes to the sets of InstanceKeys they point to
         */
        private IntMap<IntSet> inCycle = new SparseIntMap<>();

        private Map<Integer, IntSet> recentlyUsedMap = new LinkedHashMap<Integer, IntSet>(RECENTLY_USED_LIMIT) {

            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, IntSet> eldest) {
                return this.size() > RECENTLY_USED_LIMIT; // only keep the most recently accessed nodes.
            }

        };

        /**
         * Update or invalidate caches based on the changes represented by the
         * graph delta
         */
        private void updateForDelta(GraphDelta delta) {
            IntIterator iter = delta.domainIterator();
            while (iter.hasNext()) {
                /*PointsToGraphNode*/int n = iter.next();
                SoftReference<MutableIntSet> sr = this.cache.get(n);
                if (sr != null) {
                    MutableIntSet s = sr.get();
                    if (s != null) {
                        // the set is in the cache!
                        IntSet deltaSet = delta.pointsToSet(n);
                        s.addAll(deltaSet);
                        this.recentlyUsedMap.put(n, s);
                        // no need to update inCycle, since it is the same set s.
                    }
                }
            }
        }

        /**
         * Node n has been removed (e.g., collapsed with another node)
         */
        public void removed(/*PointsToGraphNode*/int n) {
            this.cache.remove(n);
            this.recentlyUsedMap.remove(n);
            this.inCycle.remove(n);
        }

        IntSet getPointsToSetIfNotEvicted(/*PointsToGraphNode*/int n) {
            SoftReference<MutableIntSet> sr = this.cache.get(n);
            if (sr != null) {
                IntSet s = sr.get();
                if (s != null) {
                    // put it in the recently used...
                    this.recentlyUsedMap.put(n, s);
                }
                return s;
            }
            // the points to set hasn't been evicted, so lets get it.
            return this.getPointsToSet(n);
        }

        public IntSet getPointsToSet(/*PointsToGraphNode*/int n) {
            return this.realizePointsToSet(n, MutableSparseIntSet.makeEmpty(), new IntStack(), new Stack<Boolean>());

        }

        private IntSet realizePointsToSet(/*PointsToGraphNode*/int n, MutableIntSet currentlyRealizing,
                                          IntStack currentlyRealizingStack, Stack<Boolean> shouldCache) {
            assert !PointsToGraph.this.representative.containsKey(n) : "Getting points to set of node that has been merged with another node.";

            try {
                boolean wasInCache = false;
                SoftReference<MutableIntSet> sr = this.cache.get(n);
                if (sr != null) {
                    IntSet s = sr.get();
                    if (s != null) {
                        // we have it in the cache!
                        this.hits++;
                        // put it in the recently used...
                        this.recentlyUsedMap.put(n, s);
                        return s;
                    }
                    wasInCache = true;
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
                boolean baseContains = PointsToGraph.this.base.containsKey(n);

                IntSet unfilteredSubsets = PointsToGraph.this.isUnfilteredSupersetOf.get(n);

                IntMap<TypeFilter> filteredSubsets = PointsToGraph.this.isSupersetOf.get(n);

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
                    return PointsToGraph.this.base.get(n);
                }

                int totalSubsets = (hasUnfilteredSubsets
                        ? unfilteredSubsets.size() : 0)
                        + (hasFilteredSubsets ? filteredSubsets.size() : 0);
//                if (totalSubsets <= 2 && !baseContains && !wasInCache) {
                if (totalSubsets <= 1 && !baseContains && !wasInCache) {
                    return this.realizeSetWithFewSubsets(n,
                                                         unfilteredSubsets,
                                                         filteredSubsets,
                                                         currentlyRealizing,
                                                         currentlyRealizingStack,
                                                         shouldCache);
                }

                // We now know we definitely missed in the cache (i.e., not a base node, and not one we choose not to cache).
                this.misses++;
                if (this.cache.get(n) != null) {
                    this.evictions++;
                }

                MutableIntSet s = MutableSparseIntSet.makeEmpty();
                if (baseContains) {
                    s.addAll(PointsToGraph.this.base.get(n));
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
                        TypeFilter f = filteredSubsets.get(x);
                        s.addAll(new FilteredIntSet(this.realizePointsToSet(x,
                                                                            currentlyRealizing,
                                                                            currentlyRealizingStack,
                                                                            shouldCache), f));
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
                    System.err.println("  Cache: " + (this.hits + this.misses)
                                       + " accesses: " + 100 * this.hits
                                       / (this.hits + this.misses) + "% hits; "
                                       + this.evictions + " of " + this.misses
                                       + " misses due to evictions; " + this.uncachable
                                       + " of the misses were uncachable; cache size: "
                                       + this.cache.size());
                    this.hits = this.misses = this.evictions = this.uncachable = 0;
                }
            }

        }

        private void storeInCache(/*PointsToGraphNode*/int n, MutableIntSet s) {
            // it is safe for us to cache the result of this realization.
            this.cache.put(n, new SoftReference<>(s));
            this.recentlyUsedMap.put(n, s);
            if (this.inCycle.containsKey(n)) {
                this.inCycle.put(n, s);
            }
        }

        /**
         * n is a PointsToGraphNode with 1 or 2 subsets. Instead of caching it,
         * we will use a union data structure, relying on the fact that all the
         * IntSets in this structure are sorted.
         *
         * @param n
         * @param unfilteredSubsets
         * @param filteredSubsets
         * @param currentlyRealizing
         * @param currentlyRealizingStack
         * @param filters
         * @param shouldCache
         * @return
         */
        private IntSet realizeSetWithFewSubsets(/*PointsToGraphNode*/int n, IntSet unfilteredSubsets,
                                                IntMap<TypeFilter> filteredSubsets, MutableIntSet currentlyRealizing,
                                                IntStack currentlyRealizingStack,
                                                Stack<Boolean> shouldCache) {
            currentlyRealizing.add(n);
            currentlyRealizingStack.push(n);
            shouldCache.push(true);

            IntSet[] subsets = new IntSet[2];
            int ind = 0;

            if (unfilteredSubsets != null) {
                IntIterator iter = unfilteredSubsets.intIterator();
                while (iter.hasNext()) {
                    int x = iter.next();
                    IntSet is = this.realizePointsToSet(x,
                                                        currentlyRealizing,
                                                        currentlyRealizingStack,
                                                        shouldCache);
                    subsets[ind++] = is;
                }
            }
            if (filteredSubsets != null) {
                IntIterator iter = filteredSubsets.keyIterator();
                while (iter.hasNext()) {
                    int x = iter.next();
                    TypeFilter f = filteredSubsets.get(x);
                    IntSet is = new FilteredIntSet(this.realizePointsToSet(x,
                                                                           currentlyRealizing,
                                                                           currentlyRealizingStack,
                                                                           shouldCache), f);
                    subsets[ind++] = is;
                }
            }
            currentlyRealizing.remove(n);
            currentlyRealizingStack.pop();
            boolean shouldCacheThis = shouldCache.pop();

            assert ind == 1 || ind == 2;

            if (ind == 1) {
                return subsets[0];
            }

            if (shouldCacheThis && subsets[0] instanceof SortedIntSetUnion
                    && subsets[1] instanceof SortedIntSetUnion) {
                // it's cacheable, and both of the subsets are themselves unions.
                // So just realize this.
                MutableIntSet s = MutableSparseIntSet.makeEmpty();
                s.addAll(subsets[0]);
                s.addAll(subsets[1]);
                this.storeInCache(n, s);
                return s;
            }

            return new SortedIntSetUnion(subsets[0], subsets[1]);

        }

        public void inCycle(/*PointsToGraphNode*/int n) {
            this.inCycle.put(n, this.getPointsToSet(n));
        }
    }

    public int cycleRemovalCount() {
        return this.representative.size();
    }

    /**
     * Notify the cache that a node is in a cycle.
     *
     * @param n
     */
    public void inCycle(/*PointsToGraphNode*/int n) {
        this.cache.inCycle(n);
    }

    public void findCycles() {
        IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();

        MutableIntSet visited = MutableSparseIntSet.makeEmpty();
        IntIterator iter = this.isUnfilteredSupersetOf.keyIterator();
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
                    // Mark the node as being in a cycle, so that it will stay in the cache.
                    this.inCycle(currentlyVisitingStack.get(i));
                }
                else if (foundAt >= 0) {
                    // Mark the node as being in a cycle, so that it will stay in the cache.
                    this.inCycle(currentlyVisitingStack.get(i));
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
        IntSet children = this.isUnfilteredSupersetOf.get(n);
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
