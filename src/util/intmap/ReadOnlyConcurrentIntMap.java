package util.intmap;

import com.ibm.wala.util.intset.IntIterator;

public class ReadOnlyConcurrentIntMap<T> implements ConcurrentIntMap<T> {
    private final IntMap<T> m;

    public ReadOnlyConcurrentIntMap(IntMap<T> map) {
        this.m = map;
    }

    @Override
    public boolean containsKey(int i) {
        return m.containsKey(i);
    }

    @Override
    public T get(int i) {
        return m.get(i);
    }

    @Override
    public T put(int i, T val) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty() {
        return m.isEmpty();
    }

    @Override
    public int size() {
        return m.size();
    }

    @Override
    public IntIterator keyIterator() {
        return m.keyIterator();
    }

    @Override
    public int max() {
        return m.max();
    }

    @Override
    public T remove(int key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public T putIfAbsent(int key, T value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(int key, T value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean replace(int key, T oldValue, T newValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public T replace(int key, T value) {
        throw new UnsupportedOperationException();
    }

}
