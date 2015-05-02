package util.intset;

import java.lang.reflect.Field;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * XXX DOCO TODO
 *
 */
public class ConcurrentMonotonicIntHashSet implements MutableIntSet {
    private static final int INITIAL_BUCKET_SIZE = 16;
    private static final int DEFAULT_CONCURRENCY_LEVEL = 32;

    /**
     * The threshold length on any one bucket to trigger a rehashing.
     */
    private static final int THRESHOLD_BUCKET_LENGTH = 6;

    private static class Entry {
        Entry(int key, Entry next) {
            this.key = key;
            this.next = next;
            this.length = next == null ? 1 : next.length + 1;
        }

        final Entry next;
        final int key; // also the hash
        final int length; // length of the list starting with this node.
    }


    private static class Segment {
        private volatile Entry[] buckets;
        /**
         * Number of deleted entries in the map (i.e., key-value pairs where the value is null). (May be approximate.)
         */
        private final AtomicInteger deletedEntries = new AtomicInteger(0);

        /**
         * Is a resize in progress? We treat this like a lock.
         */
        private final AtomicBoolean resizeInProgress = new AtomicBoolean(false);

        /**
         * A read/write lock for the bucket array. Putting a new Entry requires a read lock. Reizing the Segment (i.e.,
         * replacing the bucket array) requires the write lock.
         */
        private final Lock bucketArrayReadLock;
        private final Lock bucketArrayWriteLock;

        public Segment(int initialCapacity) {
            this.buckets = new Entry[initialCapacity];
            ReadWriteLock bucketArrayLock = new ReentrantReadWriteLock(false);
            bucketArrayReadLock = bucketArrayLock.readLock();
            bucketArrayWriteLock = bucketArrayLock.writeLock();
        }


        public boolean contains(int i) {
            Entry[] b = this.buckets;
            Entry e = getBucketHead(b, bucketForKey(i, b.length));
            while (e != null) {
                if (e.key == i) {
                    return true;
                }
                e = e.next;
            }
            return false;
        }

        /**
         * XXX
         *
         * @param i
         * @return
         */
        private static int bucketForKey(int i, int numBuckets) {
            return (numBuckets - 1) & bucketHash(i);
        }

        private static int bucketHash(int i) {
            int h = i;
            h ^= (h >>> 20) ^ (h >>> 12);
            h ^= (h >>> 7) ^ (h >>> 4);
            return h;
        }

        public boolean add(int key) {
            outer: while (true) {
                Entry[] b = this.buckets;

                // check if we have an entry for i
                int ind = bucketForKey(key, b.length);
                Entry e = getBucketHead(b, ind);
                Entry firstEntry = e;
                while (e != null) {
                    if (e.key == key) {
                        // we have an entry!
                        return false;
                    }
                    e = e.next;
                }

                // there wasn't an entry, so we must be putting a non-empty value.

                // We add a new Entry
                // Check first to see if we want to resize
                if (firstEntry != null && firstEntry.length >= THRESHOLD_BUCKET_LENGTH) {
                    // yes, we want to resize
                    if (resize(b, key)) {
                        // we successfully resized and added the new entry
                        return true;
                    }
                    // we decided not to resize, and haven't added the entry. Fall through and do it now.
                }

                // We want to add an Entry without resizing.
                // get a read lock
                this.bucketArrayReadLock.lock();
                try {
                    Entry[] currentBuckets = this.buckets;
                    if (currentBuckets != b) {
                        // doh! the buckets changed under us, just try again.
                        continue outer;
                    }
                    Entry newE = new Entry(key, firstEntry);
                    if (compareAndSwapBucketHead(b, ind, firstEntry, newE)) {
                        // we swapped it! We are done.
                        // Return, which will unlock the read lock.
                        return true;
                    }
                    // we failed to add it to the head.
                    // Release the read lock and try the put again, by continuing at the outer loop.
                }
                finally {
                    this.bucketArrayReadLock.unlock();
                }
            }
        }


        /**
         * Resize the buckets, and then add the key.
         */
        private boolean resize(Entry[] b, int key) {
            if (!this.resizeInProgress.compareAndSet(false, true)) {
                // someone else is resizing already
                return false;
            }
            this.bucketArrayWriteLock.lock();
            try {
                Entry[] existingBuckets = this.buckets;

                if (b != existingBuckets) {
                    // someone already resized it from when we decided we needed to.
                    return false;
                }
                int newBucketLength = existingBuckets.length * 2;
                Entry[] newBuckets = new Entry[newBucketLength];
                for (int i = 0; i < existingBuckets.length; i++) {
                    Entry e = getBucketHead(existingBuckets, i);
                    while (e != null) {
                        int ekey = e.key;
                        int ind = bucketForKey(ekey, newBucketLength);
                        newBuckets[ind] = new Entry(ekey, newBuckets[ind]);
                        e = e.next;
                    }
                }

                // now add the new entry
                int ind = bucketForKey(key, newBucketLength);
                newBuckets[ind] = new Entry(key, newBuckets[ind]);

                // now update the buckets
                this.buckets = newBuckets;

                return true;
            }
            finally {
                // unlock
                this.bucketArrayWriteLock.unlock();
                this.resizeInProgress.set(false);
            }
        }

