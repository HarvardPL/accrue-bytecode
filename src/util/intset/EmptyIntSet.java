package util.intset;

import com.ibm.wala.util.collections.EmptyIntIterator;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * Immutable empty set of integers with the {@link MutableIntSet} interface
 */
public class EmptyIntSet implements MutableIntSet {

    /**
     * Singleton instance of an immutable empty set of integers
     */
    public static final EmptyIntSet INSTANCE = new EmptyIntSet();

    /**
     * Private constructor use static instance
     */
    private EmptyIntSet() {
        // Prevent instantiation
    }

    @Override
    public boolean contains(int i) {
        return false;
    }

    @Override
    public boolean containsAny(IntSet set) {
        return false;
    }

    @Override
    public IntSet intersection(IntSet that) {
        return this;
    }

    @Override
    public IntSet union(IntSet that) {
        return that;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public IntIterator intIterator() {
        return EmptyIntIterator.instance();
    }

    @Override
    public void foreach(IntSetAction action) {
        // Do nothing
    }

    @Override
    public void foreachExcluding(IntSet X, IntSetAction action) {
        // Do nothing
    }

    @Override
    public int max() {
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean sameValue(IntSet that) {
        return that.isEmpty();
    }

    @Override
    public boolean isSubset(IntSet that) {
        return that.isEmpty();
    }

    @Override
    public void copySet(IntSet set) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(IntSet set) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean add(int i) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(int i) {
        return false;
    }

    @Override
    public void clear() {
        // Nothing to do
    }

    @Override
    public void intersectWith(IntSet set) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAllInIntersection(IntSet other, IntSet filter) {
        throw new UnsupportedOperationException();
    }
}
