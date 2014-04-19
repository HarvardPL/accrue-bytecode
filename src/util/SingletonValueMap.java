package util;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Unmodifiable map where every key is mapped to the same value. Keys not in the
 * map will return null.
 * 
 * @param <K>
 *            the type of keys maintained by this map
 * @param <V>
 *            the type of the mapped value
 */
public final class SingletonValueMap<K, V> implements Map<K, V> {

    /**
     * Set of keys
     */
    private final Set<K> keySet;
    /**
     * Set of values
     */
    private final V value;

    /**
     * Create a new map that can only contain a single value. Once created the
     * set of keys and value cannot be modified.
     * 
     * @param keys
     *            set of keys in mapped to the value
     * @param value
     *            unmodifiable singleton value that all keys are mapped to
     */
    public SingletonValueMap(Set<K> keys, V value) {
        this.keySet = Collections.unmodifiableSet(keys);
        this.value = value;
    }

    @Override
    public int size() {
        return keySet.size();
    }

    @Override
    public boolean isEmpty() {
        return keySet.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return keySet.contains(key);
    }

    @Override
    public boolean containsValue(Object o) {
        return this.value == null ? o == null : this.value.equals(o);
    }

    @Override
    public V get(Object key) {
        if (containsKey(key)) {
            return value;
        }
        return null;
    }

    @Override
    public Set<K> keySet() {
        return keySet;
    }

    @Override
    public Collection<V> values() {
        return Collections.singleton(value);
    }

    @Override
    public Set<java.util.Map.Entry<K, V>> entrySet() {
        Set<Entry<K, V>> entries = new LinkedHashSet<>();
        for (K key : keySet) {
            entries.add(new AbstractMap.SimpleEntry<K, V>(key, value));
        }
        return Collections.unmodifiableSet(entries);
    }

    @Override
    public V put(K key, V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((keySet == null) ? 0 : keySet.hashCode());
        result = prime * result + ((value == null) ? 0 : value.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        @SuppressWarnings("rawtypes")
        SingletonValueMap other = (SingletonValueMap) obj;
        if (keySet == null) {
            if (other.keySet != null)
                return false;
        } else if (!keySet.equals(other.keySet))
            return false;
        if (value == null) {
            if (other.value != null)
                return false;
        } else if (!value.equals(other.value))
            return false;
        return true;
    }
    
    @Override
    public String toString() {
        return "{KEYS:" + keySet + "=" + value + "}";
    }
}
