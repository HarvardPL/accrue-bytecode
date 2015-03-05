package analysis.dataflow.flowsensitizer;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class FlowSensitizedVariableMap<T> {

    private final Map<T, Integer> map;

    /* Factory methods */

    public static <T> FlowSensitizedVariableMap<T> makeEmpty() {
        return new FlowSensitizedVariableMap<>();
    }

    /**
     * I am truly sorry this is such a complicated implementation of what should be a dead simple method. Basically, I'm
     * optimizing for the size zero and size one cases. Otherwise, I use the non-mutating join method to repeatedly join
     * the elements of the collection together and return the result.
     *
     * @param s
     * @return
     */
    public static <T> FlowSensitizedVariableMap<T> joinCollection(Collection<FlowSensitizedVariableMap<T>> s) {
        if (s.size() == 0) {
            return makeEmpty();
        }

        Iterator<FlowSensitizedVariableMap<T>> it = s.iterator();

        FlowSensitizedVariableMap<T> t = it.next();

        if (s.size() == 1) {
            return t;
        }

        while (it.hasNext()) {
            t = t.join(it.next());
        }

        return t;
    }

    /* Constructors */

    private FlowSensitizedVariableMap() {
        this.map = new HashMap<>();
    }

    private FlowSensitizedVariableMap(Map<T, Integer> map) {
        this.map = map;
    }

    /* Logic */

    /**
     * Records that a new, flow-sensitive, version of {@code t} should become valid after source location {@code l}.
     *
     * @param t a flow insensitive variable
     * @param l a source location
     */
    public FlowSensitizedVariableMap<T> freshFlowSensitive(T t) {
        Map<T, Integer> newMap = new HashMap<>();
        // this.map.forEach((k, v) -> newMap.put(k, k.equals(t) ? v + 1 : v));
        for (Entry<T, Integer> kv : this.map.entrySet()) {
            T k = kv.getKey();
            Integer v = kv.getValue();
            if (k.equals(t)) {
                newMap.put(k, v + 1);
            }
            else {
                newMap.put(k, v);
            }
        }
        return new FlowSensitizedVariableMap<>(newMap);
    }

    /**
     * Should be equivalent to
     *
     * @{code IFlowSensitizedVariableMap<T> m = ...; for (T t : ts) { m = m.freshFlowSensitive(t); } return m;}
     *
     * @param t
     * @return
     */
    public FlowSensitizedVariableMap<T> freshFlowSensitive(Set<T> ts) {
        // this.map.forEach((k, v) -> newMap.put(k, setOrMap(ts, t -> k.equals(t)) ? v + 1 : v));
        Map<T, Integer> newMap = new HashMap<>();
        for (Entry<T, Integer> kv : this.map.entrySet()) {
            T k = kv.getKey();
            Integer v = kv.getValue();

            boolean acc = false;
            for (T t : ts) {
                acc = acc || k.equals(t);
                if (acc) {
                    break;
                }
            }

            if (acc) {
                newMap.put(k, v + 1);
            }
            else {
                newMap.put(k, v);
            }
        }
        return new FlowSensitizedVariableMap<>(newMap);
    }

    public FlowSensitizedVariableMap<T> join(FlowSensitizedVariableMap<T> m) {
        Map<T, Integer> newMap = new HashMap<>();
        // this.map.forEach((k, v) -> newMap.put(k, v));
        for(Entry<T, Integer> kv : this.map.entrySet()) {
            T k = kv.getKey();
            Integer v = kv.getValue();
            newMap.put(k, v);
        }
//        m.getInsensitiveToFlowSensistiveMap().forEach((k, v) -> newMap.merge(k,
//                                        v,
//                                        (oldv, newv) -> {
//                                            throw new RuntimeException("overlapping maps not allowed " + this.map
//                                                    + " " + m.getInsensitiveToFlowSensistiveMap());
//                                        }));

        for(Entry<T, Integer> kv : m.getInsensitiveToFlowSensistiveMap().entrySet()) {
            T k = kv.getKey();
            Integer v = kv.getValue();
            if (newMap.containsKey(k)) {
                throw new RuntimeException("overlapping maps not allowed " + this.map
                                           + " " + m.getInsensitiveToFlowSensistiveMap());
            }
            newMap.put(k, v);
        }
        return null;
    }

    /**
     * DO NOT MODIFY THIS MAP
     */
    public Map<T, Integer> getInsensitiveToFlowSensistiveMap() {
        return this.map;
    }

}
