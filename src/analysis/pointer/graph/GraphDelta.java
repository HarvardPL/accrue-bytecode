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
    private final Map<PointsToGraphNode, Set<InstanceKey>> d;

    public GraphDelta() {
        this.d = new LinkedHashMap<>();
    }
    public GraphDelta(PointsToGraphNode src, InstanceKey trg) {
        this.d = new LinkedHashMap<>();
        Set<InstanceKey> s = new LinkedHashSet<>();
        s.add(trg);
        this.d.put(src, s);
    }

    private Set<InstanceKey> getOrCreateSet(PointsToGraphNode src) {
        Set<InstanceKey> s = d.get(src);
        if (s == null) {
            s = new LinkedHashSet<>();
            d.put(src, s);
        }
        return s;
    }

    public void add(PointsToGraphNode src, InstanceKey trg) {
        this.getOrCreateSet(src).add(trg);
    }

    @SuppressWarnings("unchecked")
    public Set<InstanceKey> getPointsToSet(PointsToGraphNode node) {
        Set<InstanceKey> s = this.d.get(node);
        if (s == null) {
            return Collections.EMPTY_SET;
        }
        return s;
    }

    public Set<PointsToGraphNode> domain() {
        return Collections.unmodifiableSet(this.d.keySet());
    }

    /**
     * Combine this GraphDelta with another graph delta. For efficiency, this method may be implemented imperatively.
     * 
     * @param d
     * @return
     */
    public GraphDelta combine(GraphDelta d) {
        if (d != null) {
            for (PointsToGraphNode src : d.d.keySet()) {
                this.getOrCreateSet(src).addAll(d.d.get(src));
            }
        }
        return this;
    }

    public Set<ObjectField> getObjectFields(FieldReference fieldReference) {
        Set<ObjectField> possibles = new LinkedHashSet<>();
        for (PointsToGraphNode src : this.d.keySet()) {
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
        for (PointsToGraphNode src : this.d.keySet()) {
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
        return d.isEmpty();
    }
}
