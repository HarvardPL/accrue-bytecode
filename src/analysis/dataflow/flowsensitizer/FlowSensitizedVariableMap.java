package analysis.dataflow.flowsensitizer;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class FlowSensitizedVariableMap {

    private final Map<Integer, Integer> map;

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
     * @return
     */
    public static FlowSensitizedVariableMap joinCollection(Collection<FlowSensitizedVariableMap> s) {
        if (s.size() == 0) {
            return makeEmpty();
        }

        Iterator<FlowSensitizedVariableMap> it = s.iterator();

        FlowSensitizedVariableMap t = it.next();

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

    private FlowSensitizedVariableMap(Map<Integer, Integer> map) {
        this.map = map;
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
        for (Entry<Integer, Integer> kv : this.map.entrySet()) {
            Integer k = kv.getKey();
            Integer v = kv.getValue();
            if (k.equals(t)) {
                newMap.put(k, v + 1);
            }
            else {
                newMap.put(k, v);
            }
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
        for (Entry<Integer, Integer> kv : this.map.entrySet()) {
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
                newMap.put(k, v + 1);
            }
            else {
                newMap.put(k, v);
            }
        }
        return new FlowSensitizedVariableMap(newMap);
    }

    public FlowSensitizedVariableMap join(FlowSensitizedVariableMap m) {
        Map<Integer, Integer> newMap = new HashMap<>();
        // this.map.forEach((k, v) -> newMap.put(k, v));
        for(Entry<Integer, Integer> kv : this.map.entrySet()) {
            Integer k = kv.getKey();
            Integer v = kv.getValue();
            newMap.put(k, v);
        }
//        m.getInsensitiveToFlowSensistiveMap().forEach((k, v) -> newMap.merge(k,
//                                        v,
//                                        (oldv, newv) -> {
//                                            throw new RuntimeException("overlapping maps not allowed " + this.map
//                                                    + " " + m.getInsensitiveToFlowSensistiveMap());
//                                        }));

        for(Entry<Integer, Integer> kv : m.getInsensitiveToFlowSensistiveMap().entrySet()) {
            Integer k = kv.getKey();
            Integer v = kv.getValue();
            if (newMap.containsKey(k)) {
                throw new RuntimeException("overlapping maps not allowed " + this.map
                                           + " " + m.getInsensitiveToFlowSensistiveMap());
            }
            newMap.put(k, v);
        }
        return new FlowSensitizedVariableMap(newMap);
    }

    /**
     * DO NOT MODIFY THIS MAP
     */
    public Map<Integer, Integer> getInsensitiveToFlowSensistiveMap() {
        return this.map;
    }

    @Override
    public String toString() {
        return "FlowSensitizedVariableMap(" + this.map + ")";
    }

}
