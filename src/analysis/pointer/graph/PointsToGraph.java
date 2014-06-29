package analysis.pointer.graph;

import java.lang.ref.SoftReference;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Stack;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.CancelException;

/**
 * Graph mapping local variables (in a particular context) and fields to abstract heap locations (representing zero or
 * more actual heap locations)
 */
public class PointsToGraph {
    public static final String ARRAY_CONTENTS = "[contents]";

    /*
     * The pointsto graph is represented using two relations. If base.get(n).contains(i) then i is in the pointsto set
     * of n. If isSubsetOf.get(n).contains(<m, f>), then the filtered pointsto set of n is a subset of the points to set
     * of m. The superset relation is just the reverse of subset.
     */
    private final Map<PointsToGraphNode, Set<InstanceKey>> base =
            new LinkedHashMap<>();

    private final Map<PointsToGraphNode, Set<PointsToGraphNode>> isUnfilteredSubsetOf =
            new LinkedHashMap<>();
    private final Map<PointsToGraphNode, Set<OrderedPair<PointsToGraphNode, TypeFilter>>> isSubsetOf =
            new LinkedHashMap<>();
    private final Map<PointsToGraphNode, Set<OrderedPair<PointsToGraphNode, TypeFilter>>> isSupersetOf =
            new LinkedHashMap<>();
    private final Map<PointsToGraphNode, Set<PointsToGraphNode>> isUnfilteredSupersetOf =
            new LinkedHashMap<>();

    private final Map<PointsToGraphNode, PointsToGraphNode> representative =
            new HashMap<>();
    /**
     * The contexts that a method may appear in.
     */
    private final Map<IMethod, Set<Context>> contexts = new LinkedHashMap<>();

    /**
     * The classes that will be loaded (i.e., we need to analyze their static initializers).
     */
    private final Set<IMethod> classInitializers =
            AnalysisUtil.createConcurrentSet();

    /**
     * Heap abstraction factory.
     */
    private final HeapAbstractionFactory haf;

    private final HafCallGraph callGraph;

    // private final DependencyRecorder depRecorder;
    private Set<PointsToGraphNode> readNodes;
    private Set<PointsToGraphNode> newlyCombinedNodes;

    private Map<IMethod, Set<Context>> newContexts;

    /*
     * A cache for realized points to sets.
     */
    RealizedSetCache cache = new RealizedSetCache();

    private int outputLevel = 0;

    public static boolean DEBUG = false;

    public PointsToGraph(StatementRegistrar registrar,
            HeapAbstractionFactory haf) {
        readNodes = new LinkedHashSet<>();
        newlyCombinedNodes = new LinkedHashSet<>();
        newContexts = new LinkedHashMap<>();

        this.haf = haf;
        callGraph = new HafCallGraph(haf);

        populateInitialContexts(registrar.getInitialContextMethods());
    }

    /**
     * Populate the contexts map by adding the initial context for all the given methods
     * 
     * @param haf
     *            abstraction factory defining the initial context
     * @param initialMethods
     *            methods to be paired with the initial context
     * @return mapping from each method in the given set to the singleton set containing the initial context
     */
    private void populateInitialContexts(Set<IMethod> initialMethods) {
        for (IMethod m : initialMethods) {
            getOrCreateContextSet(m).add(haf.initialContext());
        }
    }

    public Map<PointsToGraphNode, Set<InstanceKey>> getBaseNodes() {
        return base;
    }

    // Return the immediate supersets of n. That is, any node m such that n is an immediate subset of m
    public Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> immediateSuperSetsOf(
            PointsToGraphNode n) {
        n = getRepresentative(n);

        Set<PointsToGraphNode> unfilteredsupersets =
                isUnfilteredSubsetOf.get(n);
        Set<OrderedPair<PointsToGraphNode, TypeFilter>> supersets =
                isSubsetOf.get(n);
        return composeIterators(unfilteredsupersets, supersets);
    }

