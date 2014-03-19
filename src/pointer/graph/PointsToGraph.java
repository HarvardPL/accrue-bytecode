package pointer.graph;

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
import java.util.Map;
import java.util.Set;

import pointer.analyses.HeapAbstractionFactory;
import pointer.statements.StatementRegistrar;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.TypeReference;

public class PointsToGraph {

    public static final String ARRAY_CONTENTS = "[contents]";
    private final Map<PointsToGraphNode, Set<InstanceKey>> graph = new LinkedHashMap<>();
    private final Set<InstanceKey> allHContexts = new HashSet<>();
    private final IClassHierarchy cha;

    private Set<PointsToGraphNode> changedNodes;
    private Set<PointsToGraphNode> readNodes;
    private Map<IMethod, Set<Context>> newContexts;
    private final Map<IMethod, Set<Context>> contexts;
    /**
     * If true then every statement is assumed to throw an error
     * TODO this doesn't do much right now 
     */
    private boolean trackImplicitErrors = false;

    public PointsToGraph(IClassHierarchy cha, StatementRegistrar registrar, HeapAbstractionFactory haf, boolean trackImplicitErrors) {
        this(cha,registrar, haf);
        this.trackImplicitErrors  = trackImplicitErrors;
    }
    
    public PointsToGraph(IClassHierarchy cha, StatementRegistrar registrar, HeapAbstractionFactory haf) {
        this.cha = cha;
        changedNodes = new LinkedHashSet<>();
        readNodes = new LinkedHashSet<>();
        newContexts = new LinkedHashMap<>();
        contexts = getInitialContexts(haf, registrar.getInitialContextMethods());
    }

    private Map<IMethod, Set<Context>> getInitialContexts(HeapAbstractionFactory haf, Set<IMethod> initialMethods){
        Map<IMethod, Set<Context>> init = new LinkedHashMap<>();
        for (IMethod m : initialMethods) {
            Set<Context> cs = new LinkedHashSet<>();
            cs.add(haf.initialContext());
            init.put(m, cs);
        }
        return init;
    }
    
    public boolean addEdge(PointsToGraphNode node, InstanceKey heapContext) {
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

        if (graph.containsKey(node)) {
            return new LinkedHashSet<>(graph.get(node));
        }
        return Collections.emptySet();
    }

    public Set<InstanceKey> getPointsToSetFiltered(PointsToGraphNode node, TypeReference type) {
        Set<InstanceKey> s = getPointsToSet(node);
        Iterator<InstanceKey> i = s.iterator();
        while (i.hasNext()) {
            InstanceKey k = i.next();
            IClass klass = k.getConcreteType();
            // TODO isAssignableFrom instead of isSubclassOf ?
            if (!cha.isSubclassOf(klass, cha.lookupClass(type))) {
                i.remove();
            }
        }
        return s;
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
    public Set<InstanceKey> getPointsToSetFiltered(PointsToGraphNode node, TypeReference isType,
            Set<IClass> notTypes) {
        Set<InstanceKey> s = getPointsToSet(node);
        if (s.isEmpty()) {
            return Collections.emptySet();
        }
        
        boolean areNotTypes = notTypes != null && !notTypes.isEmpty();
        
        IClass isClass = cha.lookupClass(isType);
        Iterator<InstanceKey> iter = s.iterator();
        while (iter.hasNext()) {
            InstanceKey k = iter.next();
            IClass klass = k.getConcreteType();
            // TODO assuming we have a precise type could be dangerous
            if (cha.isSubclassOf(klass, isClass)) {
                if (areNotTypes) {
                    for (IClass notClass : notTypes){
                        if (cha.isSubclassOf(klass, notClass)) {
                            // klass is a subclass of one of the classes we do not want
                            iter.remove();
                            break;
                        }
                    }
                }
            } else {
                iter.remove();
            }
            
            
        }
        
        return s;
    }

    public boolean addCall(CallSiteReference caller, Context callerContext, IMethod callee,
            Context calleeContext) {
        // TODO Add edge to call graph

        Set<Context> s = contexts.get(callee);
        if (s == null) {
            s = new LinkedHashSet<>();
            contexts.put(callee, s);
        }
        boolean changed = s.add(calleeContext);
        if (changed) {
            Set<Context> n = newContexts.get(callee);
            if (n == null) {
                n = new LinkedHashSet<>();
                newContexts.put(callee, n);
            }
            n.add(calleeContext);
        }

        return changed;
    }

    /**
     * Set of contexts for the given method
     * 
     * @param reference
     *            method reference to get contexts for
     * @return set of contexts for the given method
     */
    public Set<Context> getContexts(IMethod reference) {
        Set<Context> s = contexts.get(reference);
        if (s == null) {
            return Collections.<Context> emptySet();
        }
        return Collections.unmodifiableSet(contexts.get(reference));
    }
    
    /**
     * Get a set containing all the points-to graph nodes
     * 
     * @return points-to graph nodes 
     */
    public Set<PointsToGraphNode> getNodes() {
        return graph.keySet();
    }
    

    public void dumpPointsToGraphToFile(boolean addDate) {
        String dir = "tests";
        String file = "pointsTo";
        if (addDate) {
            SimpleDateFormat dateFormat =
                    new SimpleDateFormat("-yyyy-MM-dd-HH_mm_ss");
            Date dateNow = new Date();
            String now = dateFormat.format(dateNow);
            file += now;
        }
        String fullFilename = dir + "/" + file + ".dot";
        try {
            Writer out = new BufferedWriter(new FileWriter(fullFilename));
            out = dumpPointsToGraph(out);
            out.close();
            System.err.println("\nDOT written to: " + fullFilename);
        }
        catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename
                    + ", " + e.getMessage());
        }
    }

    private String escape(String s) {
        return s.replace("\"", "").replace("\\", "\\\\");
    }

    private Writer dumpPointsToGraph(Writer writer) throws IOException {
        double spread = 1.0;
        writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep="
                + spread + ";\n" + "graph [fontsize=10]" + ";\n"
                + "node [fontsize=10]" + ";\n" + "edge [fontsize=10]" + ";\n");  
        
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
            for (InstanceKey ik : graph.get(n)){
                writer.write("\t\"" + n2s.get(n) + "\" -> "
                        + "\"" + k2s.get(ik) + "\";\n");
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
     * Track implicit {@link Error}s 
     * 
     * @return if true then we track implicitly thrown errors
     */
    public boolean trackImplicitErrors() {
        return trackImplicitErrors;
    }
}
