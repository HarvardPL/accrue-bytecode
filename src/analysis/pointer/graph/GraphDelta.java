package analysis.pointer.graph;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import util.intmap.IntMap;
import util.intmap.SparseIntMap;
import util.intset.IntSetUnion;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.PointsToGraph.FilteredIntSet;
import analysis.pointer.graph.PointsToGraph.ProgramPointIntIterator;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

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
public final class GraphDelta {
    private final PointsToGraph g;

    /**
     * Map from PointsToGraphNode to sets of InstanceKeys (where PointsToGraphNodes and InstanceKeys are represented by
     * ints). These are the flow-insensitive facts, i.e., they hold true at all program points.
     */
    private final IntMap<MutableIntSet> deltaFI;

    /**
     * Map from PointsToGraphNode to InstanceKeys, including the program points (actually, the interprogrampoint
     * replicas) at which they are valid. These are the flow sensitive points to information. if (s,t,ps) \in deltaFS,
     * and p \in ps, then s points to t at program point p.
     */
    private final IntMap<IntMap<ProgramPointSetClosure>> deltaFS;

    /**
     * New allocation sites.
     */
    private final IntMap<Set<ProgramPointReplica>> deltaAllocationSites;

    public GraphDelta(PointsToGraph g) {
        this.g = g;
        // Map doesn't need to be thread safe, since when it is being modified it is thread local
        // and when it is shared, it is read only.
        this.deltaFI = new SparseIntMap<>();
        this.deltaFS = new SparseIntMap<>();
        this.deltaAllocationSites = new SparseIntMap<>();
    }


    IntMap<ProgramPointSetClosure> getOrCreateFSMap(/*PointsToGraphNode*/int src) {
        IntMap<ProgramPointSetClosure> s = deltaFS.get(src);
        if (s == null) {
            s = new SparseIntMap<>();
            deltaFS.put(src, s);
        }
        return s;
    }

    boolean addProgramPoints(IntMap<ProgramPointSetClosure> m, /*PointsToGraphNode*/int from, /*PointsToGraphNode*/
                                            int to,
                                            ExplicitProgramPointSet toAdd) {
        ProgramPointSetClosure p = m.get(to);
        if (p == null) {
            p = new ProgramPointSetClosure(from, to, this.g);
            m.put(to, p);
        }
        return p.addAll(toAdd);
    }

    private boolean addProgramPoints(IntMap<ProgramPointSetClosure> m, /*PointsToGraphNode*/int from, /*PointsToGraphNode*/
                                     int to, ProgramPointSetClosure toAdd) {
        ProgramPointSetClosure p = m.get(to);
        if (p == null) {
            p = new ProgramPointSetClosure(from, to, this.g);
            m.put(to, p);
        }
        return p.addAll(toAdd);
    }

    public boolean addAllocationSite(InstanceKeyRecency ikr, ProgramPointReplica ppr) {
        int n = g.lookupDictionary(ikr);
        Set<ProgramPointReplica> p = deltaAllocationSites.get(n);
        if (p == null) {
            p = new LinkedHashSet<>();
            deltaAllocationSites.put(n, p);
        }
        return p.add(ppr);
    }

