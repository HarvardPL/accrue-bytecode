package util.intmap;

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;

/**
 * Create an IntSet from given IntMap<Boolean>.
 */
public class IntSetFromMap implements IntSet {
    protected final IntMap<Boolean> map;

    public IntSetFromMap(IntMap<Boolean> m) {
        if (m == null) {
            throw new IllegalArgumentException();
        }
        this.map = m;
    }
    @Override
    public boolean containsAny(IntSet set) {
        IntIterator iter = set.intIterator();
        while (iter.hasNext()) {
            if (contains(iter.next())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean contains(int i) {
        return map.containsKey(i);
    }

    @Override
    public IntSet intersection(IntSet that) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IntSet union(IntSet that) {
        throw new UnsupportedOperationException();
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
    public IntIterator intIterator() {
        return map.keyIterator();
    }

    @Override
    public void foreach(IntSetAction action) {
        IntIterator iter = intIterator();
        while (iter.hasNext()) {
            action.act(iter.next());
        }

    }

    @Override
    public void foreachExcluding(IntSet X, IntSetAction action) {
        IntIterator iter = intIterator();
        while (iter.hasNext()) {
            int i = iter.next();
            if (!X.contains(i)) {
                action.act(i);
            }
        }

    }

    @Override
    public int max() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean sameValue(IntSet that) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSubset(IntSet that) {
        throw new UnsupportedOperationException();
    }

}
