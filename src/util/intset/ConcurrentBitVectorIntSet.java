package util.intset;

import java.lang.reflect.Field;
import java.util.NoSuchElementException;

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * A thread-safe implementation of a bit-vector int set. It has restricted functionality, and in particular can only
 * increase in size.
 */
public final class ConcurrentBitVectorIntSet implements MutableIntSet {

    protected final static int LOG_BITS_PER_UNIT = 5;

    protected final static int BITS_PER_UNIT = 32;

    protected final static int LOW_MASK = 0x1f;

    // Unsafe mechanics
    static final sun.misc.Unsafe UNSAFE;
    private static final long SPINEBASE;
    private static final int SPINESHIFT;
    private static final long SEGBASE;
    private static final int SEGSHIFT;

    static {
        int segs, spines;
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);

            Class segc = int[].class;
            SEGBASE = UNSAFE.arrayBaseOffset(segc);
            segs = UNSAFE.arrayIndexScale(segc);

            Class spinec = int[][].class;
            SPINEBASE = UNSAFE.arrayBaseOffset(spinec);
            spines = UNSAFE.arrayIndexScale(spinec);

        }
        catch (Exception e) {
            throw new Error(e);
        }
        if ((segs & (segs - 1)) != 0 || (spines & (spines - 1)) != 0) {
            throw new Error("data type scale not a power of two");
        }
        SEGSHIFT = 31 - Integer.numberOfLeadingZeros(segs);
        SPINESHIFT = 31 - Integer.numberOfLeadingZeros(spines);
    }

    /**
     * Convert bitIndex to a subscript into the spine array.
     */
    private static int spineSubscript(int bitIndex) {
        return (bitIndex >> LOG_BITS_PER_UNIT) / SEGMENT_SIZE;
    }

    /**
     * Convert bitIndex to a subscript into a segment (int[]).
     */
    private static int segmentSubscript(int bitIndex) {
        return (bitIndex >> LOG_BITS_PER_UNIT) % SEGMENT_SIZE;
    }

    /**
     * The spine is an array of segments, where each segment is an int array of length this.segmentSize.
     */
    private volatile int[][] spine;

    private final static int SEGMENT_SIZE = 64; // a segment holds SEGMENT_SIZE * BITS_PER_UNIT = 2048 elements

    public ConcurrentBitVectorIntSet() {
        this(1);
    }
    public ConcurrentBitVectorIntSet(int initialCapacity) {
        int spineSize = ((initialCapacity - 1) / SEGMENT_SIZE) + 1;
        if (spineSize < 16) {
            spineSize = 16;
        }
        int[][] newSpine = new int[spineSize][];
        newSpine[0] = new int[SEGMENT_SIZE];
        this.spine = newSpine;
    }

    @Override
    public boolean contains(int i) {
        int spineSub = spineSubscript(i);
        if (spineSub >= this.spine.length) {
            return false;
        }
        int[] segment = getSegment(spineSub);

        int ss = segmentSubscript(i);

        int shiftBits = i & LOW_MASK;
        return ((intAt(segment, ss) & (1 << shiftBits)) != 0);
    }

    /**
     * Get spine[spineSub], creating a segment if needed.
     *
     * @param spineSub
     * @return
     */
    private int[] getSegment(int spineSub) {
        long u = (spineSub << SPINESHIFT) + SPINEBASE;
        int[] seg = (int[]) UNSAFE.getObjectVolatile(this.spine, u);
        if (seg == null) {
            seg = new int[SEGMENT_SIZE];
            if (UNSAFE.compareAndSwapObject(this.spine, u, null, seg)) {
                // we successfully updated the value.
            }
            else {
                // someone else updated it.
                seg = (int[]) UNSAFE.getObjectVolatile(this.spine, u);
            }
        }

        return seg;

    }

    // access segment[ss], but with volitile semantics
    private static int intAt(int[] segment, int ss) {
        assert segment != null;
        long u = (ss << SEGSHIFT) + SEGBASE;
        return UNSAFE.getIntVolatile(segment, u);
    }

    @Override
    public boolean add(int i) {
        int spineSub = spineSubscript(i);
        if (spineSub >= this.spine.length) {
            extendSpine(spineSub);
        }
        int[] segment = getSegment(spineSub);

        int ss = segmentSubscript(i);

        int shiftBits = i & LOW_MASK;

        long u = (ss << SEGSHIFT) + SEGBASE;

        // now set bit 1 << shiftBits
        do {
            int existingVal = intAt(segment, ss);
            int newVal = existingVal | (1 << shiftBits);
            if (newVal == existingVal) {
                // someone else set it.
                return false;
            }
            if (UNSAFE.compareAndSwapInt(segment, u, existingVal, newVal)) {
                // we succesfully updated the value
                return true;
            }
            // we didn't get in there. Try again.
        } while (true);
    }


    private final Object spineLock = new Object();

    private void extendSpine(int spineSub) {
        // need to extend the spine to at least spineSub.
        // for the moment we will use a lock, since we think this will happen infrequently
        synchronized (this.spineLock) {
            if (this.spine.length <= spineSub) {
                int[][] oldSpine = this.spine;
                int[][] newSpine = new int[spineSub + 1][];
                assert oldSpine.length < newSpine.length;
                for (int i = 0; i < oldSpine.length; i++) {
                    // force the creation of the other segments to avoid races.
                    newSpine[i] = getSegment(i);
                }

                assert newSpine[spineSub] == null;
                newSpine[spineSub] = new int[SEGMENT_SIZE];

                this.spine = newSpine;
            }
        }
    }

    @Override
    public IntIterator intIterator() {
        return new MyIntIterator();
    }

    @Override
    public boolean addAll(IntSet set) {
        IntIterator iter = set.intIterator();
        boolean changed = false;
        while (iter.hasNext()) {
            changed |= this.add(iter.next());
        }
        return changed;
    }

    private class MyIntIterator implements IntIterator {
        int next = 0;
        boolean isNextValid = false;

        @Override
        public boolean hasNext() {
            if (isNextValid) {
                return true;
            }
            if (next < 0) {
                // definitely none left
                return false;
            }

            next = nextSetBit(next);
            return isNextValid = (next >= 0);
        }

        @Override
        public int next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            assert isNextValid;
            isNextValid = false;
            return next++;
        }

    }

    private int nextSetBit(int start) {
        if (start < 0) {
            throw new IllegalArgumentException("illegal start: " + start);
        }
        int spineSub = spineSubscript(start);
        while (spineSub < this.spine.length) {
            int[] segment = getSegment(spineSub);
            int segSub = segmentSubscript(start);
            while (segSub < SEGMENT_SIZE) {
                int bit = (1 << (start & LOW_MASK));
                int bw = intAt(segment, segSub);
                if (bw != 0) {
                    do {
                        if ((bw & bit) != 0) {
                            return start;
                        }
                        bit <<= 1;
                        start++;
                    } while (bit != 0);
                }
                else {
                    start += (BITS_PER_UNIT - (start & LOW_MASK));
                }

                segSub++;
                bit = 1;
            }
            // we rolled over into the next segment
            spineSub++;
            segSub = 0;
        }

        return -1;
    }

    @Override
    public int hashCode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAny(IntSet set) {
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

    @Override
    public boolean isEmpty() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void foreach(IntSetAction action) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void foreachExcluding(IntSet X, IntSetAction action) {
        throw new UnsupportedOperationException();
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
    public void copySet(IntSet set) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(int i) {
        throw new UnsupportedOperationException();
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

    public static void main(String[] args) {
        ConcurrentBitVectorIntSet s = new ConcurrentBitVectorIntSet();
        System.out.println("Running tests");

        for (int i = 0; i < 100000; i += 3) {
            if (!s.add(i)) {
                throw new RuntimeException();
            }
        }

        for (int i = 0; i < 100000; i += 3) {
            if (s.add(i)) {
                throw new RuntimeException();
            }
        }
        for (int i = 0; i < 100000; i += 3) {
            if (!s.contains(i)) {
                throw new RuntimeException();
            }
            if (s.contains(i + 1)) {
                throw new RuntimeException();
            }
        }
        IntIterator iter = s.intIterator();
        int count = 0;
        while (iter.hasNext()) {
            int i = iter.next();
            if (i != (count++) * 3) {
                throw new RuntimeException("expected");
            }
        }
        System.out.println("OK!");
    }

}

