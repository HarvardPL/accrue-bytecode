package analysis.pointer.graph;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import util.OrderedPair;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * Represents a delta (i.e., a change set) for a PointsToGraph. This is used to both represent the changes that an
 * operation made to a PointsToGraph, and also to allow more efficient processing of statements, which can focus just on
 * the changes to the graph since last time they were processed.
 */
public class GraphDelta {
    private final PointsToGraph g;
    private final Map<PointsToGraphNode, Set<InstanceKey>> delta;

    public GraphDelta(PointsToGraph g) {
        this.g = g;
        delta = new LinkedHashMap<>();
    }

    private Set<InstanceKey> getOrCreateSet(PointsToGraphNode src) {
        Set<InstanceKey> s = delta.get(src);
        if (s == null) {
            s = new LinkedHashSet<>();
            delta.put(src, s);
        }
        return s;
    }

    public void add(PointsToGraphNode n, InstanceKey ik) {
        Map<PointsToGraphNode, Set<PointsToGraphNode>> toCombine =
                new LinkedHashMap<>();
        addToSupersetsOf(n,
                         Collections.singleton(ik),
                         new HashSet<PointsToGraphNode>(),
                         new Stack<PointsToGraphNode>(),
                         new Stack<TypeFilter>(),
                         toCombine);
        combineNodes(toCombine);
    }

    public void addCopyEdges(PointsToGraphNode source, TypeFilter filter,
            PointsToGraphNode target) {
        // go through the points to set of source, and add anything that target doesn't already point to.
        Set<InstanceKey> diff = g.getDifference(source, filter, target);

        // Now take care of all the supersets of target...
        Map<PointsToGraphNode, Set<PointsToGraphNode>> toCombine =
                new LinkedHashMap<>();
        addToSupersetsOf(target,
                         diff,
                         new HashSet<PointsToGraphNode>(),
                         new Stack<PointsToGraphNode>(),
                         new Stack<TypeFilter>(),
                         toCombine);
        combineNodes(toCombine);
    }

    private void combineNodes(
            Map<PointsToGraphNode, Set<PointsToGraphNode>> toCombine) {
        for (PointsToGraphNode rep : toCombine.keySet()) {
            for (PointsToGraphNode n : toCombine.get(rep)) {
                g.combineNodes(n, rep);
                delta.remove(n);
            }
        }
    }

    private void addToSupersetsOf(PointsToGraphNode target,
            Set<InstanceKey> set, Set<PointsToGraphNode> currentlyAdding,
            Stack<PointsToGraphNode> currentlyAddingStack,
            Stack<TypeFilter> filters,
            Map<PointsToGraphNode, Set<PointsToGraphNode>> toCombine) {

        if (currentlyAdding.contains(target)) {
            // we detected a cycle!
            int foundAt = -1;
            TypeFilter filter = null;
            for (int i = 0; filter == null && i < currentlyAdding.size(); i++) {
                if (foundAt < 0 && currentlyAddingStack.get(i).equals(target)) {
                    foundAt = i;
                    filter = filters.get(i);
                }
                else if (foundAt >= 0) {
                    filter = TypeFilter.compose(filter, filters.get(i));
                }
            }
            if (filter == null) {
                // we can collapse some nodes together!
                Set<PointsToGraphNode> toCombineSet = toCombine.get(target);
                if (toCombineSet == null) {
                    toCombineSet = new LinkedHashSet<>();
                    toCombine.put(target, toCombineSet);
                }
                for (int i = foundAt + 1; i < currentlyAddingStack.size(); i++) {
                    toCombineSet.add(currentlyAddingStack.get(i));
                }
            }
            if (getOrCreateSet(target).addAll(set)) {
                throw new RuntimeException("Something strange happened: this should be empty by now...");
            }
        }

        if (!getOrCreateSet(target).addAll(set)) {
            // we didn't add anything, so don't bother recursing...
            if (getOrCreateSet(target).isEmpty()) {
                // let's clean up our mess...
                delta.remove(target);
            }
            return;
        }

        currentlyAdding.add(target);
        currentlyAddingStack.push(target);
        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> iter =
                g.immediateSuperSetsOf(target);
        while (iter.hasNext()) {
            OrderedPair<PointsToGraphNode, TypeFilter> superSet = iter.next();

            TypeFilter filter = superSet.snd();
            Set<InstanceKey> filteredSet =
                    filter == null
                            ? set
                            : new PointsToGraph.FilteredSet(set, superSet.snd());

            // Figure out which elements of filteredSet are actually added to the superset...
            Set<InstanceKey> diff =
                    g.getDifference(filteredSet, superSet.fst());

            filters.push(filter);
            addToSupersetsOf(superSet.fst(),
                             diff,
                             currentlyAdding,
                             currentlyAddingStack,
                             filters,
                             toCombine);
            filters.pop();
        }

        currentlyAdding.remove(target);
        currentlyAddingStack.pop();

    }

    /**
     * Combine this GraphDelta with another graph delta. For efficiency, this method may be implemented imperatively.
     * 
     * @param d
     * @return
     */
    public GraphDelta combine(GraphDelta d) {
        if (d != null) {
            for (PointsToGraphNode src : d.delta.keySet()) {
                getOrCreateSet(src).addAll(d.delta.get(src));
            }
        }
        return this;
    }

    public boolean isEmpty() {
        return delta.isEmpty();
    }

    @Override
    public String toString() {
        return "GraphDelta [" + delta + "]";
    }

    public Set<InstanceKey> pointsToSet(PointsToGraphNode n) {
        n = g.getRepresentative(n);
        Set<InstanceKey> s = delta.get(n);
        if (s == null) {
            return Collections.emptySet();
        }
        return s;
    }

    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode n) {
        n = g.getRepresentative(n);
        Set<InstanceKey> s = delta.get(n);
        if (s == null) {
            return Collections.emptyIterator();
        }
        return s.iterator();
    }

    public Iterator<PointsToGraphNode> domainIterator() {
        return delta.keySet().iterator();
    }

    public int size() {
        return delta.size();
    }

    public int extendedSize() {
        int size = 0;
        for (PointsToGraphNode n : delta.keySet()) {
            size += delta.get(n).size();
        }
        return size;
    }
}