    // Return the immediate supersets of n. That is, any node m such that n is an immediate superset of m
    public Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> immediateSubSetsOf(
            PointsToGraphNode n) {
        n = getRepresentative(n);
        Set<PointsToGraphNode> unfilteredsubsets =
                isUnfilteredSupersetOf.get(n);
        Set<OrderedPair<PointsToGraphNode, TypeFilter>> subsets =
                isSupersetOf.get(n);
        return composeIterators(unfilteredsubsets, subsets);
    }

    /**
     * Clear the cache of realized points to sets. Should only be used for testing.
     */
    public void clearCache() {
        cache.cache.clear();
    }

    public PointsToGraphNode getRepresentative(PointsToGraphNode n) {
        PointsToGraphNode orig = n;
        PointsToGraphNode rep;
        int i = 0;
        do {
            i++;
            rep = n;
            n = representative.get(n);
        } while (n != null);
        if (i > 1) {
            // short cut it.
            representative.put(orig, rep);
        }
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
        node = getRepresentative(node);

        Set<InstanceKey> pointsToSet = getOrCreateBaseSet(node);

        GraphDelta delta = new GraphDelta(this);
        if (!pointsToSet.contains(heapContext)) {
            delta.add(node, heapContext);
            pointsToSet.add(heapContext);

            // update the cache for the changes
            cache.updateForDelta(delta);
        }
        return delta;
    }

    /**
     * Copy the pointsto set of the source to the pointsto set of the target. This should be used when the pointsto set
     * of the target is a supserset of the pointsto set of the source.
     * 
     * @param source
     * @param target
     * @return
     */
    public GraphDelta copyEdges(PointsToGraphNode source,
            PointsToGraphNode target) {
        source = getRepresentative(source);
        target = getRepresentative(target);

        if (source.equals(target)) {
            // don't bother adding
            return new GraphDelta(this);
        }
        // source is a subset of target, target is a superset of source.
        Set<PointsToGraphNode> sourceSubset =
                getOrCreateUnfilteredSubsetSet(source);

        GraphDelta changed = new GraphDelta(this);
        if (!sourceSubset.contains(target)) {
            // For the current design, it's important that we tell delta about the copyEdges before actually updating it.
            changed.addCopyEdges(source, null, target);

            sourceSubset.add(target);
            // make sure the superset relation stays consistent
            Set<PointsToGraphNode> targetSuperset =
                    getOrCreateUnfilteredSupersetSet(target);
            targetSuperset.add(source);

            // update the cache for the changes
            cache.updateForDelta(changed);
        }
        return changed;
    }

    /**
     * Copy the pointsto set of the source to the pointsto set of the target. This should be used when the pointsto set
     * of the target is a supserset of the pointsto set of the source.
     * 
     * @param source
     * @param target
     * @return
     */
    public GraphDelta copyFilteredEdges(PointsToGraphNode source,
            TypeFilter filter, PointsToGraphNode target) {
        // source is a subset of target, target is a subset of source.

        if (TypeFilter.IMPOSSIBLE.equals(filter)) {
            // impossible filter! Don't bother adding the relationship.
            return new GraphDelta(this);
        }

        source = getRepresentative(source);
        target = getRepresentative(target);

        if (source.equals(target)) {
            // don't bother adding
            return new GraphDelta(this);
        }

        Set<OrderedPair<PointsToGraphNode, TypeFilter>> sourceSubset =
                getOrCreateSubsetSet(source);
        OrderedPair<PointsToGraphNode, TypeFilter> trgFilter =
                new OrderedPair<>(target, filter);

        GraphDelta changed = new GraphDelta(this);
        if (!sourceSubset.contains(trgFilter)) {
            // For the current design, it's important that we tell delta about the copyEdges before actually updating it.
            changed.addCopyEdges(source, filter, target);

            sourceSubset.add(trgFilter);
            // make sure the superset relation stays consistent
            Set<OrderedPair<PointsToGraphNode, TypeFilter>> targetSuperset =
                    getOrCreateSupersetSet(target);
            targetSuperset.add(new OrderedPair<>(source, filter));

            // update the cache for the changes
            cache.updateForDelta(changed);
        }
        return changed;
    }

