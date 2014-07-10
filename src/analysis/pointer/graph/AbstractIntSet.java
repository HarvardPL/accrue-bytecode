package analysis.pointer.graph;

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;

public abstract class AbstractIntSet implements IntSet {

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
    public boolean isEmpty() {
        return intIterator().hasNext();
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException();
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

    @Override
    public boolean contains(int i) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IntSet intersection(IntSet that) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IntSet union(IntSet that) {
        throw new UnsupportedOperationException();
    }

}