        public int size() {
            int count = 0;
            Entry[] bs = this.buckets;
            int deleted = this.deletedEntries.get();
            for (int i = 0; i < bs.length; i++) {
                Entry e = getBucketHead(bs, i);
                if (e != null) {
                    count += e.length;
                }
            }
            count -= deleted;
            if (count < 0) {
                return 0;
            }
            return count;
        }

        public IntIterator keyIterator() {
            return new SegmentKeyIterator(this.buckets);
        }

        /* ******************************************************************
         * Unsafe methods
         */
        private static final sun.misc.Unsafe UNSAFE;
        private static final long BUCKETBASE;
        private static final int BUCKETSHIFT;

        static {
            int bs;
            try {
                Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                UNSAFE = (sun.misc.Unsafe) f.get(null);
                Class bc = Entry[].class;
                BUCKETBASE = UNSAFE.arrayBaseOffset(bc);
                bs = UNSAFE.arrayIndexScale(bc);
            }
            catch (Exception e) {
                throw new Error(e);
            }
            if ((bs & (bs - 1)) != 0) {
                throw new Error("data type scale not a power of two");
            }
            BUCKETSHIFT = 31 - Integer.numberOfLeadingZeros(bs);
        }

        static Entry getBucketHead(ConcurrentMonotonicIntHashSet.Entry[] b, int i) {
            return (Entry) UNSAFE.getObjectVolatile(b, ((long) i << BUCKETSHIFT) + BUCKETBASE);
        }

        private boolean compareAndSwapBucketHead(Entry[] b, int i, Entry oldEntry, Entry newEntry) {
            return UNSAFE.compareAndSwapObject(b, ((long) i << BUCKETSHIFT) + BUCKETBASE, oldEntry, newEntry);
        }

        /* ******************************************************************
         * Iterator
         *
         */
        private static class SegmentKeyIterator implements IntIterator {
            final Entry[] bs;
            int currentIndex = -1;
            ConcurrentMonotonicIntHashSet.Entry currentEntry = null;
            boolean valid = false;

            public SegmentKeyIterator(Entry[] buckets) {
                this.bs = buckets;
            }

            @Override
            public boolean hasNext() {
                if (valid) {
                    return true;
                }
                // currentEntry is not valid

                // advance to the next entry, if any
                if (currentEntry != null) {
                    currentEntry = currentEntry.next;
                }

                // find the next Entry
                while (currentIndex < bs.length) {
                    if (currentEntry != null) {
                        // we have a valid entry!
                        valid = true;
                        return true;
                    }

                    // here currentEntry is null
                    currentIndex++;
                    if (currentIndex < this.bs.length) {
                        currentEntry = getBucketHead(this.bs, currentIndex);
                    }
                }
                return false;
            }

