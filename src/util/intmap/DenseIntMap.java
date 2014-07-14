package util.intmap;

import java.util.ConcurrentModificationException;

import com.ibm.wala.util.intset.IntIterator;

public class DenseIntMap<T> implements IntMap<T> {
    private static final int DEFAULT_INITIAL_CAPACITY = 10;
    private Object[] array;
    private int size;
    private int version;

    public DenseIntMap(int initialCapacity) {
        this.array = new Object[initialCapacity];
        this.size = 0;
        this.version = 0;
    }

    public DenseIntMap() {
        this(DEFAULT_INITIAL_CAPACITY);
    }
    @Override
    public boolean containsKey(int i) {
        if (i < 0 || i >= array.length) {
            return false;
        }
        return array[i] != null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public T get(int i) {
        if (i < 0 || i >= array.length) {
            return null;
        }
        return (T) array[i];
    }

    @SuppressWarnings("unchecked")
    @Override
    public T put(int i, T val) {
        if (i >= 0 || i < array.length) {
            Object existing = array[i];
            array[i] = val;
            if (existing == null && val != null) {
                size++;
            }
            else if (existing != null && val == null) {
                size--;
            }
            return (T) existing;
        }
        if (i < 0) {
            throw new IllegalArgumentException("Only handle non-negative ints");
        }
        // need to expand
        float newExtent = array.length * getExpansionFactor() + 1;
        Object[] tmp = new Object[(int) newExtent];
        System.arraycopy(array, 0, tmp, 0, array.length);
        this.array = tmp;
        array[i] = val;
        size++;
        version++;
        return null;
    }

    protected float getExpansionFactor() {
        return 1.5f;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public IntIterator keyIterator() {
        final int myVersion = this.version;
        return new IntIterator() {
            int count = 0;

            int last = -1;

            @Override
            public boolean hasNext() {
                return count < size;
            }

            @Override
            public int next() {
                if (myVersion != version) {
                    throw new ConcurrentModificationException();
                }
                count++;
                do {
                    last++;
                } while (array[last] == null);
                return last;
            }
        };
    }

    @Override
    public int max() {
        int m = array.length - 1;
        while (m >= 0 && array[m] == null) {
            m--;
        }
        return m;
    }
}
