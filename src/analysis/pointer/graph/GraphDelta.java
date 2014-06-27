package analysis.pointer.graph;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
        addToSupersetsOf(n, Collections.singleton(ik));

    }

    public void addCopyEdges(PointsToGraphNode source, TypeFilter filter,
            PointsToGraphNode target) {
        // go through the points to set of source, and add anything that target doesn't already point to.
        Set<InstanceKey> diff = g.getDifference(source, filter, target);

        // Now take care of all the supersets of target...
        addToSupersetsOf(target, diff);
    }

    private void addToSupersetsOf(PointsToGraphNode target, Set<InstanceKey> set) {

        if (!getOrCreateSet(target).addAll(set)) {
            // we didn't add anything, so don't bother recursing...
            return;
        }

        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> iter =
                g.immediateSuperSetsOf(target);
        while (iter.hasNext()) {
            OrderedPair<PointsToGraphNode, TypeFilter> superSet = iter.next();

            Set<InstanceKey> filteredSet;
            if (superSet.snd() == null) {
                filteredSet = set;
            }
            else {
                filteredSet =
                        new PointsToGraph.FilteredSet(set, superSet.snd());
            }

            // Actually check which of the filtered set is really added to the target.
            Set<InstanceKey> diff =
                    g.getDifference(filteredSet, superSet.fst());
            addToSupersetsOf(superSet.fst(), diff);
        }
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

    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode n) {
        Set<InstanceKey> s = delta.get(n);
        if (s == null) {
            return Collections.emptyIterator();
        }
        return s.iterator();
    }

    public Iterator<PointsToGraphNode> domainIterator() {
        return delta.keySet().iterator();
    }

}
