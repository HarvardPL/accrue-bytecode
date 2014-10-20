package util.intset;

import java.util.NoSuchElementException;

import analysis.pointer.graph.AbstractIntSet;

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;

/**
 * The (lazy) union of two IntSets. For iteration to work, however, the iteration of the underlying IntSets must be in
 * ascending order.
 */
public class IntSetUnion extends AbstractIntSet {
    final IntSet a;
    final IntSet b;

    public IntSetUnion(IntSet a, IntSet b) {
        this.a = a;
        this.b = b;
        if (a == null) {
            throw new IllegalArgumentException("a is null");
        }
        if (b == null) {
            throw new IllegalArgumentException("b is null");
        }
    }

    @Override
    public boolean isEmpty() {
        return this.a.isEmpty() && this.b.isEmpty();
    }

    @Override
    public boolean contains(int x) {
        return this.a.contains(x) || this.b.contains(x);
    }

    @Override
    public IntIterator intIterator() {
        return new SortedIntSetUnionIterator(this.a.intIterator(), this.b.intIterator());
    }

}

class SortedIntSetUnionIterator implements IntIterator {
    final IntIterator a;
    final IntIterator b;
    int aNext = -1;
    int bNext = -1;
    boolean aNextValid = false;
    boolean bNextValid = false;

    SortedIntSetUnionIterator(IntIterator a, IntIterator b) {
        this.a = a;
        this.b = b;
    }

    @Override
    public boolean hasNext() {
        if (!this.aNextValid && this.a.hasNext()) {
            int t = this.a.next();
            assert (t > this.aNext) : "Interator a is not in ascending order";
            this.aNext = t;
            this.aNextValid = true;
        }
        if (!this.bNextValid && this.b.hasNext()) {
            int t = this.b.next();
            assert (t > this.bNext) : "Interator b is not in ascending order";
            this.bNext = t;
            this.bNextValid = true;
        }
        return this.aNextValid || this.bNextValid;
    }

    @Override
    public int next() {
        if (!this.hasNext()) {
            throw new NoSuchElementException();
        }
        // at least one of aNext and bNext is non-null
        if (!this.aNextValid) {
            this.bNextValid = false;
            return this.bNext;
        }
        if (!this.bNextValid) {
            this.aNextValid = false;
            return this.aNext;
        }
        // both are non-null
        if (this.aNext == this.bNext) {
            // they are the same value
            this.aNextValid = this.bNextValid = false;
            return this.aNext;
        }
        if (this.aNext < this.bNext) {
            this.aNextValid = false;
            return this.aNext;
        }
        this.bNextValid = false;
        return this.bNext;
    }

}
