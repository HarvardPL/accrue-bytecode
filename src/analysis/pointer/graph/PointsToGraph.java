package analysis.pointer.graph;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.AbstractSet;
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
    private final Set<InstanceKey> allHContexts = new LinkedHashSet<>();

    private Set<PointsToGraphNode> changedNodes;
    private Set<PointsToGraphNode> readNodes;
    private Map<IMethod, Set<Context>> newContexts;
    private final Map<IMethod, Set<Context>> contexts;
    private final Set<IMethod> classInitializers;

    private final HeapAbstractionFactory haf;
    private final HafCallGraph callGraph;
    private int outputLevel = 0;

    public static boolean DEBUG = false;

    public PointsToGraph(StatementRegistrar registrar, HeapAbstractionFactory haf) {
        changedNodes = new LinkedHashSet<>();
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

    public boolean addEdge(PointsToGraphNode node, InstanceKey heapContext) {
        assert node != null && heapContext != null;
        Set<InstanceKey> pointsToSet = graph.get(node);
        if (pointsToSet == null) {
            pointsToSet = new LinkedHashSet<>();
            graph.put(node, pointsToSet);
        }

        boolean changed = pointsToSet.add(heapContext);
        if (changed) {
            changedNodes.add(node);
            allHContexts.add(heapContext);
        }
        return changed;
    }

    public boolean addEdges(PointsToGraphNode node, Set<InstanceKey> heapContexts) {
        Set<InstanceKey> pointsToSet = graph.get(node);
        if (pointsToSet == null) {
            pointsToSet = new LinkedHashSet<>();
            graph.put(node, pointsToSet);
        }
        boolean changed = pointsToSet.addAll(heapContexts);

        if (changed) {
            changedNodes.add(node);
            this.allHContexts.addAll(heapContexts);
        }
        return changed;
    }

    /**
     * 
     * @param node
     * 
     * @return Modifiable set of heap contexts that the given node points to
     */
    public Set<InstanceKey> getPointsToSet(PointsToGraphNode node) {
        readNodes.add(node);

        Set<InstanceKey> s = graph.get(node);
        if (s != null) {
            if (DEBUG && s.isEmpty() && outputLevel >= 7) {
                System.err.println("\tEMPTY POINTS-TO SET for " + node);
            }
            return Collections.unmodifiableSet(s);
        }

        if (DEBUG && outputLevel >= 7) {
            System.err.println("\tEMPTY POINTS-TO SET for " + node);
        }

        return Collections.emptySet();
    }

    public Set<InstanceKey> getPointsToSetFiltered(PointsToGraphNode node, TypeReference type) {
        readNodes.add(node);

        Set<InstanceKey> s = getPointsToSet(node);

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
        Set<InstanceKey> s = getPointsToSet(node);
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
        for (InstanceKey k : allHContexts) {
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
     * Set containing all Heap contexts
     * 
     * @return set with all the Heap contexts
     */
    public Set<InstanceKey> getAllHContexts() {
        return allHContexts;
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
     * Get the points-to graph nodes that have caused a change since this was last called and clear the set.
     * 
     * @return set of changed nodes
     */
    public Set<PointsToGraphNode> getAndClearChangedNodes() {
        Set<PointsToGraphNode> c = changedNodes;
        changedNodes = new LinkedHashSet<>();
        return c;
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
            return new FilteredIterator(s.iterator());
        }

        boolean satisfiesFilters(InstanceKey o) {
            if (isAssignableFrom(isType, o.getConcreteType())) {
                if (notTypes != null) {
                    for (IClass nt : notTypes) {
                        if (isAssignableFrom(nt, o.getConcreteType())) {
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
            return s.contains(o) && satisfiesFilters((InstanceKey) o);
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
            final Iterator<InstanceKey> iter;
            InstanceKey next = null;

            FilteredIterator(Iterator<InstanceKey> iter) {
                this.iter = iter;
            }

            @Override
            public boolean hasNext() {
                if (next != null) {
                    return true;
                }
                while (iter.hasNext()) {
                    InstanceKey ik = iter.next();
                    if (satisfiesFilters(ik)) {
                        this.next = ik;
                        return true;
                    }
                }
                return false;
            }

            @Override
            public InstanceKey next() {
                if (hasNext()) {
                    InstanceKey ik = this.next;
                    this.next = null;
                    return ik;
                }
                throw new NoSuchElementException();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        }
    }
}
