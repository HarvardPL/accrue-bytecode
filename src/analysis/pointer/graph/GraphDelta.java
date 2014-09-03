package analysis.pointer.graph;

import java.util.ArrayList;
import java.util.Iterator;

import util.intmap.IntMap;
import util.intmap.SparseIntMap;
import analysis.pointer.graph.PointsToGraph.FilteredIntSet;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.collections.EmptyIntIterator;
import com.ibm.wala.util.intset.EmptyIntSet;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;
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

    public GraphDelta(PointsToGraph g) {
        this.g = g;
        this.delta = new SparseIntMap<MutableIntSet>();
    }


    protected boolean addAllToSet(/*PointsToGraphNode*/int n, IntSet set) {
        return getOrCreateSet(n, setSizeBestGuess(set)).addAll(set);
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

    private static int setSizeBestGuess(IntSet set) {
        return set instanceof FilteredIntSet
                ? ((FilteredIntSet) set).underlyingSetSize() : set.size();
    }

    protected void collapseNodes(/*PointsToGraphNode*/int n, /*PointsToGraphNode*/int rep) {
        MutableIntSet old = delta.remove(n);
        assert old == null || old.isSubset(delta.get(rep));
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
