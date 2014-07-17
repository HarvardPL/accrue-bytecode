package analysis.pointer.graph;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.intset.IntSet;

public class ThreadSafeCallGraph implements CallGraph {

    /**
     * Utility classes and operations, including a pointer to the IR cache
     */
    final WalaAnalysisUtil util;
    /**
     * All nodes in the call graph
     */
    private final ConcurrentHashMap<OrderedPair<IMethod, Context>, CGNode> allNodes;
    /**
     * Caller call graph nodes and the set of methods called from them
     */
    private final ConcurrentHashMap<CGNode, Set<CGNode>> allCallees;
    /**
     * Synthetic root method that calls application "main" method
     */
    private final CGNode fakeRoot;
    /**
     * Entry points to the call graph (root node and class initializers)
     */
    private final Set<CGNode> entryPoints;

    /**
     * Create and initialize a new call graph where contexts are created using the given {@link HeapAbstractionFactory}
     * 
     * @param util
     *            Utility classes
     * @param haf
     *            Heap abstraction factory
     */
    public ThreadSafeCallGraph(WalaAnalysisUtil util, HeapAbstractionFactory haf) {
        this.util = util;
        this.fakeRoot = findOrCreateNode(util.getFakeRoot(), haf.initialContext());
        registerEntryPoint(fakeRoot);
        allNodes = makeConcurrentHashMap();
        allCallees = makeConcurrentHashMap();
        entryPoints = makeConcurrentSet();
    }

    /**
     * Find a node in the call graph for the given method and context, create a new one if it does not exist
     * 
     * @param method
     *            resolved method
     * @param context
     *            analysis context
     * @return call graph node for the given method and context
     */
    private CGNode findOrCreateNode(IMethod method, Context context) {
        OrderedPair<IMethod, Context> key = new OrderedPair<>(method, context);
        CGNode n = allNodes.get(key);
        if (n == null) {
            // keep it private to prevent creation of call graph nodes outside this method
            @SuppressWarnings("synthetic-access")
            CallGraphNode newNode = new CallGraphNode(method, context);
            n = allNodes.putIfAbsent(key, newNode);
            if (n == null) {
                // the key wasn't in the map and newNode is now in the map
                n = newNode;
            }
        }
        return n;
    }

    /**
     * This iterator is "weakly consistent" which means that it reflects a snapshot of the call graph at creation time
     * and may or may not reflect later changes to the underlying call graph.
     * <p>
     * {@inheritDoc}
     */
    @Override
    public Iterator<CGNode> iterator() {
        return allNodes.values().iterator();
    }

    /**
     * Number of call graph nodes
     * 
     * @return total number of nodes in the call graph
     */
    @Override
    public int getNumberOfNodes() {
        return allNodes.values().size();
    }

    @Override
    public CGNode getFakeRootNode() {
        return fakeRoot;
    }

    @Override
    public Collection<CGNode> getEntrypointNodes() {
        return entryPoints;
    }

    /**
     * Add a new entry point to the call graph (most likely a class initializer)
     * 
     * @param n
     *            node to add as an entry point
     */
    public void registerEntryPoint(CGNode n) {
        entryPoints.add(n);
    }

    @Override
    public CGNode getNode(IMethod method, Context C) {
        return findOrCreateNode(method, C);
    }

    @Override
    public IClassHierarchy getClassHierarchy() {
        return util.getClassHierarchy();
    }

    @Override
    public Set<CGNode> getPossibleTargets(CGNode node, CallSiteReference site) {
        assert node instanceof CallGraphNode;
        return ((CallGraphNode) node).getPossibleTargets(site);
    }

    @Override
    public Iterator<CGNode> getSuccNodes(CGNode n) {
        Set<CGNode> callees = allCallees.get(n);
        if (callees == null) {
            return Collections.emptyIterator();
        }
        return callees.iterator();
    }

    @Override
    public boolean containsNode(CGNode n) {
        return allNodes.contains(n);
    }

    @Override
    public boolean hasEdge(CGNode src, CGNode dst) {
        Set<CGNode> callees = allCallees.get(src);
        if (callees == null) {
            return false;
        }
        return callees.contains(dst);
    }

