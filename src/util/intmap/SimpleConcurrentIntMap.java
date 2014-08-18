package util.intmap;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

import com.ibm.wala.util.intset.IntIterator;

/**
 * A simple ConcurrentIntMap, built using the ConcurrentHashMap from the JDK.
 *
 * @param <T>
 */
public class SimpleConcurrentIntMap<T> implements ConcurrentIntMap<T> {
    public final ConcurrentHashMap<Integer, T> map = new ConcurrentHashMap<>();

    @Override
    public boolean containsKey(int i) {
        return map.containsKey(i);
    }

    @Override
    public T get(int i) {
        return map.get(i);
    }

    @Override
    public T put(int i, T val) {
        return map.put(i, val);
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public IntIterator keyIterator() {
        final Iterator<Integer> iter = this.map.keySet().iterator();
        return new IntIterator() {

            @Override
            public boolean hasNext() {
                return iter.hasNext();
            }

            @Override
            public int next() {
                return iter.next();
            }
        };
    }

    @Override
    public int max() {
        throw new UnsupportedOperationException();
    }

    @Override
    public T remove(int key) {
        return map.remove(key);
    }

    @Override
    public T putIfAbsent(int key, T value) {
        return map.putIfAbsent(key, value);
    }

    @Override
    public boolean remove(int key, T value) {
        return map.remove(key, value);
    }

    @Override
    public boolean replace(int key, T oldValue, T newValue) {
        return map.replace(key, oldValue, newValue);
    }

    @Override
    public T replace(int key, T value) {
        return map.replace(key, value);
    }

}
