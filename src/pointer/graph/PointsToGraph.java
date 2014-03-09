package pointer.graph;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import pointer.analyses.HeapAbstractionFactory;
import pointer.statements.StatementRegistrar;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

public class PointsToGraph {

    public static final String ARRAY_CONTENTS = "contents";
    private final Map<PointsToGraphNode, Set<InstanceKey>> graph = new LinkedHashMap<>();
    private final IClassHierarchy cha;

    private Set<PointsToGraphNode> changedNodes;
    private Set<PointsToGraphNode> readNodes;
    private Map<MethodReference, Set<Context>> newContexts;
    private final Map<MethodReference, Set<Context>> contexts;

    public PointsToGraph(IClassHierarchy cha, StatementRegistrar registrar, HeapAbstractionFactory haf) {
        this.cha = cha;
        changedNodes = new LinkedHashSet<>();
        readNodes = new LinkedHashSet<>();
        newContexts = new LinkedHashMap<>();
        contexts = new LinkedHashMap<>();
        MethodReference entry = registrar.getEntryPoint();
        Set<Context> initial = Collections.singleton(haf.initialContext());
        contexts.put(entry, initial);
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

    public boolean addCall(CallSiteReference caller, Context callerContext, MethodReference callee,
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
    public Set<Context> getContexts(MethodReference reference) {
        return Collections.unmodifiableSet(contexts.get(reference));
    }
}