    @Override
    public int getSuccNodeCount(CGNode N) {
        Set<CGNode> callees = allCallees.get(N);
        if (callees == null) {
            return 0;
        }
        return callees.size();
    }

    @Override
    public int getNumberOfTargets(CGNode node, CallSiteReference site) {
        return getPossibleTargets(node, site).size();
    }

    @Override
    public Iterator<CallSiteReference> getPossibleSites(CGNode src, CGNode target) {
        Set<CallSiteReference> callSites = new LinkedHashSet<>();
        Iterator<CallSiteReference> iter = src.iterateCallSites();
        while (iter.hasNext()) {
            CallSiteReference site = iter.next();
            if (getPossibleTargets(src, site).contains(target)) {
                callSites.add(site);
            }
        }
        return callSites.iterator();
    }

    /**
     * Create a concurrent hash map with some reasonable parameters, including the number of available processors
     * 
     * @return new concurrent hash map
     */
    static <W, T> ConcurrentHashMap<W, T> makeConcurrentHashMap() {
        return new ConcurrentHashMap<>(16, 0.75f, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Create a set backed by a ConcurrentHashMap
     * 
     * @return new set backed by a ConcurrentHashMap
     */
    static <W> Set<W> makeConcurrentSet() {
        Map<W, Boolean> m = makeConcurrentHashMap();
        return Collections.<W> newSetFromMap(m);
    }

    /**
     * Print the call graph in graphviz dot format to a file
     * 
     * @param filename
     *            name of the file, the file is put in tests/filename.dot
     * @param addDate
     *            if true then the date will be added to the filename
     */
    public void dumpCallGraphToFile(String filename, boolean addDate) {
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
            dumpCallGraph(out);
            System.err.println("\nDOT written to: " + fullFilename);
        } catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    /**
     * Write the call graph to the given writer
     * 
     * @param writer
     *            writer to write to
     * @throws IOException
     *             writing problem
     */
    private void dumpCallGraph(Writer writer) throws IOException {
        double spread = 1.0;
        writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
                                        + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n"
                                        + "edge [fontsize=10]" + ";\n");

        Map<String, Integer> dotToCount = new HashMap<>();
        Map<CGNode, String> n2s = new HashMap<>();

        // Need to differentiate between different nodes with the same string
        for (CGNode n : this) {
            String nStr = escape(PrettyPrinter.cgNodeString(n));
            Integer count = dotToCount.get(nStr);
            if (count == null) {
                dotToCount.put(nStr, 1);
            } else {
                nStr += " (" + count + ")";
                dotToCount.put(nStr, count + 1);
            }
            n2s.put(n, nStr);
        }

        for (CGNode source : this) {
            Iterator<CGNode> iter = this.getSuccNodes(source);
            while (iter.hasNext()) {
                CGNode target = iter.next();
                writer.write("\t\"" + n2s.get(source) + "\" -> \"" + n2s.get(target) + "\";\n");
            }
        }

        writer.write("\n};\n");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    @Deprecated
    public Iterator<CGNode> getPredNodes(CGNode n) {
        // TODO getPredNodes(CGNode n)
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public int getPredNodeCount(CGNode n) {
        // TODO getPredNodeCount(CGNode n)
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public Set<CGNode> getNodes(MethodReference m) {
        // TODO getNodes(MethodReference m)
        throw new UnsupportedOperationException();
    }

    @Override
    @Deprecated
    public void addNode(CGNode n) {
        throw new UnsupportedOperationException("use findOrCreateNode");
    }

    @Override
    @Deprecated
    public void removeNodeAndEdges(CGNode n) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This call graph is write only");
    }

    @Override
    @Deprecated
    public void removeNode(CGNode n) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This call graph is write only");
    }

    @Override
    @Deprecated
    public void addEdge(CGNode src, CGNode dst) {
        throw new UnsupportedOperationException("Use addPossibleTarget on src CGNode after calling findOrCreateNode");
    }

    @Override
    @Deprecated
    public void removeEdge(CGNode src, CGNode dst) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This call graph is write only");
    }

    @Override
    @Deprecated
    public void removeAllIncidentEdges(CGNode node) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This call graph is write only");
    }

