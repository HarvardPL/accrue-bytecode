package analysis.pointer.graph;

import java.util.AbstractSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final Map<PointsToGraphNode, Set<InstanceKey>> base = new LinkedHashMap<>();

    private final Map<PointsToGraphNode, Set<OrderedPair<PointsToGraphNode, TypeFilter>>> isSubsetOf = new LinkedHashMap<>();
    private final Map<PointsToGraphNode, Set<OrderedPair<PointsToGraphNode, TypeFilter>>> isSupersetOf = new LinkedHashMap<>();

    /**
     * The contexts that a method may appear in.
     */
    private final Map<IMethod, Set<Context>> contexts = new LinkedHashMap();

    /**
     * The classes that will be loaded (i.e., we need to analyze their static initializers).
     */
    private final Set<IMethod> classInitializers = AnalysisUtil.createConcurrentSet();

    /**
     * Heap abstraction factory.
     */
    private final HeapAbstractionFactory haf;

    private final HafCallGraph callGraph;


    // private final DependencyRecorder depRecorder;
    private Set<PointsToGraphNode> readNodes;
    private Map<PointsToGraphNode, Set<OrderedPair<PointsToGraphNode, TypeFilter>>> newSubsetRelations;

    private Map<IMethod, Set<Context>> newContexts;

    private int outputLevel = 0;

    public static boolean DEBUG = false;

    public PointsToGraph(StatementRegistrar registrar, HeapAbstractionFactory haf) {
        readNodes = new LinkedHashSet<>();
        newSubsetRelations = new LinkedHashMap<>();
        newContexts = new LinkedHashMap<>();

        this.haf = haf;
        this.callGraph = new HafCallGraph(haf);

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
            this.getOrCreateContextSet(m).add(haf.initialContext());
        }
    }

    public Map<PointsToGraphNode, Set<InstanceKey>> getBaseNodes() {
        return this.base;
    }

    // Return the supersets of n. That is, any node m such that n is a subset of m
    public Set<OrderedPair<PointsToGraphNode, TypeFilter>> superSetsOf(PointsToGraphNode n) {
        Set<OrderedPair<PointsToGraphNode, TypeFilter>> supersets = isSubsetOf.get(n);
        return supersets == null ? Collections.<OrderedPair<PointsToGraphNode, TypeFilter>> emptySet() : supersets;
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
        Set<InstanceKey> pointsToSet = getOrCreateBaseSet(node);

        GraphDelta delta = new GraphDelta(this);
        if (pointsToSet.add(heapContext)) {
            delta.addBase(node, heapContext);
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
    public GraphDelta copyEdges(PointsToGraphNode source, PointsToGraphNode target) {
        // source is a subset of target, target is a superset of source.
        Set<OrderedPair<PointsToGraphNode, TypeFilter>> sourceSubset = getOrCreateSubsetSet(source);
        OrderedPair<PointsToGraphNode, TypeFilter> trgFilter = new OrderedPair<>(target, null);

        GraphDelta changed = new GraphDelta(this);
        if (sourceSubset.add(trgFilter)) {
            changed.addCopyEdges(source, null, target);

            // make sure the superset relation stays consistent
            Set<OrderedPair<PointsToGraphNode, TypeFilter>> targetSuperset = getOrCreateSupersetSet(target);
            targetSuperset.add(new OrderedPair<>(source, (TypeFilter)null));

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
    public GraphDelta copyFilteredEdges(PointsToGraphNode source, TypeFilter filter, PointsToGraphNode target) {
        // source is a subset of target, target is a subset of source.
        Set<OrderedPair<PointsToGraphNode, TypeFilter>> sourceSubset = getOrCreateSubsetSet(source);
        OrderedPair<PointsToGraphNode, TypeFilter> trgFilter = new OrderedPair<>(target, filter);

        GraphDelta changed = new GraphDelta(this);
        if (sourceSubset.add(trgFilter)) {
            changed.addCopyEdges(source, filter, target);

            // make sure the superset relation stays consistent
            Set<OrderedPair<PointsToGraphNode, TypeFilter>> targetSuperset = getOrCreateSupersetSet(target);
            targetSuperset.add(new OrderedPair<>(source, filter));
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
        return new PointsToIterator(this, n);
    }

    /**
     * XXXX DOCO TODO.
     * 
     */
    @SuppressWarnings("deprecation")
    public boolean addCall(CallSiteReference callSite, IMethod caller, Context callerContext,
                                    IMethod callee,
                                    Context calleeContext) {

        CGNode src;
        CGNode dst;

        try {
            src = callGraph.findOrCreateNode(caller, callerContext);
            dst = callGraph.findOrCreateNode(callee, calleeContext);
        } catch (CancelException e) {
            throw new RuntimeException(e + " cannot add call graph edge from " + PrettyPrinter.methodString(caller)
                                            + " to " + PrettyPrinter.methodString(callee));
        }

        // We are building a call graph so it is safe to call this "deprecated" method
        if (!src.addTarget(callSite, dst)) {
            // not a new target
            return false;
        }
        if (outputLevel >= 2) {
            System.err.println("ADDED\n\t" + PrettyPrinter.methodString(caller) + " in " + callerContext + " to\n\t"
                                            + PrettyPrinter.methodString(callee) + " in " + calleeContext);
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
            System.err.println("RECORDING: " + callee + " in " + calleeContext + " hc " + calleeContext);
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
        return PointsToGraph.<PointsToGraphNode, InstanceKey> getOrCreateSet(node, this.base);
    }

    private Set<OrderedPair<PointsToGraphNode, TypeFilter>> getOrCreateSubsetSet(PointsToGraphNode node) {
        return PointsToGraph.<PointsToGraphNode, OrderedPair<PointsToGraphNode, TypeFilter>> getOrCreateSet(node,
                                        this.isSubsetOf);
    }

    private Set<OrderedPair<PointsToGraphNode, TypeFilter>> getOrCreateSupersetSet(PointsToGraphNode node) {
        return PointsToGraph.<PointsToGraphNode, OrderedPair<PointsToGraphNode, TypeFilter>> getOrCreateSet(node,
                                        this.isSupersetOf);
    }

    private Set<Context> getOrCreateContextSet(IMethod callee) {
        return PointsToGraph.<IMethod, Context> getOrCreateSet(callee, this.contexts);
    }

    private static <K, T> Set<T> getOrCreateSet(K key, Map<K, Set<T>> map) {
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
        this.readNodes.add(node);
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
                } catch (CancelException e) {
                    throw new RuntimeException(e);
                }
                recordContext(clinit, c);
                callGraph.registerEntrypoint(initNode);
                clinitCount++;
            } else {
                // Already added an initializer and thus must have added initializers for super classes. These are all
                // that are left to process since we are adding from sub class to super class order

                // If any were added then j would have been decremented
                return cgChanged;
            }
        }
        // Should always be true
        assert cgChanged : "Reached the end of the loop without adding any clinits " + classInits;
        return cgChanged;
    }

    static class FilteredSet extends AbstractSet<InstanceKey> {
        final Set<InstanceKey> s;
        final TypeFilter filter;

        FilteredSet(Set<InstanceKey> s, TypeFilter filter) {
            this.s = s;
            this.filter = filter;
        }

        @Override
        public Iterator<InstanceKey> iterator() {
            return new FilteredIterator();
        }


        @Override
        public boolean contains(Object o) {
            return s.contains(o) && filter.satisfies(((InstanceKey) o).getConcreteType());
        }

        @Override
        public int size() {
            throw new UnsupportedOperationException();
        }

        class FilteredIterator implements Iterator<InstanceKey> {
            private final Iterator<InstanceKey> iter;
            private InstanceKey next = null;
            FilteredIterator() {
                this.iter = FilteredSet.this.s.iterator();
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
    }

    /**
     * We visit using the superset relation in preorder.
     */
    public static class PointsToIterator implements Iterator<InstanceKey> {
        private final PointsToGraphNode n;
        private final PointsToGraph g;
        private final Set<OrderedPair<PointsToGraphNode, TypeFilter>> visited;
        private final Stack<Iterator<OrderedPair<PointsToGraphNode, TypeFilter>>> visitingStack;
        private Iterator<InstanceKey> currentBaseIter;
        private Stack<TypeFilter> typeFilters;

        public PointsToIterator(PointsToGraph g, PointsToGraphNode n) {
            this(g, n, null, null);
        }

        public PointsToIterator(PointsToGraph g, PointsToGraphNode n, TypeFilter filter,
                                        Set<OrderedPair<PointsToGraphNode, TypeFilter>> alreadyVisited) {

            this.g = g;
            this.n = n;
            this.visited = new HashSet<>();
            if (alreadyVisited != null) {
                visited.addAll(alreadyVisited);
            }
            this.visitingStack = new Stack<>();
            this.typeFilters = new Stack<>();
            this.typeFilters.push(null);
            // set up the initial condition, i.e., we are starting to visit n.
            startVisit(n, filter);
        }

        /**
         * Attempt to start visiting t.\
         * 
         * Precondition: currentBaseIter == null
         * 
         * Postcondition: currentBaseIter != null if and only if we actually start visiting t.
         * 
         * @param t
         * @param filter
         */
        private void startVisit(PointsToGraphNode t, TypeFilter filter) {
            // Compose the current filter (this.typeFilters.peek()), with the
            // filter for t to obtain the new filter.
            TypeFilter newFilter = TypeFilter.compose(this.typeFilters.peek(), filter);

            OrderedPair<PointsToGraphNode, TypeFilter> tAndNewFilter = new OrderedPair<>(t, filter);

            if (visited.contains(tAndNewFilter) || visited.contains(new OrderedPair<>(t, null))) {
                // we have already visited t with this particular filter (or with no filter at all).
                return;
            }
            // mark that we have visited node t with filter
            this.visited.add(tAndNewFilter);

            // push the new filter on the stack.
            this.typeFilters.push(newFilter);

            // set up the current base iterator
            Set<InstanceKey> s = g.base.get(t);
            this.currentBaseIter = s == null ? Collections.<InstanceKey> emptyIterator() : (filter == null ? s
                                            .iterator() : new FilteredSet(s, typeFilters.peek()).iterator());

            Set<OrderedPair<PointsToGraphNode, TypeFilter>> set = g.isSupersetOf.get(t);
            this.visitingStack.push(set == null ? Collections
                                            .<OrderedPair<PointsToGraphNode, TypeFilter>> emptyIterator() : set
                                            .iterator());
        }

        @Override
        public boolean hasNext() {
            while (true) {
                // first do the current base instances.
                if (currentBaseIter != null && currentBaseIter.hasNext()) {
                    return true;
                }
                currentBaseIter = null;

                // find another node to start visiting...
                if (visitingStack.isEmpty()) {
                    // nothing more to visit.
                    return false;
                }
                Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> iter = visitingStack.peek();
                if (iter.hasNext()) {
                    OrderedPair<PointsToGraphNode, TypeFilter> pair = iter.next();
                    startVisit(pair.fst(), pair.snd());
                    continue;
                }
                // The top most iterator on the stack has no more things for us to visit.
                // remove the top most element of the stack and try again.
                visitingStack.pop();
                typeFilters.pop();
            }
        }

        @Override
        public InstanceKey next() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }
            return currentBaseIter.next();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

}