    MutableIntSet getOrCreateFISet(/*PointsToGraphNode*/int src, Integer initialSize) {
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

            IntIterator keysFS = d.deltaFS.keyIterator();
            while (keysFS.hasNext()) {
                int src = keysFS.next();
                IntMap<ProgramPointSetClosure> srcSet = d.deltaFS.get(src);
                IntMap<ProgramPointSetClosure> m = this.getOrCreateFSMap(src);
                IntIterator srcKeys = srcSet.keyIterator();
                while (srcKeys.hasNext()) {
                    int k = srcKeys.next();
                    ProgramPointSetClosure ss = srcSet.get(k);
                    addProgramPoints(m, ss.getFrom(), k, ss);
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
        sb.append("FLOW INSENSITIVE [");

        IntIterator iter = deltaFI.keyIterator();
        while (iter.hasNext()) {
            int i = iter.next();
            MutableIntSet s = deltaFI.get(i);
            sb.append("\n" + i + " " + g.lookupPointsToGraphNodeDictionary(i));
            sb.append(" --> ");
            IntIterator iter2 = s.intIterator();
            while (iter2.hasNext()) {
                int ik = iter2.next();
                sb.append("\n\t" + ik + " " + g.lookupInstanceKeyDictionary(ik));
            }
            size += s.size();
        }
        sb.append("\n]");
        sb.append("\nFLOW SENSITIVE [");
        iter = deltaFS.keyIterator();
        while (iter.hasNext()) {
            int i = iter.next();
            IntMap<ProgramPointSetClosure> s = deltaFS.get(i);
            PointsToGraphNode n = g.lookupPointsToGraphNodeDictionary(i);
            sb.append("\n" + n + " --> ");
            IntIterator iter2 = s.keyIterator();
            while (iter2.hasNext()) {
                int ik = iter2.next();
                sb.append("\n\t" + ik + " " + g.lookupInstanceKeyDictionary(ik));
                sb.append("\n\t\t" + s.get(ik));
            }
            size += s.size();
        }

        sb.append("\n]\n\t(size " + size + ")");
        return sb.toString();
    }

    public Iterator<InstanceKeyRecency> pointsToIterator(PointsToGraphNode node, InterProgramPointReplica ippr,
                                                         StmtAndContext originator) {
        int n = g.lookupDictionary(node);
        assert n >= 0;
        return g.new IntToInstanceKeyIterator(pointsToIntIterator(n, ippr, originator));
    }

    public IntIterator pointsToIntIterator(/*PointsToGraphNode*/int n, InterProgramPointReplica ippr,
                                           StmtAndContext originator) {

        ArrayList<IntIterator> iterators = new ArrayList<>(3);

        if (g.isFlowSensitivePointsToGraphNode(n)) {
            if (!deltaAllocationSites.isEmpty()) {
                IntIterator deltaAllocationSitesAware = new ProgramPointIntIterator(g.pointsToSetFS(n),
                                                                                    ippr,
                                                                                    new StmtAndContextReachabilityOriginator(originator),
                                                                                    this.deltaAllocationSites);

                if (deltaFS.isEmpty()) {
                    // n is a flow sensitive points to node, and this delta is for new allocation sites.
                    // What we want to do is return the program point iterator for (the representative of n)
                    // that only uses the new allocation sites.
                    return deltaAllocationSitesAware;
                }

                // there is both a nonempty deltaAllocationSites and a nonempty deltaFS
                // add deltaAllocationSitesAware to the union.
                iterators.add(deltaAllocationSitesAware);
            }
        }
        // deltaAllocationSites is empty, and/or n is not flow sensitive. Either way, we can ignore deltaAllocationSites.

        // we need to look in delta for all the possible representatives that n has been known by.
        // This is because this GraphDelta may have been created sometime
        // before n got collapsed.
        MutableIntSet s = deltaFI.get(n);
        if (s != null) {
            iterators.add(s.intIterator());
        }
        IntMap<ProgramPointSetClosure> sfs = deltaFS.get(n);

        if (sfs != null) {
            iterators.add(new ProgramPointIntIterator(sfs,
                                                      ippr,
                                                      new StmtAndContextReachabilityOriginator(originator)));
        }

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

    /**
     * Iterator for the points-to graph nodes in the domain of this graph delta
     *
     * @return iterator
     */
    public/*Iterator<PointsToGraphNode>*/IntIterator domainIterator() {
        return g.new SortedIntSetUnionIterator(deltaFI.keyIterator(), deltaFS.keyIterator());
    }

    public IntIterator flowSensitiveDomainIterator() {
        return deltaFS.keyIterator();
    }

    public IntIterator newAllocationSitesIterator() {
        return deltaAllocationSites.keyIterator();
    }

    public IntMap<ProgramPointSetClosure> flowSensitivePointsTo(/*PointsToGraphNode*/int n) {
        return deltaFS.get(n);
    }
}
