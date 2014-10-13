package util.intmap;

import java.util.NoSuchElementException;

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSetUtil;

/**
 * Sparse int map, based on the MutableSparseIntSet WALA implementation.
 */
public class SparseIntMap<T> implements IntMap<T> {
    /**
     * The backing store of int arrays
     */
    protected int[] keys;
    /**
     * The backing store of int arrays
     */
    protected Object[] values;

    /**
     * The number of entries in the backing store that are valid.
     */
    protected int size = 0;

    /**
     * Subclasses should use this with extreme care.
     */
    public SparseIntMap() {
        this.keys = null;
        this.values = null;
        this.size = 0;
    }

    /**
     * Subclasses should use this with extreme care.
     */
    public SparseIntMap(int initialSize) {
        this.keys = new int[initialSize];
        this.values = new Object[initialSize];
        this.size = 0;
    }


    /**
     * Does this key set contain value x?
     *
     * @see com.ibm.wala.util.intset.IntSet#contains(int)
     */
    @Override
    public final boolean containsKey(int x) {
        if (keys == null) {
            return false;
        }
        return IntSetUtil.binarySearch(keys, x, 0, size - 1) >= 0;
    }


    @Override
    public final int size() {
        return size;
    }

    @Override
    public final boolean isEmpty() {
        return size == 0;
    }


    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("{ ");
        if (keys != null) {
            for (int ii = 0; ii < size; ii++) {
                if (ii > 0) {
                    sb.append(", ");
                }
                sb.append(keys[ii]);
                sb.append(": ");
                sb.append(values[ii]);
            }
        }
        sb.append("}");
        return sb.toString();
    }


    /*
     * @see com.ibm.wala.util.intset.IntSet#iterator()
     */
    @Override
    public IntIterator keyIterator() {
        return new IntIterator() {
            int lastKey = -1;
            int i = 0;

            @Override
            public boolean hasNext() {
                return (i < size);
            }

            @Override
            public int next() throws NoSuchElementException {
                if (keys == null) {
                    throw new NoSuchElementException();
                }
                int t;
                while ((t = keys[i++]) <= lastKey) {
                    ; // increase i until we see a value greater than the last one
                      // This takes care of elements added while we are iterating.
                }
                lastKey = t;
                return lastKey;
            }
        };
    }

    /**
     * @return the largest element in the set
     */
    @Override
    public final int max() throws IllegalStateException {
        if (keys == null) {
            throw new IllegalStateException("Illegal to ask max() on an empty key set");
        }
        return (size > 0) ? keys[size - 1] : -1;
    }


    @SuppressWarnings("unchecked")
    @Override
    public T get(int x) {
        if (keys == null) {
            return null;
        }
        int ind = IntSetUtil.binarySearch(keys, x, 0, size - 1);
        if (ind >= 0) {
            return (T) values[ind];
        }
        return null;
    }


    @SuppressWarnings("unchecked")
    @Override
    public T put(int key, T val) {
        if (keys == null) {
            keys = new int[getInitialNonEmptySize()];
            values = new Object[getInitialNonEmptySize()];
            size = 1;
            keys[0] = key;
            values[0] = val;
            return null;
        }
        int insert;
        if (size == 0 || key > max()) {
            insert = size;
        }
        else if (key == max()) {
            Object existing = values[size - 1];
            values[size - 1] = val;
            return (T) existing;
        }
        else {
            for (insert = 0; insert < size; insert++) {
                if (keys[insert] >= key) {
                    break;
                }
            }
        }
        if (insert < size && keys[insert] == key) {
            Object existing = values[insert];
            values[insert] = val;
            return (T) existing;
        }
        if (size < keys.length - 1) {
            // there's space in the backing elements array. Use it.
            if (size != insert) {
                System.arraycopy(keys, insert, keys, insert + 1, size - insert);
                System.arraycopy(values, insert, values, insert + 1, size - insert);
            }
            size++;
            keys[insert] = key;
            values[insert] = val;
            return null;
        }
        // no space left. expand the backing array.
        float newExtent = keys.length * getExpansionFactor() + 1;
        int[] tmpKeys = new int[(int) newExtent];
        Object[] tmpValues = new Object[(int) newExtent];
        System.arraycopy(keys, 0, tmpKeys, 0, insert);
        System.arraycopy(values, 0, tmpValues, 0, insert);
        if (size != insert) {
            System.arraycopy(keys, insert, tmpKeys, insert + 1, size - insert);
            System.arraycopy(values, insert, tmpValues, insert + 1, size - insert);
        }
        tmpKeys[insert] = key;
        tmpValues[insert] = val;
        size++;
        keys = tmpKeys;
        values = tmpValues;
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public T remove(int key) {
        if (keys != null) {
            int remove;
            // special case the max element
            if (key == max()) {
                remove = size - 1;
            }
            else {
                for (remove = 0; remove < size; remove++) {
                    if (keys[remove] >= key) {
                        break;
                    }
                }
                if (remove == size) {
                    // Nothing to remove
                    return null;
                }
            }
            if (keys[remove] == key) {
                Object existing = values[remove];
                if (size == 1) {
                    keys = null;
                    values = null;
                    size = 0;
                }
                else {
                    if (remove < size - 1) {
                        System.arraycopy(keys, remove + 1, keys, remove, size - remove - 1);
                        System.arraycopy(values, remove + 1, values, remove, size - remove - 1);
                    }
                    size--;
                }
                return (T) existing;
            }
        }
        return null;
    }

    protected float getExpansionFactor() {
        return 1.5f;
    }

    protected int getInitialNonEmptySize() {
        return 2;
    }

}
