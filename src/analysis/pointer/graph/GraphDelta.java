package analysis.pointer.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import util.OrderedPair;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.collections.EmptyIntIterator;
import com.ibm.wala.util.intset.EmptyIntSet;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.SparseIntSet;
import com.ibm.wala.util.intset.TunedMutableSparseIntSet;

/**
 * Represents a delta (i.e., a change set) for a PointsToGraph. This is used to both represent the changes that an
 * operation made to a PointsToGraph, and also to allow more efficient processing of statements, which can focus just on
 * the changes to the graph since last time they were processed.
 */
public class GraphDelta {
    private final PointsToGraph g;
    private final Map<PointsToGraphNode, MutableIntSet> delta;

    public GraphDelta(PointsToGraph g) {
        this.g = g;
        delta = new HashMap<>();
    }

    private MutableIntSet getOrCreateSet(PointsToGraphNode src, int initialSize) {
        MutableIntSet s = delta.get(src);
        if (s == null) {
            s = new TunedMutableSparseIntSet(initialSize, 1.5f);
            delta.put(src, s);
        }
        return s;
    }

    public void add(PointsToGraphNode n, int ik) {
        Map<PointsToGraphNode, Set<PointsToGraphNode>> toCollapse =
                new HashMap<>();
        addToSupersetsOf(n,
                         SparseIntSet.singleton(ik),
                         new HashSet<PointsToGraphNode>(),
                         new Stack<PointsToGraphNode>(),
                         new Stack<TypeFilter>(),
                         toCollapse);
        collapseCycles(toCollapse);
    }

    public void addCopyEdges(PointsToGraphNode source, TypeFilter filter,
            PointsToGraphNode target) {
        // go through the points to set of source, and add anything that target doesn't already point to.
        IntSet diff = g.getDifference(source, filter, target);

        // Now take care of all the supersets of target...
        Map<PointsToGraphNode, Set<PointsToGraphNode>> toCollapse =
                new HashMap<>();
        addToSupersetsOf(target,
                         diff,
                         new HashSet<PointsToGraphNode>(),
                         new Stack<PointsToGraphNode>(),
                         new Stack<TypeFilter>(),
                         toCollapse);
        collapseCycles(toCollapse);
    }

    private void collapseCycles(
            Map<PointsToGraphNode, Set<PointsToGraphNode>> toCollapse) {
        Set<PointsToGraphNode> collapsed = new HashSet<>();
        for (PointsToGraphNode rep : toCollapse.keySet()) {
            rep = g.getRepresentative(rep); // it is possible that rep was already collapsed to something else. So we get the representative of it to shortcut things.
            for (PointsToGraphNode n : toCollapse.get(rep)) {
                if (collapsed.contains(n)) {
                    // we have already collapsed n with something. let's skip it.
                    continue;
                }
                collapsed.add(n);
                g.collapseNodes(n, rep);
                MutableIntSet old = delta.remove(n);
                assert old == null || old.isSubset(delta.get(rep));
            }
        }
    }

    private void addToSupersetsOf(PointsToGraphNode target, IntSet set,
            Set<PointsToGraphNode> currentlyAdding,
            Stack<PointsToGraphNode> currentlyAddingStack,
            Stack<TypeFilter> filters,
            Map<PointsToGraphNode, Set<PointsToGraphNode>> toCollapse) {

        // Handle detection of cycles.
        if (currentlyAdding.contains(target)) {
            // we detected a cycle!
            int foundAt = -1;
            TypeFilter filter = null;
            for (int i = 0; filter == null && i < currentlyAdding.size(); i++) {
                if (foundAt < 0 && currentlyAddingStack.get(i).equals(target)) {
                    foundAt = i;
                    filter = filters.get(i);
                    // Mark the node as being in a cycle, so that it will stay in the cache.
                    g.inCycle(currentlyAddingStack.get(i));
                }
                else if (foundAt >= 0) {
                    // Mark the node as being in a cycle, so that it will stay in the cache.
                    g.inCycle(currentlyAddingStack.get(i));
                    filter = TypeFilter.compose(filter, filters.get(i));
                }
            }
            if (filter == null) {
                // we can collapse some nodes together!
                Set<PointsToGraphNode> toCollapseSet = toCollapse.get(target);
                if (toCollapseSet == null) {
                    toCollapseSet = new HashSet<>();
                    toCollapse.put(target, toCollapseSet);
                }
                for (int i = foundAt + 1; i < currentlyAddingStack.size(); i++) {
                    toCollapseSet.add(currentlyAddingStack.get(i));
                }
            }
            assert !getOrCreateSet(target, 1).addAll(set) : "Shouldn't be anything left to add by this point";
        }

        // Now we actually add the set to the target.
        if (set.isEmpty() || !getOrCreateSet(target, set.size()).addAll(set)) {
            // we didn't add anything, so don't bother recursing...
            if (getOrCreateSet(target, 1).isEmpty()) {
                // let's clean up our mess...
                delta.remove(target);
            }
            return;
        }

        // We added at least one element to target, so let's recurse on the immediate supersets of target.
        currentlyAdding.add(target);
        currentlyAddingStack.push(target);
        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> iter =
                g.immediateSuperSetsOf(target);
        while (iter.hasNext()) {
            OrderedPair<PointsToGraphNode, TypeFilter> superSet = iter.next();

            TypeFilter filter = superSet.snd();
            IntSet filteredSet =
                    filter == null ? set : g.new FilteredIntSet(set,
                                                                superSet.snd());

            // Figure out which elements of filteredSet are actually added to the superset...
            IntSet diff = g.getDifference(filteredSet, superSet.fst());

            filters.push(filter);
            addToSupersetsOf(superSet.fst(),
                             diff,
                             currentlyAdding,
                             currentlyAddingStack,
                             filters,
                             toCollapse);
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
                IntSet srcSet = d.delta.get(src);
                getOrCreateSet(src, srcSet.size()).addAll(srcSet);
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

    public IntSet pointsToSet(PointsToGraphNode n) {
        n = g.getRepresentative(n);
        MutableIntSet s = delta.get(n);
        if (s == null) {
            return EmptyIntSet.instance;
        }
        return s;
    }

    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode n) {
        return g.new IntToInstanceKeyIterator(pointsToIntIterator(n));
    }

    public IntIterator pointsToIntIterator(PointsToGraphNode n) {
        MutableIntSet s;
        // we need to look in delta for all the possible representatives that n has been known by.
        // This is because this GraphDelta may have been created sometime
        // before n got collapsed.
        do {
            s = delta.get(n);
            n = g.getImmediateRepresentative(n);
        } while (s == null && n != null);

        if (s == null) {
            return EmptyIntIterator.instance();
        }
        return s.intIterator();
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
