package analysis.pointer.graph;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.Stack;

import util.OrderedPair;
import util.intmap.IntMap;
import util.intmap.SparseIntMap;
import analysis.pointer.graph.PointsToGraph.FilteredIntSet;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.collections.EmptyIntIterator;
import com.ibm.wala.util.collections.IntStack;
import com.ibm.wala.util.intset.EmptyIntSet;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;
import com.ibm.wala.util.intset.SparseIntSet;
import com.ibm.wala.util.intset.TunedMutableSparseIntSet;

/**
 * Represents a delta (i.e., a change set) for a PointsToGraph. This is used to both represent the changes that an
 * operation made to a PointsToGraph, and also to allow more efficient processing of statements, which can focus just on
 * the changes to the graph since last time they were processed.
 */
public class GraphDelta {
    private final PointsToGraph g;
    /**
     * Map from PointsToGraphNode to sets of InstanceKeys (where PointsToGraphNodes and InstanceKeys are represented by
     * ints)
     */
    private final IntMap<MutableIntSet> delta;

    // An estimate of the size of this delta.
    private int size;

    public GraphDelta(PointsToGraph g) {
        this.g = g;
        this.delta = new SparseIntMap<MutableIntSet>();
        this.size = 0;
    }

    private MutableIntSet getOrCreateSet(/*PointsToGraphNode*/int src,
            Integer initialSize) {
        MutableIntSet s = delta.get(src);
        if (s == null) {
            if (initialSize == null || initialSize == 0) {
                s = MutableSparseIntSet.makeEmpty();
            }
            else {
                s = new TunedMutableSparseIntSet(initialSize, 1.5f);
            }
            delta.put(src, s);
        }
        return s;
    }

    public void add(/*PointsToGraphNode*/int n, int ik) {
        // Map from PointsToGraphNode to sets of pointsToGraphNodes
        IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();
        addToSupersetsOf(n,
                         SparseIntSet.singleton(ik),
                         MutableSparseIntSet.makeEmpty(),
                         new IntStack(),
                         new Stack<Set<TypeFilter>>(),
                         toCollapse);
        // XXX TO ADD LATER?
        // collapseCycles(toCollapse);
    }

    public void addCopyEdges(/*PointsToGraphNode*/int source, TypeFilter filter, /*PointsToGraphNode*/int target) {

        // go through the points to set of source, and add anything that target doesn't already point to.
        IntSet diff = g.getDifference(source, filter, target);

        // Now take care of all the supersets of target...
        IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();
        addToSupersetsOf(target,
                         diff,
                         MutableSparseIntSet.makeEmpty(),
                         new IntStack(),
                         new Stack<Set<TypeFilter>>(),
                         toCollapse);
        // XXX maybe add later?
        // collapseCycles(toCollapse);
    }