    /**
     * Provide an interatory for the things that n points to. Note that we may not return a set, i.e., some InstanceKeys
     * may be returned multiple times. XXX we may change this in the future...
     * 
     * @param n
     * @return
     */
    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode n) {
        n = getRepresentative(n);
        recordRead(n);
        return cache.getPointsToSet(n).iterator();
    }

    /**
     * Does n point to ik?
     */
    /*
    public boolean pointsTo(PointsToGraphNode n, InstanceKey ik) {
       if (true) {
           return cache.getPointsToSet(n).contains(ik);
       }
       Set<InstanceKey> s = cache.getPointsToSetIfNotEvicted(n);
       if (s != null) {
           return s.contains(ik);
       }

       // we don't have a cached version of the points to set. Let's try to be cunning.
       if (base.containsKey(n)) {
           return base.get(n).contains(ik);
       }

       // let's try the immediate subsets of n
       Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> iter =
               immediateSubSetsOf(n);
       while (iter.hasNext()) {
           OrderedPair<PointsToGraphNode, TypeFilter> p = iter.next();
           TypeFilter filter = p.snd();
           if (filter == null || filter.satisfies(ik.getConcreteType())) {
               if (pointsTo(p.fst(), ik)) {
                   return true;
               }
           }
       }
       return false;
    }
    */
    /**
     * XXXX DOCO TODO.
     * 
     */
    @SuppressWarnings("deprecation")
    public boolean addCall(CallSiteReference callSite, IMethod caller,
            Context callerContext, IMethod callee, Context calleeContext) {

        CGNode src;
        CGNode dst;

        try {
            src = callGraph.findOrCreateNode(caller, callerContext);
            dst = callGraph.findOrCreateNode(callee, calleeContext);
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
        if (outputLevel >= 2) {
            System.err.println("ADDED\n\t" + PrettyPrinter.methodString(caller)
                    + " in " + callerContext + " to\n\t"
                    + PrettyPrinter.methodString(callee) + " in "
                    + calleeContext);
        }

        recordContext(callee, calleeContext);
        return true;
    }

    /**
     * Record a callee context for the given method
     * 
     * @param callee
     *            method
     * @param calleeContext
     *            context
     */
    private void recordContext(IMethod callee, Context calleeContext) {
        if (outputLevel >= 1) {
            System.err.println("RECORDING: " + callee + " in " + calleeContext
                    + " hc " + calleeContext);
        }
        Set<Context> s = contexts.get(callee);
        if (s == null) {
            s = new LinkedHashSet<>();
            contexts.put(callee, s);
        }

        if (s.add(calleeContext)) {
            // The context is new
            Set<Context> n = newContexts.get(callee);
            if (n == null) {
                n = new LinkedHashSet<>();
                newContexts.put(callee, n);
            }
            n.add(calleeContext);
        }
    }

    private Set<InstanceKey> getOrCreateBaseSet(PointsToGraphNode node) {
        return PointsToGraph.<PointsToGraphNode, InstanceKey> getOrCreateSet(node,
                                                                             base);
    }

    private Set<OrderedPair<PointsToGraphNode, TypeFilter>> getOrCreateSubsetSet(
            PointsToGraphNode node) {
        assert !representative.containsKey(node);
        return PointsToGraph.<PointsToGraphNode, OrderedPair<PointsToGraphNode, TypeFilter>> getOrCreateSet(node,
                                                                                                            isSubsetOf);
    }

    private Set<OrderedPair<PointsToGraphNode, TypeFilter>> getOrCreateSupersetSet(
            PointsToGraphNode node) {
        assert !representative.containsKey(node);
        return PointsToGraph.<PointsToGraphNode, OrderedPair<PointsToGraphNode, TypeFilter>> getOrCreateSet(node,
                                                                                                            isSupersetOf);
    }

    private Set<PointsToGraphNode> getOrCreateUnfilteredSubsetSet(
            PointsToGraphNode node) {
        assert !representative.containsKey(node);
        return PointsToGraph.<PointsToGraphNode, PointsToGraphNode> getOrCreateSet(node,
                                                                                   isUnfilteredSubsetOf);
    }

    private Set<PointsToGraphNode> getOrCreateUnfilteredSupersetSet(
            PointsToGraphNode node) {
        assert !representative.containsKey(node);
        return PointsToGraph.<PointsToGraphNode, PointsToGraphNode> getOrCreateSet(node,
                                                                                   isUnfilteredSupersetOf);
    }

    private Set<Context> getOrCreateContextSet(IMethod callee) {
        return PointsToGraph.<IMethod, Context> getOrCreateSet(callee, contexts);
    }

    static <K, T> Set<T> getOrCreateSet(K key, Map<K, Set<T>> map) {
        Set<T> set = map.get(key);
        if (set == null) {
            // make these concurrent to avoid ConcurrentModificationExceptions
            // set = new LinkedHashSet<>();
            set = AnalysisUtil.createConcurrentSet();
            map.put(key, set);
        }
        return set;
    }

    /**
     * Set of contexts for the given method
     * 
     * @param m
     *            method reference to get contexts for
     * @return set of contexts for the given method
     */
    public Set<Context> getContexts(IMethod m) {
        Set<Context> s = contexts.get(m);
        if (s == null) {
            return Collections.<Context> emptySet();
        }
        return Collections.unmodifiableSet(contexts.get(m));
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
    // public Set<InstanceKey> getAllHContexts() {
    // Set<InstanceKey> all = new LinkedHashSet<>();
    //
    // for (Set<InstanceKey> s : graph.values()) {
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
        return callGraph;
    }

    /**
     * Get new contexts created since this was last called and clear the new context map
     * 
     * @return new context map
     */
    public Map<IMethod, Set<Context>> getAndClearNewContexts() {
        Map<IMethod, Set<Context>> newC = newContexts;
        newContexts = new LinkedHashMap<>();
        return newC;
    }

    /**
     * Get the set of nodes that have been read since this was last called and clear the set.
     * 
     * @return set of nodes for which the points-to set was retrieved
     */
    public Set<PointsToGraphNode> getAndClearReadNodes() {
        Set<PointsToGraphNode> c = readNodes;
        readNodes = new LinkedHashSet<>();
        return c;
    }

    private void recordRead(PointsToGraphNode node) {
        readNodes.add(node);
    }

    public Set<PointsToGraphNode> getAndClearNewlyCombinedNodes() {
        Set<PointsToGraphNode> c = newlyCombinedNodes;
        newlyCombinedNodes = new LinkedHashSet<>();
        return c;
    }

    /**
     * When we have detected that the points to sets of two nodes are identical, we can
     * collapse them.
     * @param n
     * @param rep
     */
    void combineNodes(PointsToGraphNode n, PointsToGraphNode rep) {
        assert !n.equals(rep) : "Can't combine a node with itself";

        // it is possible that since n and rep were registered, one or both of them were already merged.
        n = getRepresentative(n);
        rep = getRepresentative(rep);
        if (n.equals(rep)) {
            // they have already been merged.
            return;
        }
        newlyCombinedNodes.add(n);

        // update the cache.
        cache.removed(n);

        // update the read nodes
        if (readNodes.contains(n)) {
            readNodes.remove(n);
            readNodes.add(rep);
        }

        // update the subset and superset graphs.
        Set<PointsToGraphNode> unfilteredSubsetOf =
                isUnfilteredSubsetOf.remove(n);
        Set<PointsToGraphNode> unfilteredSupersetOf =
                isUnfilteredSupersetOf.remove(n);

        Set<PointsToGraphNode> repUnfilteredSubsetOf =
                getOrCreateUnfilteredSubsetSet(rep);
        Set<PointsToGraphNode> repUnfilteredSupersetOf =
                getOrCreateUnfilteredSupersetSet(rep);

        if (unfilteredSubsetOf != null) {
            for (PointsToGraphNode x : unfilteredSubsetOf) {
                // n is an unfiltered subset of x, so n is in the isUnfilteredSupersets of x 
                Set<PointsToGraphNode> s = isUnfilteredSupersetOf.get(x);
                s.remove(n);
                if (!x.equals(rep)) {
                    s.add(rep);
                    // add x to the representative's...
                    repUnfilteredSubsetOf.add(x);
                }
            }
        }

        if (unfilteredSupersetOf != null) {
            for (PointsToGraphNode x : unfilteredSupersetOf) {
                // n is an unfiltered superset of x, so n is in the isUnfilteredSubsets of x 
                Set<PointsToGraphNode> s = isUnfilteredSubsetOf.get(x);
                s.remove(n);
                if (!x.equals(rep)) {
                    s.add(rep);
                    // add x to the representative's...
                    repUnfilteredSupersetOf.add(x);
                }
            }
        }

        Set<OrderedPair<PointsToGraphNode, TypeFilter>> filteredSubsetOf =
                isSubsetOf.remove(n);
        Set<OrderedPair<PointsToGraphNode, TypeFilter>> filteredSupersetOf =
                isSupersetOf.remove(n);

        Set<OrderedPair<PointsToGraphNode, TypeFilter>> repFilteredSubsetOf =
                getOrCreateSubsetSet(rep);
        Set<OrderedPair<PointsToGraphNode, TypeFilter>> repFilteredSupersetOf =
                getOrCreateSupersetSet(rep);

        if (filteredSubsetOf != null) {
            for (OrderedPair<PointsToGraphNode, TypeFilter> x : filteredSubsetOf) {
                // n is a filtered subset of x, so n is in the isSupersets of x 
                Set<OrderedPair<PointsToGraphNode, TypeFilter>> s =
                        isSupersetOf.get(x.fst());
                s.remove(new OrderedPair<>(n, x.snd()));
                if (!x.fst().equals(rep)) {
                    s.add(new OrderedPair<>(rep, x.snd()));
                    // add x to the representative's...
                    repFilteredSubsetOf.add(x);

                }
            }
        }

        if (filteredSupersetOf != null) {
            for (OrderedPair<PointsToGraphNode, TypeFilter> x : filteredSupersetOf) {
                // n is a filtered superset of x, so n is in the isSubsets of x 
                Set<OrderedPair<PointsToGraphNode, TypeFilter>> s =
                        isSubsetOf.get(x.fst());
                s.remove(new OrderedPair<>(n, x.snd()));
                if (!x.fst().equals(rep)) {
                    s.add(new OrderedPair<>(rep, x.snd()));
                    // add x to the representative's...
                    repFilteredSupersetOf.add(x);

                }
            }
        }

        // update the base nodes.
        assert !base.containsKey(n) : "Base nodes shouldn't be combined";

        representative.put(n, rep);
    }

    public void setOutputLevel(int outputLevel) {
        this.outputLevel = outputLevel;
    }

    public int clinitCount = 0;

    /**
     * Add class initialization methods
     * 
     * @param classInits
     *            list of class initializer is initialization order (i.e. element j is a super class of element j+1)
     * @return true if the call graph changed as a result of this call, false otherwise
     */
    public boolean addClassInitializers(List<IMethod> classInits) {
        boolean cgChanged = false;
        for (int j = classInits.size() - 1; j >= 0; j--) {
            IMethod clinit = classInits.get(j);
            if (classInitializers.add(clinit)) {
                // new initializer
                cgChanged = true;
                Context c = haf.initialContext();
                CGNode initNode;
                try {
                    initNode = callGraph.findOrCreateNode(clinit, c);
                }
                catch (CancelException e) {
                    throw new RuntimeException(e);
                }
                recordContext(clinit, c);
                callGraph.registerEntrypoint(initNode);
                clinitCount++;
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

    static class FilteredSet extends AbstractSet<InstanceKey> {
        final Set<InstanceKey> s;
        final TypeFilter filter;

        FilteredSet(Set<InstanceKey> s, TypeFilter filter) {
            this.filter = filter;
            if (TypeFilter.IMPOSSIBLE.equals(filter)) {
                // nothing will satisfy this filter.
                this.s = Collections.emptySet();
            }
            else {
                this.s = s;
            }
        }

        @Override
        public Iterator<InstanceKey> iterator() {
            return new FilteredIterator(s.iterator(), filter);
        }

        @Override
        public boolean contains(Object o) {
            return s.contains(o)
                    && filter.satisfies(((InstanceKey) o).getConcreteType());
        }

        @Override
        public int size() {
            throw new UnsupportedOperationException();
        }
    }

    static class FilteredIterator implements Iterator<InstanceKey> {
        private final Iterator<InstanceKey> iter;
        private final TypeFilter filter;
        private InstanceKey next = null;

        FilteredIterator(Iterator<InstanceKey> iter, TypeFilter filter) {
            this.filter = filter;
            if (TypeFilter.IMPOSSIBLE.equals(filter)) {
                // nothing will satisfy this filter.
                this.iter = Collections.emptyIterator();
            }
            else {
                this.iter = iter;
            }

        }

        @Override
        public boolean hasNext() {
            while (next == null && iter.hasNext()) {
                InstanceKey ik = iter.next();
                if (filter.satisfies(ik.getConcreteType())) {
                    next = ik;
                }
            }

            return next != null;
        }

        @Override
        public InstanceKey next() {
            if (hasNext()) {
                InstanceKey x = next;
                next = null;
                return x;
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    static Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> composeIterators(
            Set<PointsToGraphNode> unfilteredSet,
            Set<OrderedPair<PointsToGraphNode, TypeFilter>> filteredSet) {
        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> unfilteredIterator =
                unfilteredSet == null
                        ? null
                        : new LiftUnfilteredIterator(unfilteredSet.iterator());
        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> filteredIterator =
                filteredSet == null ? null : filteredSet.iterator();

        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> iter;
        if (unfilteredIterator == null) {
            if (filteredIterator == null) {
                iter =
                        Collections.<OrderedPair<PointsToGraphNode, TypeFilter>> emptyIterator();
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
                iter =
                        new ComposedIterators<>(unfilteredIterator,
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
            return iter1 != null && iter1.hasNext() || iter2.hasNext();
        }

        @Override
        public T next() {
            if (iter1 != null) {
                if (iter1.hasNext()) {
                    return iter1.next();
                }
                this.iter1 = null;
            }
            return iter2.next();
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
            return iter.hasNext();
        }

        @Override
        public OrderedPair<PointsToGraphNode, TypeFilter> next() {
            return new OrderedPair<>(iter.next(), (TypeFilter) null);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Return the set of InstanceKeys that are in source (and satisfy filter) but are not in target.
     * @param source
     * @param filter
     * @param target
     * @return
     */
    Set<InstanceKey> getDifference(PointsToGraphNode source, TypeFilter filter,
            PointsToGraphNode target) {
        source = getRepresentative(source);

        Set<InstanceKey> s = cache.getPointsToSet(source);
        Iterator<InstanceKey> srcIter;
        if (filter == null) {
            srcIter = s.iterator();
        }
        else {
            srcIter = new FilteredIterator(s.iterator(), filter);
        }
        return getDifference(srcIter, target);

    }

    private Set<InstanceKey> getDifference(Iterator<InstanceKey> srcIter,
            PointsToGraphNode target) {
        target = getRepresentative(target);

        if (!srcIter.hasNext()) {
            // nothing in there, return an empty set.
            return Collections.emptySet();
        }

        Set<InstanceKey> trg = cache.getPointsToSet(target);

        Set<InstanceKey> s = new LinkedHashSet<>();
        while (srcIter.hasNext()) {
            InstanceKey i = srcIter.next();
            if (!trg.contains(i)) {
                s.add(i);
            }
        }
        return s;

    }

    /**
     * Return whatever is in set which is not in the points to set of target.
     * @param set
     * @param target
     * @return
     */
    public Set<InstanceKey> getDifference(Set<InstanceKey> set,
            PointsToGraphNode target) {
        return getDifference(set.iterator(), target);
    }

    /**
     * This class implements a cache for realized points-to sets, using SoftReferences
     */
    private class RealizedSetCache {
        private static final int RECENTLY_USED_LIMIT = 50000;
        private final Map<PointsToGraphNode, SoftReference<Set<InstanceKey>>> cache =
                new HashMap<>();
        private int hits = 0;
        private int misses = 0;
        private int evictions = 0;
        private int uncachable = 0;

        /*
         * The following maps are used to have hard reference to sets, to stop them being garbage collected,
         * i.e., to keep them in the cache.
         */
        private Map<PointsToGraphNode, Set<InstanceKey>> inCycle =
                new HashMap<>();
        private Map<PointsToGraphNode, Set<InstanceKey>> recentlyUsedMap =
                new LinkedHashMap<PointsToGraphNode, Set<InstanceKey>>(RECENTLY_USED_LIMIT) {

                    @Override
                    protected boolean removeEldestEntry(
                            Entry<PointsToGraphNode, Set<InstanceKey>> eldest) {
                        return size() > RECENTLY_USED_LIMIT; // only keep the most recently accessed nodes.
                    }

                };

        /**
         * Update or invalidate caches based on the changes represented by 
         * the graph delta
         */
        private void updateForDelta(GraphDelta delta) {
            Iterator<PointsToGraphNode> iter = delta.domainIterator();
            while (iter.hasNext()) {
                PointsToGraphNode n = iter.next();
                Set<InstanceKey> deltaSet = delta.pointsToSet(n);
                SoftReference<Set<InstanceKey>> sr = cache.get(n);
                if (sr != null) {
                    Set<InstanceKey> s = sr.get();
                    // the set is in the cache!
                    s.addAll(deltaSet);
                    recentlyUsedMap.put(n, s);
                    // no need to update inCycle, since it is the same set s.
                }
            }
        }

        /**
         * Node n has been removed (e.g., combined with another node
         */
        public void removed(PointsToGraphNode n) {
            cache.remove(n);
            recentlyUsedMap.remove(n);
            inCycle.remove(n);
        }

        /*
                Set<InstanceKey> getPointsToSetIfNotEvicted(PointsToGraphNode n) {
                    SoftReference<Set<InstanceKey>> sr = cache.get(n);
                    if (sr != null) {
                        Set<InstanceKey> s = sr.get();
                        if (s != null) {
                            // put it in the recently used...
                            recentlyUsedMap.put(n, s);
                        }
                        return s;
                    }
                    // the points to set hasn't been evicted, so lets get it.
                    return getPointsToSet(n);
                }*/

        public Set<InstanceKey> getPointsToSet(PointsToGraphNode n) {
            return realizePointsToSet(n,
                                      new HashSet<PointsToGraphNode>(),
                                      new Stack<PointsToGraphNode>(),
                                      new Stack<TypeFilter>(),
                                      new Stack<Boolean>());

        }

        private Set<InstanceKey> realizePointsToSet(PointsToGraphNode n,
                Set<PointsToGraphNode> currentlyRealizing,
                Stack<PointsToGraphNode> currentlyRealizingStack,
                Stack<TypeFilter> filters, Stack<Boolean> safeToCache) {
            assert !representative.containsKey(n) : "Getting points to set of node that has been merged with another node.";

            try {
                SoftReference<Set<InstanceKey>> sr = cache.get(n);
                if (sr != null) {
                    Set<InstanceKey> s = sr.get();
                    if (s != null) {
                        // we have it in the cache!
                        hits++;
                        // put it in the recently used...
                        recentlyUsedMap.put(n, s);
                        return s;
                    }
                }

                if (currentlyRealizing.contains(n)) {
                    // we are called recursively. Need to handle this specially.
                    // find the index that n first appears at, and compute the effective filter on the cycle.
                    int foundAt = -1;
                    TypeFilter filter = null;
                    for (int i = 0; i < currentlyRealizing.size(); i++) {
                        if (foundAt < 0
                                && currentlyRealizingStack.get(i).equals(n)) {
                            foundAt = i;
                            filter = filters.get(i);
                        }
                        else if (foundAt >= 0) {
                            // it is not safe to cache the result of i. (but will be ok to cache foundAt).
                            safeToCache.setElementAt(false, i);
                            filter = TypeFilter.compose(filter, filters.get(i));
                        }
                    }
                    // return an empty set, which will let us compute the realization of the
                    // point to set.
                    return Collections.emptySet();
                }
                boolean baseContains = base.containsKey(n);
                boolean hasUnfilteredSupersetRelns =
                        isUnfilteredSupersetOf.containsKey(n);
                boolean hasFilteredSupersetRelns = isSupersetOf.containsKey(n);

                // A case that shouldn't be true...
                if (!baseContains && !hasUnfilteredSupersetRelns
                        && !hasFilteredSupersetRelns) {
                    // doesn't point to anything.
                    return Collections.emptySet();
                }

                if (baseContains
                        && !(hasUnfilteredSupersetRelns || hasFilteredSupersetRelns)) {
                    // n is a base node, and no superset relations
                    return base.get(n);
                }

                // We now know we definitely missed in the cache (i.e., not a base node).
                misses++;
                if (cache.get(n) != null) {
                    evictions++;
                }

                Set<InstanceKey> s = AnalysisUtil.createConcurrentSet();
                if (baseContains) {
                    s.addAll(base.get(n));
                }
                safeToCache.push(true);
                addSubSets(n,
                           s,
                           currentlyRealizing,
                           currentlyRealizingStack,
                           filters,
                           safeToCache);
                if (safeToCache.pop()) {
                    // it is safe for us to cache the result of this realization.
                    cache.put(n, new SoftReference<>(s));
                    recentlyUsedMap.put(n, s);
                    if (inCycle.containsKey(n)) {
                        inCycle.put(n, s);
                    }
                }
                else {
                    uncachable++;
                }
                return s;

            }
            finally {
                if ((hits + misses) % 5000000 == 0 && hits + misses > 0) {
                    System.err.println("  Cache: " + (hits + misses)
                            + " accesses: " + 100 * hits / (hits + misses)
                            + "% hits; " + evictions + " of " + misses
                            + " misses due to evictions; " + uncachable
                            + " of the misses were uncachable; cache size: "
                            + cache.size());
                    hits = misses = evictions = uncachable = 0;
                }
            }

        }

        private void addSubSets(PointsToGraphNode n, Set<InstanceKey> s,
                Set<PointsToGraphNode> currentlyRealizing,
                Stack<PointsToGraphNode> currentlyRealizingStack,
                Stack<TypeFilter> filters, Stack<Boolean> safeToCache) {
            currentlyRealizing.add(n);
            currentlyRealizingStack.push(n);
            // go through the superset relations and add them

            Set<PointsToGraphNode> unfiltered = isUnfilteredSupersetOf.get(n);
            if (unfiltered != null) {
                filters.push(null);
                for (PointsToGraphNode x : unfiltered) {
                    s.addAll(realizePointsToSet(x,
                                                currentlyRealizing,
                                                currentlyRealizingStack,
                                                filters,
                                                safeToCache));
                }
                filters.pop();
            }
            Set<OrderedPair<PointsToGraphNode, TypeFilter>> filtered =
                    isSupersetOf.get(n);
            if (filtered != null) {
                for (OrderedPair<PointsToGraphNode, TypeFilter> p : filtered) {
                    filters.push(p.snd());
                    s.addAll(new FilteredSet(realizePointsToSet(p.fst(),
                                                                currentlyRealizing,
                                                                currentlyRealizingStack,
                                                                filters,
                                                                safeToCache),
                                             p.snd()));
                    filters.pop();

                }
            }
            currentlyRealizing.remove(n);
            currentlyRealizingStack.pop();
        }

        public void inCycle(PointsToGraphNode n) {
            inCycle.put(n, getPointsToSet(n));
        }
    }

    public int cycleRemovalCount() {
        return representative.size();
    }

    /**
     * Notify the cache that a node is in a cycle.
     * @param n
     */
    public void inCycle(PointsToGraphNode n) {
        cache.inCycle(n);

    }
}