            @Override
            public int next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                valid = false;
                return currentEntry.key;
            }

        }

    }

    private final Segment[] segments;

    /**
     * Best guess at the max key.
     */
    private final AtomicInteger max = new AtomicInteger(-1);

    /**
     * Initial capacity for segments when they are created.
     */
    private final int segmentInitialCapacity;

    private final int segmentMask;

    public ConcurrentMonotonicIntHashSet() {
        this(INITIAL_BUCKET_SIZE, DEFAULT_CONCURRENCY_LEVEL);
    }

    public ConcurrentMonotonicIntHashSet(int concurrencyLevel) {
        this(INITIAL_BUCKET_SIZE, concurrencyLevel);
    }

    private ConcurrentMonotonicIntHashSet(int initialCapacity, int concurrencyLevel) {
        // Find power-of-two sizes best matching arguments
        int ssize = 1;
        while (ssize <= concurrencyLevel) {
            ssize <<= 1;
        }

        this.segments = new Segment[ssize];
        this.segmentInitialCapacity = initialCapacity;
        this.segmentMask = ssize - 1;
    }


    @Override
    public boolean isEmpty() {
        return size() == 0;
    }


    private void checkMax(int key) {
        int lastReturned;
        do {
            lastReturned = this.max.get();
            if (lastReturned >= key) {
                return;
            }
            // we need to set the new max
        } while (!this.max.compareAndSet(lastReturned, key));
    }

    @Override
    public int max() {
        return this.max.get();
    }

    @Override
    public boolean contains(int i) {
        Segment seg = segmentForKey(i);
        if (seg == null) {
            return false;
        }
        return seg.contains(i);
    }

    @Override
    public boolean add(int i) {
        Segment seg = ensureSegmentForKey(i);
        return seg.add(i);
    }

    @Override
    public int size() {
        int count = 0;
        for (int i = 0; i < this.segments.length; i++) {
            Segment seg = segmentAt(i);
            if (seg != null) {
                count += seg.size();
            }
        }
        return count;
    }

    @Override
    public IntIterator intIterator() {
        return new KeyIterator();
    }

    private class KeyIterator implements IntIterator {
        int currSeg = 0;
        IntIterator currIter = null;

        @Override
        public boolean hasNext() {
            while (currSeg < segments.length && (currIter == null || !currIter.hasNext())) {
                Segment s = segmentAt(currSeg++);
                currIter = (s == null ? null : s.keyIterator());
            }
            if (currIter != null && currIter.hasNext()) {
                return true;
            }
            return false;
        }

        @Override
        public int next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return currIter.next();
        }

    }

    /* ******************************************************************
     * Unsafe methods
     */
    private static final sun.misc.Unsafe UNSAFE;
    private static final long SEGBASE;
    private static final int SEGSHIFT;

    static {
        int ss;
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
            Class sc = Segment[].class;
            SEGBASE = UNSAFE.arrayBaseOffset(sc);
            ss = UNSAFE.arrayIndexScale(sc);
        }
        catch (Exception e) {
            throw new Error(e);
        }
        if ((ss & (ss - 1)) != 0) {
            throw new Error("data type scale not a power of two");
        }
        SEGSHIFT = 31 - Integer.numberOfLeadingZeros(ss);
    }

    private Segment segmentForKey(int key) {
        int hash = segmentHash(key);

        return segmentAt(hash & segmentMask);
    }

    private Segment ensureSegmentForKey(int key) {
        int hash = segmentHash(key);

        return ensureSegmentAt(hash & segmentMask);
    }

    private static int segmentHash(int key) {
        int h = key;
        // Spread bits to regularize both segment and index locations,
        // using variant of single-word Wang/Jenkins hash.
        h += (h << 15) ^ 0xffffcd7d;
        h ^= (h >>> 10);
        h += (h << 3);
        h ^= (h >>> 6);
        h += (h << 2) + (h << 14);
        return h ^ (h >>> 16);
    }

    private Segment segmentAt(int i) {
        return (Segment) UNSAFE.getObjectVolatile(this.segments, ((long) i << SEGSHIFT) + SEGBASE);
    }

    private Segment ensureSegmentAt(int i) {
        Segment s = segmentAt(i);
        if (s != null) {
            return s;
        }
        Segment t = new Segment(this.segmentInitialCapacity);
        do {
            if (UNSAFE.compareAndSwapObject(this.segments, ((long) i << SEGSHIFT) + SEGBASE, null, t)) {
                return t;
            }
        } while ((s = segmentAt(i)) == null);
        return s;
    }
    public static void main(String[] args) {
        ConcurrentMonotonicIntHashSet m = new ConcurrentMonotonicIntHashSet();
        int testSize = 100000;
        for (int i = 0; i < testSize; i++) {
            if (m.add(i) == false) {
                throw new RuntimeException("add");
            }
        }
        System.out.println("OK: A");

        if (m.size() != testSize) {
            throw new RuntimeException("size");
        }

        System.out.println("OK: B");

        if (m.add(10)) {
            throw new RuntimeException("add");
        }
        if (m.size() != testSize) {
            throw new RuntimeException("size");
        }
        IntIterator iter = m.intIterator();
        int count = 0;
        System.out.println("OK: C");

        while (iter.hasNext()) {
            int i = iter.next();
            //System.out.println(i);
            if (!m.contains(i)) {
                throw new RuntimeException("contains");
            }
            count++;
        }
        System.out.println("OK: D");
        if (m.contains(testSize + 5)) {
            throw new RuntimeException("contains");
        }

        if (count != testSize) {
            throw new RuntimeException("count is " + count);
        }
        if (m.max() != testSize) {
            throw new RuntimeException("max is " + m.max());
        }
        System.out.println("OK: " + m.max());
    }

    @Override
    public boolean containsAny(IntSet set) {
        IntIterator iter = set.intIterator();
        while (iter.hasNext()) {
            if (this.contains(iter.next())) {
                return true;
            }
        }
        return false;
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
    public void foreach(IntSetAction action) {
        IntIterator iter = this.intIterator();
        while (iter.hasNext()) {
            action.act(iter.next());
        }
    }

    @Override
    public void foreachExcluding(IntSet X, IntSetAction action) {
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
    public boolean addAll(IntSet set) {
        boolean changed = false;
        IntIterator iter = set.intIterator();
        while (iter.hasNext()) {
            if (this.add(iter.next())) {
                changed = true;
            }
        }
        return changed;
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

    @Override
    public boolean remove(int i) {
        throw new UnsupportedOperationException();
    }

}
