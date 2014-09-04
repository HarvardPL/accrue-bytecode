package analysis.pointer.graph;

import java.util.ArrayList;
import java.util.Iterator;

import util.intmap.IntMap;
import util.intmap.SparseIntMap;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.graph.PointsToGraph.FilteredIntSet;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

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
public class GraphDelta {
    private final PointsToGraph g;

    /**
     * Map from PointsToGraphNode to sets of InstanceKeys (where PointsToGraphNodes and InstanceKeys are represented by
     * ints). These are the flow-insensitive facts.
     */
    private final IntMap<MutableIntSet> deltaFI;

    /**
     * Map from PointsToGraphNode to InstanceKeys, including the program points (actually, the interprogrampoint
     * replicas) at which they are valid. These are the flow sensitive points to information.
     */
    private final IntMap<IntMap<ProgramPointSet>> deltaFS;

    public GraphDelta(PointsToGraph g) {
        this.g = g;
        // Map doesn't need to be thread safe, since when it is being modified it is thread local
        // and when it is shared, it is read only.
        this.deltaFI = new SparseIntMap<>();
        this.deltaFS = new SparseIntMap<>();
    }


    /**
     * Add some points to info to a flow-insensitive pointstographnode.
     *
     * @param n
     * @param set
     * @return
     */
    protected boolean addAllToSet(/*PointsToGraphNode*/int n, IntSet set) {
        assert !g.isFlowSensitivePointsToGraphNode(n);
        if (set.isEmpty()) {
            return false;
        }
        return getOrCreateFISet(n, setSizeBestGuess(set)).addAll(set);
    }
    protected boolean addAllToSet(/*PointsToGraphNode*/int n, IntSet set, ProgramPointSet pps) {
        assert g.isFlowSensitivePointsToGraphNode(n);
        if (set.isEmpty()) {
            return false;
        }
        boolean changed = false;
        IntMap<ProgramPointSet> m = getOrCreateFSMap(n);
        IntIterator iter = set.intIterator();
        while (iter.hasNext()) {
            int to = iter.next();
            changed |= addProgramPoints(m, to, pps);
        }
        return changed;
    }

    private IntMap<ProgramPointSet> getOrCreateFSMap(/*PointsToGraphNode*/int src) {
        IntMap<ProgramPointSet> s = deltaFS.get(src);
        if (s == null) {
            s = new SparseIntMap();
            deltaFS.put(src, s);
        }
        return s;
    }

    private boolean addProgramPoints(IntMap<ProgramPointSet> m, /*PointsToGraphNode*/int to, ProgramPointSet pps) {
        ProgramPointSet p = m.get(to);
        if (p == null) {
            p = new ProgramPointSet();
            m.put(to, p);
        }
        return p.addAll(pps);
    }

    private MutableIntSet getOrCreateFISet(/*PointsToGraphNode*/int src, Integer initialSize) {
        MutableIntSet s = deltaFI.get(src);
        if (s == null) {
            if (initialSize == null || initialSize == 0) {
                s = MutableSparseIntSet.makeEmpty();
            }
            else {
                s = new TunedMutableSparseIntSet(initialSize, 1.5f);
            }
            deltaFI.put(src, s);
        }
        return s;
    }

    private static int setSizeBestGuess(IntSet set) {
        return set instanceof FilteredIntSet
                ? ((FilteredIntSet) set).underlyingSetSize() : set.size();
    }

    protected void collapseNodes(/*PointsToGraphNode*/int n, /*PointsToGraphNode*/int rep) {
        MutableIntSet oldFI = deltaFI.remove(n);
        assert oldFI == null || oldFI.isSubset(deltaFI.get(rep));
        IntMap<ProgramPointSet> oldFS = deltaFS.remove(n);
        assert (oldFS == null || containsAll(deltaFS.get(rep), oldFS));
    }

    private static boolean containsAll(IntMap<ProgramPointSet> superset, IntMap<ProgramPointSet> subset) {
        IntIterator iter = subset.keyIterator();
        while (iter.hasNext()) {
            int key = iter.next();
            if (superset.containsKey(key)) {
                if (!superset.get(key).containsAll(subset.get(key))) {
                    return false;
                }
            }
            else {
                return false;
            }
        }
        return true;
    }


    /**
     * Combine this GraphDelta with another graph delta. For efficiency, this method may be implemented imperatively.
     *
     * @param d
     * @return
     */
    public GraphDelta combine(GraphDelta d) {
        if (d != null) {
            IntIterator keysFI = d.deltaFI.keyIterator();
            while (keysFI.hasNext()) {
                int src = keysFI.next();
                IntSet srcSet = d.deltaFI.get(src);
                int estimatedSize = setSizeBestGuess(srcSet);
                getOrCreateFISet(src, estimatedSize).addAll(srcSet);
            }

            IntIterator keysFS = d.deltaFI.keyIterator();
            while (keysFS.hasNext()) {
                int src = keysFS.next();
                IntMap<ProgramPointSet> srcSet = d.deltaFS.get(src);
                IntMap<ProgramPointSet> m = this.getOrCreateFSMap(src);
                IntIterator srcKeys = srcSet.keyIterator();
                while (srcKeys.hasNext()) {
                    int k = srcKeys.next();
                    addProgramPoints(m, k, srcSet.get(k));
                }
            }

        }
        return this;
    }

    public boolean isEmpty() {
        return deltaFI.isEmpty() && deltaFS.isEmpty();
    }

    @Override
    public String toString() {
        int size = 0;
        StringBuffer sb = new StringBuffer();
        sb.append("GraphDelta [");

        IntIterator iter = deltaFI.keyIterator();
        while (iter.hasNext()) {
            int i = iter.next();
            MutableIntSet s = deltaFI.get(i);
            sb.append(i);
            sb.append(":");
            sb.append(s);
            if (iter.hasNext()) {
                sb.append(" ");
            }
            size += s.size();
        }
        iter = deltaFS.keyIterator();
        if (iter.hasNext() && size > 0) {
            sb.append(" ");
        }
        while (iter.hasNext()) {
            int i = iter.next();
            IntMap<ProgramPointSet> s = deltaFS.get(i);
            sb.append(i);
            sb.append(":");
            sb.append(s);
            if (iter.hasNext()) {
                sb.append(" ");
            }
            size += s.size();
        }

        sb.append(" (size" + size + ")]");
        return sb.toString();
    }

    public Iterator<InstanceKeyRecency> pointsToIterator(PointsToGraphNode n) {
        return g.new IntToInstanceKeyIterator(pointsToIntIterator(g.lookupDictionary(n)));
    }

    public IntIterator pointsToIntIterator(/*PointsToGraphNode*/int n, InterProgramPointReplica ippr) {
        ArrayList<IntIterator> iterators = new ArrayList<>(10);
        // we need to look in delta for all the possible representatives that n has been known by.
        // This is because this GraphDelta may have been created sometime
        // before n got collapsed.
        Integer node = n;
        do {
            MutableIntSet s = deltaFI.get(node);
            if (s != null) {
                iterators.add(s.intIterator());
            }
            node = g.getImmediateRepresentative(node);
        } while (node != null);

        node = n;
        do {
            IntMap<ProgramPointSet> s = deltaFS.get(node);
            if (s != null) {
                iterators.add(XXX);
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
        return g.new SortedIntSetUnionIterator(deltaFI.keyIterator(), deltaFS.keyIterator());
    }

}
