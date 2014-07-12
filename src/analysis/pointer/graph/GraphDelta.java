package analysis.pointer.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import util.OrderedPair;
import analysis.pointer.graph.PointsToGraph.FilteredIntSet;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.collections.EmptyIntIterator;
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
    private final Map<PointsToGraphNode, MutableIntSet> delta;

    private GraphDelta representative;

    // An estimate of the size of this delta.
    private int size;

    private GraphDelta representative() {
        GraphDelta last = this;
        GraphDelta rep = last.representative;
        while (rep != null) {
            last = rep;
            rep = last.representative;
        }
        return last;
    }

    public GraphDelta(PointsToGraph g) {
        this.g = g;
        this.delta = new HashMap<>();
        this.size = 0;
    }

    private MutableIntSet getOrCreateSet(PointsToGraphNode src,
            Integer initialSize) {
        assert representative == null;

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

    public void add(PointsToGraphNode n, int ik) {
        assert representative == null;

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

        assert representative == null;

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
        assert representative == null;
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
            assert !getOrCreateSet(target, setSizeBestGuess(set)).addAll(set) : "Shouldn't be anything left to add by this point";
        }

        // Now we actually add the set to the target.
        int estimatedSize = setSizeBestGuess(set);
        if (!getOrCreateSet(target, estimatedSize).addAll(set)) {
            // we didn't add anything, so don't bother recursing...
            if (getOrCreateSet(target, 2).isEmpty()) {
                // let's clean up our mess...
                delta.remove(target);
            }
            return;
        }
        // increase the estimated size.
        size += estimatedSize;

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

            // The set of things that
            IntSet diff;

            if (g.numIsSupersetOf(superSet.fst()) == 1) {
                // there is only one subset!
                // This means that anything that was added to target
                // will definitely be added to superSet.fst(), and
                // so we don't need to explicitly compute the difference set
                diff = filteredSet;
            }
            else {
                // Figure out which elements of filteredSet are actually added to the superset...
                diff = g.getDifference(filteredSet, superSet.fst());
            }

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
        assert representative == null;
        if (d != null) {
            GraphDelta dr = d.representative();
            for (PointsToGraphNode src : dr.delta.keySet()) {
                IntSet srcSet = dr.delta.get(src);
                int estimatedSize = setSizeBestGuess(srcSet);
                getOrCreateSet(src, estimatedSize).addAll(srcSet);
                size += estimatedSize;
            }
        }
        return this;
    }

    public boolean isEmpty() {
        GraphDelta rep = this.representative();
        if (rep == this) {
            return delta.isEmpty();
        }
        return rep.isEmpty();
    }

    @Override
    public String toString() {
        return "GraphDelta [" + representative().delta + "]";
    }

    public IntSet pointsToSet(PointsToGraphNode n) {
        n = g.getRepresentative(n);
        MutableIntSet s = representative().delta.get(n);
        if (s == null) {
            return EmptyIntSet.instance;
        }
        return s;
    }

    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode n) {
        return g.new IntToInstanceKeyIterator(pointsToIntIterator(n));
    }

    public IntIterator pointsToIntIterator(PointsToGraphNode n) {
        ArrayList<IntIterator> iterators = new ArrayList<>(10);
        // we need to look in delta for all the possible representatives that n has been known by.
        // This is because this GraphDelta may have been created sometime
        // before n got collapsed.
        GraphDelta rep = representative();
        do {
            MutableIntSet s = rep.delta.get(n);
            if (s != null) {
                iterators.add(s.intIterator());
            }
            n = g.getImmediateRepresentative(n);
        } while (n != null);

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

    public Iterator<PointsToGraphNode> domainIterator() {
        return representative().delta.keySet().iterator();
    }

    public static void merge(GraphDelta d, GraphDelta e) {
        GraphDelta dr = d.representative();
        GraphDelta er = e.representative();
        if (dr == er) {
            // They are already merged
            return;
        }

        if (dr.size < er.size || d.representative == null) {
            er.combine(dr);
            dr.representative = er;
        }
        else {
            dr.combine(er);
            er.representative = dr;
        }
    }
}
