package analysis.pointer.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

/**
 * Represents a delta (i.e., a change set) for a PointsToGraph. This is used to both represent the changes that an
 * operation made to a PointsToGraph, and also to allow more efficient processing of statements, which can focus just on
 * the changes to the graph since last time they were processed.
 */
public class GraphDelta {
    private final Map<PointsToGraphNode, Set<InstanceKey>> map;
    private PointsToGraph g;

    public GraphDelta(PointsToGraph g) {
        this.map = new LinkedHashMap<>();
        this.g = g;
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
        g.recordRead(node);
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

    public Set<ObjectField> getObjectFields(FieldReference fieldReference) {
        Set<ObjectField> possibles = new LinkedHashSet<>();
        for (PointsToGraphNode src : this.map.keySet()) {
            if (src instanceof ObjectField) {
                ObjectField of = (ObjectField) src;
                if (of.fieldReference() != null && of.fieldReference().equals(fieldReference)) {
                    possibles.add(of);
                }
            }               
        }
        return possibles;
    }

    public Set<ObjectField> getObjectFields(String fieldName, TypeReference expectedType) {
        Set<ObjectField> possibles = new LinkedHashSet<>();
        for (PointsToGraphNode src : this.map.keySet()) {
            if (src instanceof ObjectField) {
                ObjectField of = (ObjectField) src;
                if (of.fieldName().equals(fieldName) && of.expectedType().equals(expectedType)) {
                    possibles.add(of);
                }
            }
        }
        return possibles;
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public String toString() {
        return "Delta: ((( " + this.map.toString() + " )))";
    }
}
