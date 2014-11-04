package util;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class PairSet<T> extends AbstractSet<T> {
    private final T a;
    private final T b;

    public PairSet(T a, T b) {
        assert a != null && b != null;
        this.a = a;
        this.b = b;
    }

    @Override
    public boolean contains(Object o) {
        return (a == o || b == o || a.equals(o) || b.equals(o));
    }
    @Override
    public Iterator<T> iterator() {
        return new PairSetIterater();
    }

    private final class PairSetIterater implements Iterator<T> {
        int nextIndex = 0;

        @Override
        public boolean hasNext() {
            return nextIndex < 2;
        }

        @Override
        public T next() {
            if (nextIndex >= 2) {
                throw new NoSuchElementException();
            }
            assert nextIndex == 0 || nextIndex == 1;
            T n = nextIndex == 0 ? PairSet.this.a : PairSet.this.b;
            nextIndex++;
            return n;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public int size() {
        return 2;
    }
}
