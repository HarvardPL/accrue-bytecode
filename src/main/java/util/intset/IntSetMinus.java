package util.intset;

import java.util.NoSuchElementException;

import analysis.pointer.graph.AbstractIntSet;

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;

/**
 * The (lazy) union of two IntSets. For iteration to work, however, the iteration of the underlying IntSets must be in
 * ascending order.
 */
public class IntSetMinus extends AbstractIntSet {
    final IntSet a;
    final IntSet minus;

    public IntSetMinus(IntSet a, IntSet minus) {
        this.a = a;
        this.minus = minus;
        if (a == null) {
            throw new IllegalArgumentException("a is null");
        }
        if (minus == null) {
            throw new IllegalArgumentException("minus is null");
        }
    }

    private Boolean isEmpty = null; // cache the result
    @Override
    public boolean isEmpty() {
        if (isEmpty == null) {
            isEmpty = !this.intIterator().hasNext();
        }
        return isEmpty;
    }

    @Override
    public boolean contains(int x) {
        return this.a.contains(x) && !this.minus.contains(x);
    }

    @Override
    public IntIterator intIterator() {
        return new SortedIntSetMinusIterator(this.a.intIterator(), this.minus.intIterator());
    }


    public static class SortedIntSetMinusIterator implements IntIterator {
        final IntIterator a;
        final IntIterator minus;
        int aNext = -1;
        int minusNext = -1;
        boolean aNextValid = false;
        boolean minusNextValid = false;

        public SortedIntSetMinusIterator(IntIterator a, IntIterator minus) {
            this.a = a;
            this.minus = minus;
        }

        @Override
        public boolean hasNext() {
            do {
                // make sure the iterators are valid if possible.
                if (!this.aNextValid && this.a.hasNext()) {
                    int t = this.a.next();
                    assert (t > this.aNext) : "Interator a is not in ascending order";
                    this.aNext = t;
                    this.aNextValid = true;
                }
                if (!this.minusNextValid && this.minus.hasNext()) {
                    int t = this.minus.next();
                    assert (t > this.minusNext) : "Interator minus is not in ascending order";
                    this.minusNext = t;
                    this.minusNextValid = true;
                }
                if (!this.aNextValid) {
                    // no more as left.
                    assert !this.a.hasNext();
                    return false;
                }
                else if (this.aNextValid && !this.minusNextValid) {
                    // we still have "a"s left, and no minuses.
                    assert (!this.minus.hasNext());
                    return true;
                }
                else if (this.aNextValid && this.minusNextValid && this.aNext < this.minusNext) {
                    // the next thing to minus is not aNext.
                    return true;
                }
                else if (this.aNextValid && this.minusNextValid && this.aNext == this.minusNext) {
                    // the next thing is removed!
                    this.aNextValid = false;
                }
                else if (this.aNextValid && this.minusNextValid && this.aNext > this.minusNext) {
                    // we need the next minus element to know if the next "a" is valid.
                    this.minusNextValid = false;
                }
                else {
                    assert false : "We failed to handle all cases";
                }
            } while (true);
        }

        @Override
        public int next() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }
            assert this.aNextValid && (!this.minusNextValid || this.aNext < this.minusNext);

            this.aNextValid = false;
            return this.aNext;
        }

    }
}
