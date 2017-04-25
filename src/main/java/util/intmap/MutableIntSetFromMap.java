package util.intmap;

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * Create an IntSet from given IntMap<Boolean>.
 */
public class MutableIntSetFromMap extends IntSetFromMap implements MutableIntSet {

    public MutableIntSetFromMap(IntMap<Boolean> m) {
        super(m);
    }

    @Override
    public void copySet(IntSet set) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(IntSet set) {
        boolean changed = false;
        IntIterator iter = set.intIterator();
        while (iter.hasNext()) {
            int i = iter.next();
            changed |= add(i);
        }
        return changed;
    }

    @Override
    public boolean add(int i) {
        return this.map.put(i, Boolean.TRUE) == null;
    }

    @Override
    public boolean remove(int i) {
        return this.map.remove(i) != null;
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
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
