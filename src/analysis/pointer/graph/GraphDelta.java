package analysis.pointer.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * Represents a delta (i.e., a change set) for a PointsToGraph. This is used to both represent the changes that an
 * operation made to a PointsToGraph, and also to allow more efficient processing of statements, which can focus just on
 * the changes to the graph since last time they were processed.
 */
public class GraphDelta {
    private final Map<PointsToGraphNode, Set<InstanceKey>> map;

    public GraphDelta() {
        this.map = new LinkedHashMap<>();
    }

    private Set<InstanceKey> getOrCreateSet(PointsToGraphNode src) {
        Set<InstanceKey> s = map.get(src);
        if (s == null) {
            s = new LinkedHashSet<>();
            map.put(src, s);
        }
        return s;
    }

    public void add(PointsToGraphNode src, InstanceKey trg) {
        this.getOrCreateSet(src).add(trg);
    }

    @SuppressWarnings("unchecked")
    public Set<InstanceKey> getPointsToSet(PointsToGraphNode node) {
        Set<InstanceKey> s = this.map.get(node);
        if (s == null) {
            return Collections.EMPTY_SET;
        }
        return s;
    }

    public Set<PointsToGraphNode> domain() {
        return Collections.unmodifiableSet(this.map.keySet());
    }

    /**
     * Combine this GraphDelta with another graph delta. For efficiency, this method may be implemented imperatively.
     * 
     * @param d
     * @return
     */
    public GraphDelta combine(GraphDelta d) {
        if (d != null) {
            for (PointsToGraphNode src : d.map.keySet()) {
                this.getOrCreateSet(src).addAll(d.map.get(src));
            }
        }
        return this;
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public String toString() {
        return "Delta: ((( " + this.map.toString() + " )))";
    }
}
