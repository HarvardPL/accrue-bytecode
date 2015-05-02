package analysis.dataflow.flowsensitizer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public final class DataFlowUtilities {
    public static <K, V> Map<K, Set<V>> deepCopyMap(Map<K, Set<V>> m) {
        Map<K, Set<V>> m2 = new HashMap<>();

        for (Entry<K, Set<V>> kv : m.entrySet()) {
            K var = kv.getKey();
            Set<V> s = kv.getValue();

            Set<V> s2 = new HashSet<>(s);
            m2.put(var, s2);
        }

        return m2;
    }

    public static <K, V> void combineMaps(Map<K, Set<V>> m, Map<K, Set<V>> n) {
        for (Entry<K, Set<V>> kv : n.entrySet()) {
            K var = kv.getKey();
            Set<V> s = kv.getValue();

            if (m.containsKey(var)) {
                m.get(var).addAll(s);
            }
            else {
                m.put(var, s);
            }
        }
    }

    public static <K, V> void putInSetMap(Map<K, Set<V>> m, K var, V sensitizer) {
        if (m.containsKey(var)) {
            m.get(var).add(sensitizer);
        }
        else {
            Set<V> s = new HashSet<>();
            s.add(sensitizer);
            m.put(var, s);
        }
    }

}