    private void collapseCycles(IntMap<MutableIntSet> toCollapse) {
        MutableIntSet collapsed = MutableSparseIntSet.makeEmpty();
        IntIterator iter = toCollapse.keyIterator();
        while (iter.hasNext()) {
            int rep = iter.next();
            rep = g.getRepresentative(rep); // it is possible that rep was already collapsed to something else. So we get the representative of it to shortcut things.
            IntIterator collapseIter = toCollapse.get(rep).intIterator();
            while (collapseIter.hasNext()) {
                int n = collapseIter.next();
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

    private void addToSupersetsOf(/*PointsToGraphNode*/int target, IntSet set, MutableIntSet currentlyAdding,
                                  IntStack currentlyAddingStack, Stack<Set<TypeFilter>> filterStack,
                                  IntMap<MutableIntSet> toCollapse) {
        // Handle detection of cycles.
        if (currentlyAdding.contains(target)) {
            // we detected a cycle!
            int foundAt = -1;
            boolean hasMeaningfulFilter = false;
            for (int i = 0; !hasMeaningfulFilter && i < currentlyAdding.size(); i++) {
                if (foundAt < 0 && currentlyAddingStack.get(i) == target) {
                    foundAt = i;
                }
                hasMeaningfulFilter |= filterStack.get(i) != null;
            }
            if (!hasMeaningfulFilter) {
                // we can collapse some nodes together!
                MutableIntSet toCollapseSet = toCollapse.get(target);
                if (toCollapseSet == null) {
                    toCollapseSet = MutableSparseIntSet.makeEmpty();
                    toCollapse.put(target, toCollapseSet);
                }
                for (int i = foundAt + 1; i < filterStack.size(); i++) {
                    toCollapseSet.add(currentlyAddingStack.get(i));
                }
            }
            assert !getOrCreateSet(target, setSizeBestGuess(set)).addAll(set) : "Shouldn't be anything left to add by this point";
        }

        // Now we actually add the set to the target.
        int estimatedSize = setSizeBestGuess(set);
        if (!getOrCreateSet(target, estimatedSize).addAll(set)) {
            return;
        }
        // increase the estimated size.
        size += estimatedSize;

        // We added at least one element to target, so let's recurse on the immediate supersets of target.
        currentlyAdding.add(target);
        currentlyAddingStack.push(target);
        OrderedPair<IntSet, IntMap<Set<TypeFilter>>> supersets = g.immediateSuperSetsOf(target);
        IntSet unfilteredSupersets = supersets.fst();
        IntMap<Set<TypeFilter>> filteredSupersets = supersets.snd();
        IntIterator iter = unfilteredSupersets == null ? EmptyIntIterator.instance()
                : unfilteredSupersets.intIterator();
        while (iter.hasNext()) {
            int m = iter.next();
            propagateDifference(m, null, set, currentlyAdding, currentlyAddingStack, filterStack, toCollapse);
        }
        iter = filteredSupersets == null ? EmptyIntIterator.instance() : filteredSupersets.keyIterator();
        while (iter.hasNext()) {
            int m = iter.next();
            propagateDifference(m,
                                filteredSupersets.get(m),
                                set,
                                currentlyAdding,
                                currentlyAddingStack,
                                filterStack,
                                toCollapse);
        }
        currentlyAdding.remove(target);
        currentlyAddingStack.pop();

    }

    private void propagateDifference(/*PointsToGraphNode*/int target, Set<TypeFilter> filters, IntSet source,
                                     MutableIntSet currentlyAdding, IntStack currentlyAddingStack,
                                     Stack<Set<TypeFilter>> filterStack, IntMap<MutableIntSet> toCollapse) {
        IntSet filteredSet = filters == null ? source : g.new FilteredIntSet(source, filters);

        // The set of elements that will be added to the superset.
        IntSet diff = g.getDifference(filteredSet, target);

        filterStack.push(filters);
        addToSupersetsOf(target, diff, currentlyAdding, currentlyAddingStack, filterStack, toCollapse);
        filterStack.pop();

    }

    private static int setSizeBestGuess(IntSet set) {
        return set instanceof FilteredIntSet
                ? ((FilteredIntSet) set).underlyingSetSize() : set.size();
    }

    /**
     * Combine this GraphDelta with another graph delta. For efficiency, this method may be implemented imperatively.
     *
     * @param d
     * @return
     */
    public GraphDelta combine(GraphDelta d) {
        if (d != null) {
            IntIterator keys = d.delta.keyIterator();
            while (keys.hasNext()) {
                int src = keys.next();
                IntSet srcSet = d.delta.get(src);
                int estimatedSize = setSizeBestGuess(srcSet);
                getOrCreateSet(src, estimatedSize).addAll(srcSet);
                size += estimatedSize;
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

    public IntSet pointsToSet(/*PointsToGraphNode*/int n) {
        n = g.getRepresentative(n);
        MutableIntSet s = this.delta.get(n);
        if (s == null) {
            return EmptyIntSet.instance;
        }
        return s;
    }

    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode n) {
        return g.new IntToInstanceKeyIterator(pointsToIntIterator(g.lookupDictionary(n)));
    }

    public IntIterator pointsToIntIterator(/*PointsToGraphNode*/int n) {
        ArrayList<IntIterator> iterators = new ArrayList<>(10);
        // we need to look in delta for all the possible representatives that n has been known by.
        // This is because this GraphDelta may have been created sometime
        // before n got collapsed.
        Integer node = n;
        do {
            MutableIntSet s = delta.get(node);
            if (s != null) {
                iterators.add(s.intIterator());
            }
            node = g.getImmediateRepresentative(node);
        } while (node != null);

        if (iterators.isEmpty()) {
            return EmptyIntIterator.instance();
        }
        if (iterators.size() == 1) {
            return iterators.get(0);
        }
        // there are multiple iterators.
        // Combine them in a gree.
        do {
            ArrayList<IntIterator> newIterators = new ArrayList<>(iterators.size() / 2 + 1);
            for (int i = 0; i < iterators.size(); i += 2) {
                IntIterator iter;
                if (i + 1 < iterators.size()) {
                    iter = g.new SortedIntSetUnionIterator(iterators.get(i), iterators.get(i + 1));
                }
                else {
                    iter = iterators.get(i);
                }
                newIterators.add(iter);
            }
            iterators = newIterators;

        } while (iterators.size() > 1);
        return iterators.get(0);
    }

    public IntIterator domainIterator() {
        return delta.keyIterator();
    }

}