    @Override
    @Deprecated
    public void removeIncomingEdges(CGNode node) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This call graph is write only");
    }

    @Override
    @Deprecated
    public void removeOutgoingEdges(CGNode node) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This call graph is write only");
    }

    @Override
    @Deprecated
    public int getNumber(CGNode N) {
        throw new UnsupportedOperationException("Nodes are not numbered");
    }

    @Override
    @Deprecated
    public CGNode getNode(int number) {
        throw new UnsupportedOperationException("Nodes are not numbered");
    }

    @Override
    @Deprecated
    public int getMaxNumber() {
        throw new UnsupportedOperationException("Nodes are not numbered");
    }

    @Override
    @Deprecated
    public Iterator<CGNode> iterateNodes(IntSet s) {
        throw new UnsupportedOperationException("Nodes are not numbered");
    }

    @Override
    @Deprecated
    public IntSet getSuccNodeNumbers(CGNode node) {
        throw new UnsupportedOperationException("Nodes are not numbered");
    }

    @Override
    @Deprecated
    public IntSet getPredNodeNumbers(CGNode node) {
        throw new UnsupportedOperationException("Nodes are not numbered");
    }

    /**
     * Simple call graph node, a (method, context) pair
     */
    private class CallGraphNode implements CGNode {

        /**
         * Method
         */
        private final IMethod m;
        /**
         * Context
         */
        private final Context c;
        /**
         * Def use information for this call graph node
         */
        private WeakReference<DefUse> du = new WeakReference<>(null);
        /**
         * IR for this call graph node
         */
        private WeakReference<IR> ir = new WeakReference<>(null);
        /**
         * Map from call site in the method for this CG node to callee
         */
        private final ConcurrentHashMap<CallSiteReference, Set<CGNode>> allTargets;
        /**
         * Node ID
         */
        private int id;

        /**
         * Create a call graph node from a method and context.
         * 
         * @param m
         *            method
         * @param c
         *            context
         */
        private CallGraphNode(IMethod m, Context c) {
            this.m = m;
            this.c = c;
            this.allTargets = makeConcurrentHashMap();
        }

        /**
         * Get all callees from a given call site in the method this call graph node represents
         * 
         * @param site
         *            call site
         * @return set of possible callee (method, context) pairs
         */
        public Set<CGNode> getPossibleTargets(CallSiteReference site) {
            Set<CGNode> targets = allTargets.get(site);
            if (targets == null) {
                return Collections.emptySet();
            }
            return targets;
        }

        @Override
        public int getGraphNodeId() {
            return id;
        }

        @Override
        public void setGraphNodeId(int number) {
            this.id = number;
        }

        @Override
        public IClassHierarchy getClassHierarchy() {
            return getClassHierarchy();
        }

        @Override
        public IMethod getMethod() {
            return m;
        }

        @Override
        public Context getContext() {
            return c;
        }

        @Override
        public boolean addTarget(CallSiteReference site, CGNode target) {
            Set<CGNode> targets = allTargets.get(site);
            if (targets == null) {
                Set<CGNode> s = makeConcurrentSet();
                targets = allTargets.putIfAbsent(site, s);
                if (targets == null) {
                    // key was missing and s was put into the map
                    targets = s;
                }
            }
            return targets.add(target);
        }

        @Override
        public IR getIR() {
            IR ir = this.ir.get();
            if (ir == null) {
                ir = util.getIR(m);
                this.ir = new WeakReference<>(ir);
            }
            return ir;
        }

        @Override
        public DefUse getDU() {
            DefUse du = this.du.get();
            if (du == null) {
                du = util.getDefUse(m);
                this.du = new WeakReference<>(du);
            }
            return du;
        }

        @Override
        public Iterator<NewSiteReference> iterateNewSites() {
            return getIR().iterateNewSites();
        }

        @Override
        public Iterator<CallSiteReference> iterateCallSites() {
            return getIR().iterateCallSites();
        }

    }
}
