package util.intset;

import java.util.NoSuchElementException;

import analysis.pointer.graph.AbstractIntSet;

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;

/**
 * The (lazy) intersection of two IntSets. For iteration to work, however, the iteration of the underlying IntSets must
 * be in ascending order.
 */
public class IntSetIntersection extends AbstractIntSet {
    final IntSet a;
    final IntSet b;

    public IntSetIntersection(IntSet a, IntSet b) {
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
        return this.intIterator().hasNext();
    }

    @Override
    public boolean contains(int x) {
        return this.a.contains(x) && this.b.contains(x);
    }

    @Override
    public IntIterator intIterator() {
        return new SortedIntSetIntersectionIterator(this.a.intIterator(), this.b.intIterator());
    }


    public static class SortedIntSetIntersectionIterator implements IntIterator {
        final IntIterator a;
        final IntIterator b;
        int aNext = -1;
        int bNext = -1;
        boolean aNextValid = false;
        boolean bNextValid = false;

        public SortedIntSetIntersectionIterator(IntIterator a, IntIterator b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public boolean hasNext() {
            while (!(aNextValid && bNextValid && aNext == bNext)) {
                // Make sure that a and b are valid.
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
                if (!this.aNextValid || !this.bNextValid) {
                    // no more left...
                    return false;
                }
                if (aNext < bNext) {
                    // get the next a element.
                    aNextValid = false;
                }
                else if (bNext < aNext) {
                    // get the next b element.
                    bNextValid = false;
                }
            }
            return true;
        }

        @Override
        public int next() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }
            assert (aNextValid && bNextValid && aNext == bNext);
            this.aNextValid = false;
            this.bNextValid = false;
            return this.aNext;
        }

    }
}
