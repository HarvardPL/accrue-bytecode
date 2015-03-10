package analysis.dataflow.flowsensitizer;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class FlowSensitizedVariableMap {

    private final Map<Integer, Integer> sensitizingMap;
    public static final int INITIAL_SUBSCRIPT = 0;

    /* Factory methods */

    public static FlowSensitizedVariableMap makeEmpty() {
        return new FlowSensitizedVariableMap();
    }

    /**
     * I am truly sorry this is such a complicated implementation of what should be a dead simple method. Basically, I'm
     * optimizing for the size zero and size one cases. Otherwise, I use the non-mutating join method to repeatedly join
     * the elements of the collection together and return the result.
     *
     * @param s
     * @param dependencyMap BEWARE: this map is mutatively updated with new sensitizer dependencies
     * @return
     */
    public static FlowSensitizedVariableMap joinCollection(Collection<FlowSensitizedVariableMap> s,
                                                           Map<Integer, Map<Integer, Set<Integer>>> dependencyMap) {
        if (s.size() == 0) {
            return makeEmpty();
        }

        Iterator<FlowSensitizedVariableMap> it = s.iterator();

        FlowSensitizedVariableMap t = it.next();

        if (s.size() == 1) {
            return t;
        }

        while (it.hasNext()) {
            t = t.join(it.next(), dependencyMap);
        }

        return t;
    }

    /* Constructors */

    private FlowSensitizedVariableMap() {
        this.sensitizingMap = new HashMap<>();
    }

    private FlowSensitizedVariableMap(Map<Integer, Integer> sensitizingMap) {
        this.sensitizingMap = sensitizingMap;
    }

    /* Logic */

    /**
     * Records that a new, flow-sensitive, version of {@code t} should become valid after source location {@code l}.
     *
     * @param t a flow insensitive variable
     * @param l a source location
     */
    public FlowSensitizedVariableMap freshFlowSensitive(Integer t) {
        Map<Integer, Integer> newMap = new HashMap<>();
        // this.map.forEach((k, v) -> newMap.put(k, k.equals(t) ? v + 1 : v));
        newMap.putAll(this.sensitizingMap);
        if (newMap.containsKey(t)) {
            newMap.put(t, newMap.get(t) + 1);
        }
        else {
            newMap.put(t, INITIAL_SUBSCRIPT + 1);
        }
        System.err.println("freshFlowSensitive(" + t + ") = " + new FlowSensitizedVariableMap(newMap));
        return new FlowSensitizedVariableMap(newMap);
    }

    /**
     * Should be equivalent to
     *
     * @{code IFlowSensitizedVariableMapm = ...; for (Integer t : ts) { m = m.freshFlowSensitive(t); } return m;}
     *
     * @param t
     * @return
     */
    public FlowSensitizedVariableMap freshFlowSensitive(Set<Integer> ts) {
        // this.map.forEach((k, v) -> newMap.put(k, setOrMap(ts, t -> k.equals(t)) ? v + 1 : v));
        Map<Integer, Integer> newMap = new HashMap<>();
        for (Entry<Integer, Integer> kv : this.sensitizingMap.entrySet()) {
            Integer k = kv.getKey();
            Integer v = kv.getValue();

            boolean acc = false;
            for (Integer t : ts) {
                acc = acc || k.equals(t);
                if (acc) {
                    break;
                }
            }

            if (acc) {
                newMap.put(k, nextVar(v));
            }
            else {
                newMap.put(k, v);
            }
        }
        return new FlowSensitizedVariableMap(newMap);
    }

    @SuppressWarnings("static-method")
    private int nextVar(int sensitizer) {
        return sensitizer + 1;
    }

    public FlowSensitizedVariableMap join(FlowSensitizedVariableMap m,
                                          Map<Integer, Map<Integer, Set<Integer>>> dependencyMapForVar) {
        Map<Integer, Integer> newSensitizingMap = new HashMap<>(this.sensitizingMap);

        for (Entry<Integer, Integer> kv : m.getInsensitiveToFlowSensistiveMap().entrySet()) {
            Integer k = kv.getKey();
            Integer v = kv.getValue();
            Integer v2 = newSensitizingMap.get(k);

            if (newSensitizingMap.containsKey(k) && v != v2) {
                int nextSensitizer = nextVar(Math.max(v, v2));
                newSensitizingMap.put(k, nextSensitizer);
                /* below deals with the dependency map */
                Map<Integer, Set<Integer>> dependencyMap = dependencyMapForVar.containsKey(k)
                        ? dependencyMapForVar.get(k) : new HashMap<Integer, Set<Integer>>();
                Set<Integer> dependencies = dependencyMap.containsKey(nextSensitizer)
                        ? dependencyMap.get(nextSensitizer) : new HashSet<Integer>();
                dependencies.add(v);
                dependencies.add(v2);
                dependencyMap.put(nextSensitizer, dependencies);
                dependencyMapForVar.put(k, dependencyMap);
            }
            else {
                newSensitizingMap.put(k, v);
            }
        }
        return new FlowSensitizedVariableMap(newSensitizingMap);
    }

    /**
     * DO NOT MODIFY THIS MAP
     */
    public Map<Integer, Integer> getInsensitiveToFlowSensistiveMap() {
        return this.sensitizingMap;
    }

    @Override
    public String toString() {
        return "FlowSensitizedVariableMap(" + this.sensitizingMap + ")";
    }

}
