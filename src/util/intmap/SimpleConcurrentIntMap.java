package util.intmap;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.ibm.wala.util.intset.IntIterator;

/**
 * A simple ConcurrentIntMap, built using the ConcurrentHashMap from the JDK.
 *
 * @param <T>
 */
public class SimpleConcurrentIntMap<T> implements ConcurrentIntMap<T> {
    public final ConcurrentHashMap<Integer, T> map = new ConcurrentHashMap<>();

    /**
     * Best guess at the max key.
     */
    private AtomicInteger max = new AtomicInteger(-1);

    public SimpleConcurrentIntMap() {

    }

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
        T ret = map.put(i, val);
        if (ret == null) {
            // This is a key we haven't seen before.
            checkMax(i);
        }
        return ret;

    }

    private void checkMax(int key) {
        int lastReturned;
        do {
            lastReturned = this.max.get();
            if (lastReturned >= key) {
                return;
            }
            // we need to set the new max
        } while (!this.max.compareAndSet(lastReturned, key));
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
        return this.max.get();
    }

    @Override
    public T remove(int key) {
        return map.remove(key);
    }

    @Override
    public T putIfAbsent(int key, T value) {
        T ret = map.putIfAbsent(key, value);
        if (ret == null) {
            // This is a key we haven't seen before.
            checkMax(key);
        }
        return ret;
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

    @Override
    public String toString() {
        return map.toString();
    }

}
