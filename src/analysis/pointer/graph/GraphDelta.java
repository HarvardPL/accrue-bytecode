package analysis.pointer.graph;

import java.util.ArrayList;
import java.util.Iterator;

import util.intmap.IntMap;
import util.intmap.SparseIntMap;
import util.intset.IntSetUnion;
import util.optional.Optional;
import analysis.pointer.analyses.StringInstanceKey;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.PointsToGraph.FilteredIntSet;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.collections.EmptyIntIterator;
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
public final class GraphDelta implements PointsToIterable {
    private final PointsToGraph g;
    private final StringConstraintDelta scd;
    /**
     * Map from PointsToGraphNode to sets of InstanceKeys (where PointsToGraphNodes and InstanceKeys are represented by
     * ints)
     */
    private final IntMap<MutableIntSet> delta;

    public GraphDelta(PointsToGraph g) {
        this.g = g;
        this.scd = StringConstraintDelta.makeEmpty(this.g.getStringConstraints());
        // Map doesn't need to be thread safe, since when it is being modified it is thread local
        // and when it is shared, it is read only.
        this.delta = new SparseIntMap<MutableIntSet>();
    }

    public GraphDelta(PointsToGraph g, StringConstraintDelta scd) {
        this.g = g;
        this.scd = scd;
        // Map doesn't need to be thread safe, since when it is being modified it is thread local
        // and when it is shared, it is read only.
        this.delta = new SparseIntMap<MutableIntSet>();
    }

    MutableIntSet getOrCreateSet(/*PointsToGraphNode*/int src,
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
        if (!PointsToGraph.USE_CYCLE_COLLAPSING) {
            throw new UnsupportedOperationException("We do not currently support cycle collapsing");
        }
        MutableIntSet old = delta.remove(n);
        if (old != null) {
            getOrCreateSet(rep, setSizeBestGuess(old)).addAll(old);
        }
        assert old == null || (delta.get(rep) != null && old.isSubset(delta.get(rep)));
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
        int size = 0;
        IntIterator iter = delta.keyIterator();
        StringBuffer sb = new StringBuffer();
        sb.append("GraphDelta [");
        while (iter.hasNext()) {
            int i = iter.next();
            MutableIntSet s = delta.get(i);
            sb.append(i);
            sb.append(":");
            sb.append(s);
            size += s.size();
        }
        sb.append("](size" + size + ")");
        return sb.toString();
    }

    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode node) {
        int n = g.lookupDictionary(node);
        assert n >= 0;
        return g.new IntToInstanceKeyIterator(pointsToIntIterator(n));
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
        // Combine them in a tree.
        do {
            ArrayList<IntIterator> newIterators = new ArrayList<>(iterators.size() / 2 + 1);
            for (int i = 0; i < iterators.size(); i += 2) {
                IntIterator iter;
                if (i + 1 < iterators.size()) {
                    iter = new IntSetUnion.SortedIntSetUnionIterator(iterators.get(i), iterators.get(i + 1));
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


    @Override
    public Iterable<InstanceKey> pointsToIterable(final PointsToGraphNode node, StmtAndContext originator) {
        return new Iterable<InstanceKey>() {
            @Override
            public Iterator<InstanceKey> iterator() {
                return pointsToIterator(node);
            }
        };
    }

    @Override
    public Optional<StringInstanceKey> getAStringFor(StringVariableReplica svr) {
        return this.scd.getAStringFor(svr);
    }

}
