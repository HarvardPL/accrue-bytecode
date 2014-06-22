package analysis.pointer.graph;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import util.OrderedPair;
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

/**
 * Graph mapping local variables (in a particular context) and fields to abstract heap locations (representing zero or
 * more actual heap locations)
 */
public class PointsToGraph {

    public static final String ARRAY_CONTENTS = "[contents]";
    /**
     * Underlying data structure for the points-to graph. It is threadsafe.
     */
    private final ConcurrentHashMap<PointsToGraphNode, Set<InstanceKey>> graph = AnalysisUtil.createConcurrentHashMap();;

    /**
     * The contexts that a method may appear in.
     */
    private final ConcurrentHashMap<IMethod, Set<Context>> contexts = AnalysisUtil.createConcurrentHashMap();;

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
    private Map<PointsToGraphNode, Set<OrderedPair<PointsToGraphNode, TypeFilter>>> copies;

    private Map<IMethod, Set<Context>> newContexts;

    private int outputLevel = 0;

    public static boolean DEBUG = false;

    public PointsToGraph(StatementRegistrar registrar, HeapAbstractionFactory haf) {
        readNodes = new LinkedHashSet<>();
        copies = new LinkedHashMap<>();
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

    /**
     * Add an edge from node to heapContext in the graph.
     * 
     * @param node
     * @param heapContext
     * @return
     */
    public GraphDelta addEdge(PointsToGraphNode node, InstanceKey heapContext) {
        assert node != null && heapContext != null;
        Set<InstanceKey> pointsToSet = getOrCreatePointsToSet(node);

        GraphDelta delta = new GraphDelta();
        if (pointsToSet.add(heapContext)) {
            delta.add(node, heapContext);
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
        recordCopy(source, target);
        
        Set<InstanceKey> trgPointsToSet = getOrCreatePointsToSet(target);
        Set<InstanceKey> srcPointsToSet = getOrCreatePointsToSet(source);

        GraphDelta changed = new GraphDelta();
        for (InstanceKey hc : srcPointsToSet) {
            if (trgPointsToSet.add(hc)) {
                changed.add(target, hc);
            }
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
        recordCopy(source, filter, target);

        Set<InstanceKey> trgPointsToSet = getOrCreatePointsToSet(target);
        Set<InstanceKey> srcPointsToSet = getOrCreatePointsToSet(source);

        GraphDelta changed = new GraphDelta();
        for (InstanceKey hc : new FilteredSet(srcPointsToSet, filter)) {
            if (trgPointsToSet.add(hc)) {
                changed.add(target, hc);
            }
        }
        return changed;
    }


    public GraphDelta copyFilteredEdgesWithDelta(PointsToGraphNode source, TypeFilter filter,
                                    PointsToGraphNode target, GraphDelta delta) {
        if (delta == null) {
            return copyFilteredEdges(source, filter, target);
        }

        Set<InstanceKey> trgPointsToSet = getOrCreatePointsToSet(target);
        Set<InstanceKey> srcPointsToSet = delta.getPointsToSet(source);

        GraphDelta changed = new GraphDelta();
        for (InstanceKey hc : new FilteredSet(srcPointsToSet, filter)) {
            if (trgPointsToSet.add(hc)) {
                changed.add(target, hc);
            }
        }
        return changed;
    }


    public GraphDelta copyEdgesWithDelta(PointsToGraphNode source, PointsToGraphNode target, GraphDelta delta) {
        if (delta == null) {
            return copyEdges(source, target);
        }

        Set<InstanceKey> trgPointsToSet = getOrCreatePointsToSet(target);
        Set<InstanceKey> srcPointsToSet = delta.getPointsToSet(source);

        GraphDelta changed = new GraphDelta();
        for (InstanceKey hc : srcPointsToSet) {
            if (trgPointsToSet.add(hc)) {
                changed.add(target, hc);
            }
        }
        return changed;
    }

    /**
     * 
     * @param node
     * 
     * @return Set of heap contexts that the given node points to
     */
    public Set<InstanceKey> getPointsToSet(PointsToGraphNode node) {
        recordRead(node);

        Set<InstanceKey> s = graph.get(node);
        if (s != null) {
            if (DEBUG && s.isEmpty() && outputLevel >= 7) {
                System.err.println("\tEMPTY POINTS-TO SET for " + node);
            }
            return s;
        }

        if (DEBUG && outputLevel >= 7) {
            System.err.println("\tEMPTY POINTS-TO SET for " + node);
        }

        return Collections.emptySet();
    }

    /**
     * Return a pointsto set for the given node. If delta is non-null, then delta is used, i.e., only the things that
     * node points to in the delta will be used. If delta is null, then the behavior is the same as getPointsToSet.
     * 
     * @param node
     * @param delta
     * 
     * @return Set of heap contexts that the given node points to, restricted to the delta if that is provided
     */
    public Set<InstanceKey> getPointsToSetWithDelta(PointsToGraphNode node, GraphDelta delta) {
        if (delta == null) {
            return this.getPointsToSet(node);
        }

        return delta.getPointsToSet(node);
    }

    /**
     * Get the points-to set for the given points-to graph node, filtering out results which do not have a particular
     * type, or which have one of a set of types
     * 
     * @param node
     *            node to get the points-to set for
     * @param filter
     *            Filter to specify what type the heap objects must be, and, optionally, must not be.
     * @return Set of heap contexts filtered based on type
     */
    public Set<InstanceKey> getPointsToSetFiltered(PointsToGraphNode node, TypeFilter filter) {
        recordRead(node);
        Set<InstanceKey> s = this.getPointsToSet(node);
        if (s.isEmpty()) {
            return s;
        }

        return new FilteredSet(s, filter);
    }

    public Set<InstanceKey> getPointsToSetFilteredWithDelta(PointsToGraphNode node, TypeFilter filter, GraphDelta delta) {
        if (delta == null) {
            return this.getPointsToSetFiltered(node, filter);
        }

        Set<InstanceKey> s = delta.getPointsToSet(node);

        if (s.isEmpty()) {
            return s;
        }
        return new FilteredSet(s, filter);
    }

    /**
     * XXXX DOCO TODO.
     * 
     * This method is synchronized because it uses callGraph, which is not thread safe. We thus use the the lock on this
     * object to protect access to callGraph.
     */
    @SuppressWarnings("deprecation")
    public synchronized boolean addCall(CallSiteReference callSite, IMethod caller, Context callerContext,
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

    private Set<InstanceKey> getOrCreatePointsToSet(PointsToGraphNode node) {
        // Double-Checked Lock pattern, works with ConcurrentHashMap in Java 5.0 and later
        Set<InstanceKey> set = this.graph.get(node);
        if (set == null) {
            // Set does not yet exist
            Set<InstanceKey> newSet = new PointsToSet();

            // It's possible that another thread created the Set for the key, so add it carefully
            set = this.graph.putIfAbsent(node, newSet);
            if (set == null) {
                // put succeeded, use new value
                set = newSet;
            }
        }
        return set;
    }

    private Set<Context> getOrCreateContextSet(IMethod callee) {
        return PointsToGraph.<IMethod, Context> getOrCreateSet(callee, this.contexts);
    }

    private static <K, T> Set<T> getOrCreateSet(K key, ConcurrentHashMap<K, Set<T>> map) {
        // Double-Checked Lock pattern, works with ConcurrentHashMap in Java 5.0 and later
        Set<T> set = map.get(key);
        if (set == null) {
            // Set does not yet exist
            Set<T> newSet = AnalysisUtil.createConcurrentSet();

            // It's possible that another thread created the Set for the key, so add it carefully
            set = map.putIfAbsent(key, newSet);
            if (set == null) {
                // put succeeded, use new value
                set = newSet;
            }
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

    /**
     * Get a set containing all the points-to graph nodes
     * 
     * @return points-to graph nodes
     */
    public Set<PointsToGraphNode> getNodes() {
        return graph.keySet();
    }

    /**
     * Print the graph in graphviz dot format to a file
     * 
     * @param filename
     *            name of the file, the file is put in tests/filename.dot
     * @param addDate
     *            if true then the date will be added to the filename
     */
    public void dumpPointsToGraphToFile(String filename, boolean addDate) {
        String dir = "tests";
        String file = filename;
        if (addDate) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("-yyyy-MM-dd-HH_mm_ss");
            Date dateNow = new Date();
            String now = dateFormat.format(dateNow);
            file += now;
        }
        String fullFilename = dir + "/" + file + ".dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            dumpPointsToGraph(out);
            System.err.println("\nDOT written to: " + fullFilename);
        } catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Writer dumpPointsToGraph(Writer writer) throws IOException {
        double spread = 1.0;
        writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
                                        + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n"
                                        + "edge [fontsize=10]" + ";\n");

        Map<String, Integer> dotToCount = new HashMap<>();
        Map<PointsToGraphNode, String> n2s = new HashMap<>();
        Map<InstanceKey, String> k2s = new HashMap<>();

        // Need to differentiate between different nodes with the same string
        for (PointsToGraphNode n : graph.keySet()) {
            String nStr = escape(n.toString());
            Integer count = dotToCount.get(nStr);
            if (count == null) {
                dotToCount.put(nStr, 1);
            } else {
                dotToCount.put(nStr, count + 1);
                nStr += " (" + count + ")";
            }
            n2s.put(n, nStr);
        }
        for (InstanceKey k : getAllHContexts()) {
            String kStr = escape(k.toString());
            Integer count = dotToCount.get(kStr);
            if (count == null) {
                dotToCount.put(kStr, 1);
            } else {
                dotToCount.put(kStr, count + 1);
                kStr += " (" + count + ")";
            }
            k2s.put(k, kStr);
        }

        for (PointsToGraphNode n : graph.keySet()) {
            for (InstanceKey ik : graph.get(n)) {
                writer.write("\t\"" + n2s.get(n) + "\" -> " + "\"" + k2s.get(ik) + "\";\n");
            }
        }

        writer.write("\n};\n");
        return writer;
    }

    /**
     * Set containing all Heap contexts. This is really expensive. Don't do it unless debugging small graphs.
     * 
     * @return set with all the Heap contexts
     */
    public Set<InstanceKey> getAllHContexts() {
        Set<InstanceKey> all = new LinkedHashSet<>();

        for (Set<InstanceKey> s : graph.values()) {
            all.addAll(s);
        }
        return all;
    }

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

    /**
     * Get the map of copies that have been made since this was last called and clear the map.
     * 
     * @return map describing the copies from source to target(s).
     */
    public Map<PointsToGraphNode, Set<OrderedPair<PointsToGraphNode, TypeFilter>>> getAndClearCopies() {
        Map<PointsToGraphNode, Set<OrderedPair<PointsToGraphNode, TypeFilter>>> c = copies;
        copies = new LinkedHashMap<>();
        return c;
    }

    private void recordCopy(PointsToGraphNode source, PointsToGraphNode target) {
        recordCopy(source, null, target);
    }

    private void recordCopy(PointsToGraphNode source, TypeFilter filter, PointsToGraphNode target) {
        Set<OrderedPair<PointsToGraphNode, TypeFilter>> s = this.copies.get(source);
        if (s == null) {
            s = new LinkedHashSet<>();
            this.copies.put(source, s);
        }
        s.add(new OrderedPair<PointsToGraphNode, TypeFilter>(target, filter));
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

    private static class FilteredSet extends AbstractSet<InstanceKey> {
        final Set<InstanceKey> s;
        final TypeFilter filter;

        FilteredSet(Set<InstanceKey> s, TypeFilter filter) {
            this.s = s;
            this.filter = filter;
        }

        @Override
        public Iterator<InstanceKey> iterator() {
            if (s instanceof PointsToSet) {
                return new FilteredPTSIterator((PointsToSet) s);
            }
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

        class FilteredPTSIterator implements Iterator<InstanceKey> {
            PointsToSet pts;
            final Iterator<IClass> it;
            Iterator<InstanceKey> current = null;

            FilteredPTSIterator(PointsToSet pts) {
                this.pts = pts;
                this.it = pts.map.keySet().iterator();
            }

            @Override
            public boolean hasNext() {
                // we can get away with an if instead of a while here, since we know that the sets are nonempty...
                if (current == null || !current.hasNext()) {
                    // find the next key that satisfies the filters
                    current = null;
                    while (it.hasNext()) {
                        IClass ic = it.next();
                        if (filter.satisfies(ic)) {
                            current = pts.map.get(ic).iterator();
                            break;
                        }
                    }
                    if (current == null && !it.hasNext()) {
                        return false;
                    }
                }

                return current.hasNext();
            }


            @Override
            public InstanceKey next() {
                if (hasNext()) {
                    return current.next();
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
     * Specialized implementation of set for this points to graph. This implementation is thread safe.
     */
    private static class PointsToSet implements Set<InstanceKey> {
        /**
         * Map from concrete classes to non-empty sets of that class.
         */
        private final ConcurrentHashMap<IClass, Set<InstanceKey>> map = AnalysisUtil.createConcurrentHashMap();


        /**
         * This is best guess, as it relies on several underlying ConcurrentHashMaps, which may be changing
         * concurrently.
         */
        @Override
        public int size() {
            int sum = 0;
            for (Set<InstanceKey> s : map.values()) {
                sum += s.size();
            }
            return sum;
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            if (o instanceof InstanceKey) {
                InstanceKey ik = (InstanceKey) o;
                Set<InstanceKey> s = map.get(ik.getConcreteType());
                if (s != null) {
                    return s.contains(ik);
                }
            }
            return false;
        }

        @Override
        public Iterator<InstanceKey> iterator() {
            return new PointsToSetIterator(this);
        }


        @Override
        public boolean add(InstanceKey e) {
            return PointsToGraph.getOrCreateSet(e.getConcreteType(), this.map).add(e);
        }

        @Override
        public Object[] toArray() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            for (Object e : c)
                if (!contains(e))
                    return false;
            return true;
        }

        @Override
        public boolean addAll(Collection<? extends InstanceKey> c) {
            boolean modified = false;
            for (InstanceKey e : c)
                if (add(e))
                    modified = true;
            return modified;

        }

        @Override
        public boolean retainAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        public boolean equals(Object o) {
            if (o == this)
                return true;

            if (!(o instanceof Set))
                return false;
            Collection c = (Collection) o;
            if (c.size() != size())
                return false;
            try {
                return containsAll(c);
            }
            catch (ClassCastException unused) {
                return false;
            }
            catch (NullPointerException unused) {
                return false;
            }
        }

        public int hashCode() {
            int h = 0;
            Iterator<InstanceKey> i = iterator();
            while (i.hasNext()) {
                InstanceKey obj = i.next();
                if (obj != null)
                    h += obj.hashCode();
            }
            return h;
        }

        public String toString() {
            Iterator<InstanceKey> it = iterator();
            if (!it.hasNext())
                return "[]";

            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (;;) {
                InstanceKey e = it.next();
                sb.append(e);
                if (!it.hasNext())
                    return sb.append(']').toString();
                sb.append(',').append(' ');
            }
        }

    }

    public static class PointsToSetIterator implements Iterator<InstanceKey> {
        final Iterator<Set<InstanceKey>> it;
        Iterator<InstanceKey> current = null;

        PointsToSetIterator(PointsToSet pts) {
            this.it = pts.map.values().iterator();
        }

        @Override
        public boolean hasNext() {
            // we can get away with an if instead of a while here, since we know that the sets are nonempty...
            if (current == null || !current.hasNext()) {
                if (!it.hasNext()) {
                    return false;
                }
                current = it.next().iterator();
                // since the sets are guaranteed to be non-empty, we know that there is at least one more element...
                return true;
            }

            return current.hasNext();
        }

        @Override
        public InstanceKey next() {
            if (hasNext()) {
                return current.next();
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

}
