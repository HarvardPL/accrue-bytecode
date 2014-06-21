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

import types.TypeRepository;
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
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;

/**
 * Graph mapping local variables (in a particular context) and fields to abstract heap locations (representing zero or
 * more actual heap locations)
 */
public class PointsToGraph {

    public static final String ARRAY_CONTENTS = "[contents]";
    private final Map<PointsToGraphNode, Set<InstanceKey>> graph = new LinkedHashMap<>();

    private Set<PointsToGraphNode> readNodes;
    private Map<IMethod, Set<Context>> newContexts;
    private final Map<IMethod, Set<Context>> contexts;
    private final Set<IMethod> classInitializers;

    private final HeapAbstractionFactory haf;
    private final HafCallGraph callGraph;
    private int outputLevel = 0;

    public static boolean DEBUG = false;

    public PointsToGraph(StatementRegistrar registrar, HeapAbstractionFactory haf) {
        readNodes = new LinkedHashSet<>();
        newContexts = new LinkedHashMap<>();
        classInitializers = new LinkedHashSet<>();
        this.haf = haf;
        contexts = getInitialContexts(haf, registrar.getInitialContextMethods());
        callGraph = new HafCallGraph(haf);
    }

    /**
     * Get a map from method to the singleton set containing the initial context for all the given methods
     * 
     * @param haf
     *            abstraction factory defining the initial context
     * @param initialMethods
     *            methods to be paired with the initial context
     * @return mapping from each method in the given set to the singleton set containing the initial context
     */
    private Map<IMethod, Set<Context>> getInitialContexts(HeapAbstractionFactory haf, Set<IMethod> initialMethods) {
        Map<IMethod, Set<Context>> init = new LinkedHashMap<>();
        for (IMethod m : initialMethods) {
            Set<Context> cs = new LinkedHashSet<>();
            cs.add(haf.initialContext());
            init.put(m, cs);
        }
        return init;
    }

    public GraphDelta addEdge(PointsToGraphNode node, InstanceKey heapContext) {
        assert node != null && heapContext != null;
        Set<InstanceKey> pointsToSet = graph.get(node);
        if (pointsToSet == null) {
            pointsToSet = new PointsToSet();
            graph.put(node, pointsToSet);
        }

        GraphDelta delta = null;
        if (pointsToSet.add(heapContext)) {
            delta = new GraphDelta(node, heapContext);
        }
        return delta;
    }

    public GraphDelta addEdges(PointsToGraphNode node, Set<InstanceKey> heapContexts) {
        Set<InstanceKey> pointsToSet = graph.get(node);
        if (pointsToSet == null) {
            pointsToSet = new PointsToSet();
            graph.put(node, pointsToSet);
        }

        GraphDelta delta = new GraphDelta();
        for (InstanceKey hc : heapContexts) {
            if (pointsToSet.add(hc)) {
                delta.add(node, hc);
            }
        }

        return delta;
    }

    /**
     * 
     * @param node
     * 
     * @return Set of heap contexts that the given node points to
     */
    public Set<InstanceKey> getPointsToSet(PointsToGraphNode node) {
        readNodes.add(node);

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

        readNodes.add(node);
        return delta.getPointsToSet(node);
    }

    public Set<InstanceKey> getPointsToSetFiltered(PointsToGraphNode node, TypeReference type) {
        readNodes.add(node);
        Set<InstanceKey> s = this.getPointsToSet(node);

        if (s.isEmpty()) {
            return s;
        }
        return new FilteredSet(s, type);
    }

    public Set<InstanceKey> getPointsToSetFilteredWithDelta(PointsToGraphNode node, TypeReference type, GraphDelta delta) {
        if (delta == null) {
            return this.getPointsToSetFiltered(node, type);
        }

        readNodes.add(node);

        Set<InstanceKey> s = delta.getPointsToSet(node);

        if (s.isEmpty()) {
            return s;
        }
        return new FilteredSet(s, type);
    }

    /**
     * Get the points-to set for the given points-to graph node, filtering out results which do not have a particular
     * type, or which have one of a set of types
     * 
     * @param node
     *            node to get the points-to set for
     * @param isType
     *            type the node must have (or be a subtype of)
     * @param notTypes
     *            the node cannot be a subclass of any of these classes
     * @return Set of heap contexts filtered based on type
     */
    public Set<InstanceKey> getPointsToSetFiltered(PointsToGraphNode node, TypeReference isType, Set<IClass> notTypes) {
        readNodes.add(node);
        Set<InstanceKey> s = this.getPointsToSet(node);
        if (s.isEmpty()) {
            return s;
        }

        return new FilteredSet(s, isType, notTypes);
    }

    public Set<InstanceKey> getPointsToSetFilteredWithDelta(PointsToGraphNode node, TypeReference isType, Set<IClass> notTypes, GraphDelta delta) {
        if (delta == null) {
            return this.getPointsToSetFiltered(node, isType, notTypes);
        }
        readNodes.add(node);
        Set<InstanceKey> s = delta.getPointsToSet(node);
        if (s.isEmpty()) {
            return s;
        }

        return new FilteredSet(s, isType, notTypes);
    }

    @SuppressWarnings("deprecation")
    public boolean addCall(CallSiteReference callSite, IMethod caller, Context callerContext, IMethod callee,
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
        final IClass isType;
        final Set<IClass> notTypes;

        FilteredSet(Set<InstanceKey> s, TypeReference isType, Set<IClass> notTypes) {
            this.s = s;
            this.isType = AnalysisUtil.getClassHierarchy().lookupClass(isType);
            this.notTypes = notTypes;
        }

        FilteredSet(Set<InstanceKey> s, TypeReference isType) {
            this(s, isType, null);
        }

        @Override
        public Iterator<InstanceKey> iterator() {
            if (s instanceof PointsToSet) {
                return new FilteredPTSIterator((PointsToSet) s);
            }
            return new FilteredIterator();
        }

        boolean satisfiesFilters(IClass concreteType) {
            if (isAssignableFrom(isType, concreteType)) {
                if (notTypes != null) {
                    for (IClass nt : notTypes) {
                        if (isAssignableFrom(nt, concreteType)) {
                            // it's assignable to a not type...
                            return false;
                        }
                    }
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean contains(Object o) {
            return s.contains(o) && satisfiesFilters(((InstanceKey) o).getConcreteType());
        }

        private boolean isAssignableFrom(IClass c1, IClass c2) {
            if (notTypes == null) {
                return AnalysisUtil.getClassHierarchy().isAssignableFrom(c1, c2);
            }
            // use caching version instead, since notTypes are
            // used for exceptions, and it's worth caching them.
            return TypeRepository.isAssignableFrom(c1, c2);
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
                    if (satisfiesFilters(ik.getConcreteType())) {
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
                        if (satisfiesFilters(ic)) {
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

    private static class PointsToSet implements Set<InstanceKey> {
        /**
         * Map from concrete classes to non-empty sets of that class.
         */
        private final Map<IClass, Set<InstanceKey>> map = new LinkedHashMap<>();


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
            IClass t = e.getConcreteType();
            Set<InstanceKey> s = this.map.get(t);
            if (s == null) {
                s = new LinkedHashSet<>();
                this.map.put(t, s);
            }
            return s.add(e);
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
