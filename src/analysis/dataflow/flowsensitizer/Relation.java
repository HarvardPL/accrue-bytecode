package analysis.dataflow.flowsensitizer;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class Relation<K, V> implements LatticeJoin<Relation<K, V>> {
    /*
     * Public Factories
     */

    public static <K, V> Relation<K, V> makeBottom() {
        return new Relation<>(Relation.<K, V> emptyMap());
    }

    /*
     * Private Data and constructors said data
     */

    private final Map<K, Set<V>> m;

    private static <K, V> Map<K, Set<V>> emptyMap() {
        return new HashMap<>();
    }

    /*
     * Private Constructors
     */

    private Relation(Map<K, Set<V>> m) {
        this.m = m;
    }

    /*
     * Super interfaces
     */

    @Override
    public Relation<K, V> join(Relation<K, V> that) {
        Relation<K, V> r = makeBottom();
        for (Entry<K, Set<V>> kv : that.m.entrySet()) {
            K k = kv.getKey();
            Set<V> s = kv.getValue();

            r.addAll(k, s);
        }
        for (Entry<K, Set<V>> kv : this.m.entrySet()) {
            K k = kv.getKey();
            Set<V> s = kv.getValue();

            r.addAll(k, s);
        }
        return r;
    }

    public void mutateJoin(Relation<K, V> that) {
        for (Entry<K, Set<V>> kv : that.m.entrySet()) {
            K k = kv.getKey();
            Set<V> s = kv.getValue();
            this.m.get(k).addAll(s);
        }
    }

    /*
     * Logic
     */

    public Set<V> add(K k, V v) {
        if (m.containsKey(k)) {
            m.get(k).add(v);
            return Collections.emptySet();
        }
        else {
            Set<V> s = new HashSet<>();
            s.add(v);
            return m.put(k, s);
        }
    }

    public Set<V> addAll(K k, Set<V> vs) {
        if (m.containsKey(k)) {
            m.get(k).addAll(vs);
            return Collections.emptySet();
        }
        else {
            Set<V> s = new HashSet<>(vs);
            return m.put(k, s);
        }
    }

    public Set<V> replace(K k, Set<V> vs) {
        return this.m.put(k, vs);
    }

    public Set<V> get(K k) {
        if (m.containsKey(k)) {
            return m.get(k);
        }
        else {
            return Collections.emptySet();
        }
    }

    public Set<Entry<K, Set<V>>> getEntrySet() {
        return this.m.entrySet();
    }

    public boolean upperBounds(Relation<K, V> that) {
        boolean upperbounds = true;
        for (Entry<K, Set<V>> kv : that.m.entrySet()) {
            K k = kv.getKey();
            Set<V> v = kv.getValue();
            upperbounds = upperbounds && this.m.containsKey(k) && this.m.get(k).containsAll(v);
        }
        return upperbounds;
    }

    @Override
    public Relation<K, V> clone() {
        Map<K, Set<V>> m2 = new HashMap<>(this.m.size());

        for (Entry<K, Set<V>> kv : this.m.entrySet()) {
            K var = kv.getKey();
            Set<V> s = kv.getValue();

            Set<V> s2 = new HashSet<>(s);
            m2.put(var, s2);
        }

        return new Relation<>(m2);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((m == null) ? 0 : m.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof Relation)) {
            return false;
        }
        Relation other = (Relation) obj;
        if (m == null) {
            if (other.m != null) {
                return false;
            }
        }
        else if (!m.equals(other.m)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "Relation [m=" + m + "]";
    }

}
