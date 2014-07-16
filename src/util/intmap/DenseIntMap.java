package util.intmap;

import com.ibm.wala.util.intset.IntIterator;

public class DenseIntMap<T> implements IntMap<T> {
    private static final int DEFAULT_INITIAL_CAPACITY = 10;
    private Object[] array;
    private int size;
    private int max;

    public DenseIntMap(int initialCapacity) {
        this.array = new Object[initialCapacity];
        this.size = 0;
        this.max = -1;
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
        try {
            if (i >= 0 && i < array.length) {
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
            float newExtent = Math.max(array.length * getExpansionFactor() + 1, i + 1);
            Object[] tmp = new Object[(int) newExtent];
            System.arraycopy(array, 0, tmp, 0, array.length);
            this.array = tmp;
            this.array[i] = val;
            size++;
            return null;
        }
        finally {
            // update max
            if (val != null && i > max) {
                max = i;
            }
            else if (val == null && i == max) {
                // need to lower max.
                while (max > 0 && array[--max] == null) {
                    ;
                }
                if (max == 0 && array[0] == null) {
                    max = -1;
                }
                // max is now the highest index that has a non-null value, -1
                // if there is none.

            }
        }
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
        return new IntIterator() {
            int last = -1;

            @Override
            public boolean hasNext() {
                return last < max;
            }

            @Override
            public int next() {
                while (array[++last] == null) {
                    ;
                }
                return last;
            }
        };
    }

    @Override
    public int max() {
        return max;
    }

    @Override
    public T remove(int key) {
        return put(key, null);
    }
}
