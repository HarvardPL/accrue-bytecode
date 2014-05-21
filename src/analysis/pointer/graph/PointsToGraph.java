package analysis.pointer.graph;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;

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
    private final IClassHierarchy cha;

    public static boolean DEBUG = false;

    public PointsToGraph(WalaAnalysisUtil util, StatementRegistrar registrar, HeapAbstractionFactory haf) {
        changedNodes = new LinkedHashSet<>();
        readNodes = new LinkedHashSet<>();
        newContexts = new LinkedHashMap<>();
        classInitializers = new LinkedHashSet<>();
        this.haf = haf;
        contexts = getInitialContexts(haf, registrar.getInitialContextMethods());
        callGraph = new HafCallGraph(util, haf);
        this.cha = util.getClassHierarchy();
    }

    /**
     * Get a map from method to the singleton set containing the initial context
     * for all the given methods
     * 
     * @param haf
     *            abstraction factory defining the initial context
     * @param initialMethods
     *            methods to be paired with the initial context
     * @return mapping from each method in the given set to the singleton set
     *         containing the initial context
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
        if (heapContexts.isEmpty()) {
            return false;
        }

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

        Iterator<InstanceKey> i = s.iterator();
        Set<InstanceKey> toRemove = new HashSet<>();
        while (i.hasNext()) {
            InstanceKey k = i.next();
            IClass klass = k.getConcreteType();
            if (!cha.isAssignableFrom(cha.lookupClass(type), klass)) {
                // XXX Arrays can sometimes be imprecisely labeled as Object
                // types. Imprecisely, but soundly don't remove any arrays from
                // the points-to set if the type we are filtering on is
                // java.lang.Object and vice versa
                if (!(klass.isArrayClass() && type.equals(TypeReference.JavaLangObject))) {
                    if (DEBUG && outputLevel >= 6) {
                        System.err.println("Removing " + PrettyPrinter.typeString(klass.getReference()) + " for "
                                                    + PrettyPrinter.typeString(type));
                    }
                    toRemove.add(k);
                }
            }
        }

        if (DEBUG && s.isEmpty() && outputLevel >= 6) {
            System.err.println("\tEMPTY FILTERED POINTS-TO SET for " + node + " filtered on " + type);
        }

        if (toRemove.isEmpty()) {
            return s;
        }
        Set<InstanceKey> toRetain = new HashSet<>(s);
        toRetain.removeAll(toRemove);
        return Collections.unmodifiableSet(toRetain);
    }

    /**
     * Get the points-to set for the given points-to graph node, filtering out
     * results which do not have a particular type, or which have one of a set
     * of types
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

        boolean areNotTypes = notTypes != null && !notTypes.isEmpty();

        IClass isClass = cha.lookupClass(isType);
        Iterator<InstanceKey> iter = s.iterator();
        Set<InstanceKey> toRemove = new HashSet<>();
        while (iter.hasNext()) {
            InstanceKey k = iter.next();
            IClass klass = k.getConcreteType();
            // TODO assuming we have a precise type could be dangerous
            if (cha.isAssignableFrom(isClass, klass)) {
                if (areNotTypes) {
                    assert notTypes != null;
                    for (IClass notClass : notTypes) {
                        if (cha.isAssignableFrom(notClass, klass)) {
                            // klass is a subclass of one of the classes we do
                            // not want
                            toRemove.add(k);
                            break;
                        }
                    }
                }
            } else {
                // XXX Arrays can sometimes be imprecisely labeled as Object
                // types. Imprecisely, but soundly don't remove any arrays from
                // the points-to set if the type we are filtering on is
                // java.lang.Object
                if (!(klass.isArrayClass() && isType.equals(TypeReference.JavaLangObject))) {
                    toRemove.add(k);
                }
            }

        }
        if (DEBUG && s.isEmpty() && outputLevel >= 6) {
            System.err.println("\tEMPTY FILTERED/NOTTED POINTS-TO SET for " + node + " filtered on " + isType
                                            + " not type " + notTypes);
        }

        if (toRemove.isEmpty()) {
            // Nothing to remove
            return s;
        }

        Set<InstanceKey> toRetain = new HashSet<>(s);
        toRetain.removeAll(toRemove);
        return Collections.unmodifiableSet(toRetain);
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
                nStr += " (" + count + ")";
                dotToCount.put(nStr, count + 1);
            }
            n2s.put(n, nStr);
        }
        for (InstanceKey k : allHContexts) {
            String kStr = escape(k.toString());
            Integer count = dotToCount.get(kStr);
            if (count == null) {
                dotToCount.put(kStr, 1);
            } else {
                kStr += " (" + count + ")";
                dotToCount.put(kStr, count + 1);
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
     * Get the class hierarchy defining the type system
     * 
     * @return class hierarchy
     */
    public IClassHierarchy getClassHierarchy() {
        return cha;
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
     * Get new contexts created since this was last called and clear the new
     * context map
     * 
     * @return new context map
     */
    public Map<IMethod, Set<Context>> getAndClearNewContexts() {
        Map<IMethod, Set<Context>> newC = newContexts;
        newContexts = new LinkedHashMap<>();
        return newC;
    }

    /**
     * Get the points-to graph nodes that have caused a change since this was
     * last called and clear the set.
     * 
     * @return set of changed nodes
     */
    public Set<PointsToGraphNode> getAndClearChangedNodes() {
        Set<PointsToGraphNode> c = changedNodes;
        changedNodes = new LinkedHashSet<>();
        return c;
    }

    /**
     * Get the set of nodes that have been read since this was last called and
     * clear the set.
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
}
