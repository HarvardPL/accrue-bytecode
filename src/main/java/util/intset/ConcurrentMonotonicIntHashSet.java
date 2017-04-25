package util.intset;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * A concurrent int set. This set does not support removal of elements. It is a copy of the
 * ConcurrentMonotonicIntHashMap, but without keys. Look at the documentation of that class for an overview of the
 * design.
 *
 */
public final class ConcurrentMonotonicIntHashSet implements MutableIntSet {
    /**
     * Default initial capacity of the hash map. This is just used to set the initial number of buckets in the segments.
     */
    private static final int DEFAULT_INITIAL_CAPACITY = 16;

    /**
     * Default concurrency level. This is essentially the number of Segments.
     */
    private static final int DEFAULT_CONCURRENCY_LEVEL = 32;

    /**
     * The threshold length on any one bucket to trigger a resizing. Must be less than 127.
     */
    private static final int THRESHOLD_BUCKET_LENGTH = 6;

    /**
     * An Entry is a node in a bucket's linked list. All fields are final.
     *
     * @param
     */
    private static final class Entry {
        Entry(int key, Entry next) {
            this.next = next;
            this.key = key;
            this.length = (byte) (next == null ? 1 : next.length + 1);
        }

        final int key;
        final Entry next;
        final byte length; // length of the list starting with this node.
    }

    /**
     * A Segment is a thread safe hash table. A ConcurrentMonotonicIntHashSet has a fixed number of Segments, based on
     * the concurrency of the hash map.
     *
     * @param
     */
    private static final class Segment {
        /**
         * The buckets are simply linked lists. The read lock is needed to add an Entry to a bucket, or to modify an
         * Entry that is already in a bucket. The write lock is needed to replace the buckets array, i.e., to change the
         * number of buckets.
         *
         * The number of buckets should always be a power of two, since an optimization of the resize method depends on
         * this.
         */
        private volatile Entry[] buckets;

        /**
         * Is a resize in progress? We use this to ensure that at most one thread at a time is waiting to acquire the
         * write lock to perform a resize.
         */
        private final AtomicBoolean resizeInProgress = new AtomicBoolean(false);

        /*
         * Read/writes lock for the bucket array. Putting a new Entry into a bucket, or
         * modifying an Entry already in a bucket requires a read lock. Resizing the Segment (i.e.,
         * replacing the bucket array) requires the write lock.
         */
        private final ReadLock bucketArrayReadLock;
        private final WriteLock bucketArrayWriteLock;

        public Segment(int initialSize) {
            this.buckets = new Entry[initialSize];
            ReentrantReadWriteLock bucketArrayLock = new ReentrantReadWriteLock(false);
            bucketArrayReadLock = bucketArrayLock.readLock();
            bucketArrayWriteLock = bucketArrayLock.writeLock();
            assert (initialSize > 0 && ((initialSize & (initialSize - 1)) == 0)) : "initial size is not a power of two.";
        }

        public boolean containsKey(int i, int hash) {
            Entry[] b = this.buckets;
            Entry ref = getEntry(i, hash, b);
            return ref != null;
        }

        /**
         * Get the entry from the bucket array.
         *
         * @param i
         * @param hash
         * @param b
         * @return
         */
        private static Entry getEntry(int i, int hash, Entry[] b) {
            Entry e = getBucketHead(b, bucketForHash(hash, b.length));
            while (e != null) {
                if (e.key == i) {
                    return e;
                }

                e = e.next;
            }
            return null;
        }

        /**
         * For the given hash of a key, what is the corresponding bucket to use? We require that numBuckets is a power
         * of two, say 2^(n+1), and simply use the lesat n bits to determine the bucket.
         */
        private static int bucketForHash(int hash, int numBuckets) {
            return (numBuckets - 1) & hash;
        }

        public boolean add(int key, int hash) {
            outer: while (true) {
                // We need a read lock here, as we will be either modifying an Entry
                // or adding a new Entry
                boolean releasedReadLock = false;
                this.bucketArrayReadLock.lock();

                try {
                    Entry[] b = this.buckets;
                    // check if we have an entry for i
                    int ind = bucketForHash(hash, b.length);
                    Entry bucketHead = getBucketHead(b, ind);
                    Entry e = bucketHead;
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
                    if (bucketHead != null && bucketHead.length >= THRESHOLD_BUCKET_LENGTH) {
                        // yes, we want to resize
                        ResizeResult rr = resize(b, key, hash);

                        if (rr != ResizeResult.NO_RESIZE) {
                            // we successfully resized
                            releasedReadLock = true;
                            // return value should indicate whether we added or not.
                            return rr == ResizeResult.RESIZED_ADDED;
                        }
                        // we decided not to resize, and haven't added the entry.
                        // The read lock is still held.
                        // Make sure the buckets haven't changed from under us,
                        // then fall through and do the add.
                        Entry[] currentBuckets = this.buckets;
                        if (currentBuckets != b) {
                            // doh! the buckets changed under us, just try again.
                            continue outer;
                        }
                    }

                    // We want to add an Entry without resizing.
                    Entry newE = new Entry(key, bucketHead);
                    if (compareAndSwapBucketHead(b, ind, bucketHead, newE)) {
                        // we swapped it! We are done.
                        // Return, which will unlock the read lock.
                        return true;
                    }
                    // we failed to add it to the head.
                    // Release the read lock and try the put again, by continuing at the outer loop.
                }
                finally {
                    if (!releasedReadLock) {
                        this.bucketArrayReadLock.unlock();
                    }
                }
            }
        }


        /**
         * Enum to help communicate the result of resize.
         */
        private static enum ResizeResult {
            NO_RESIZE, RESIZED_ADDED, RESIZED_ALREADY_EXISTED
        }

        /**
         * Resize the buckets, and then add the key.
         *
         * Readlock must be held before calling. The read lock will be held at the end of the method if and only if it
         * returns NO_RESIZE.
         */
        private ResizeResult resize(Entry[] b, int key, int hash) {
            if (!this.resizeInProgress.compareAndSet(false, true)) {
                // someone else is resizing already
                return ResizeResult.NO_RESIZE;
            }
            // we want the write lock. Upgrade by first releasing the read lock
            this.bucketArrayReadLock.unlock();
            this.bucketArrayWriteLock.lock();
            try {
                Entry[] existingBuckets = this.buckets;
                boolean alreadyContainsNewElem = false;

                if (b != existingBuckets) {
                    // someone already resized it from when we decided we needed to.
                    // Upgrade to a read lock, but getting the read lock.
                    this.bucketArrayReadLock.lock();
                    return ResizeResult.NO_RESIZE;
                }
                int newBucketLength = existingBuckets.length * 2;
                Entry[] newBuckets = new Entry[newBucketLength];
                for (int i = 0; i < existingBuckets.length; i++) {
                    Entry bhead = getBucketHead(existingBuckets, i);
                    // First find the longest tail of the bucket list that we can reuse, i.e., that map to the same bucket.
                    // We can do this because the bucket size increases by a factor of 2, and so each old bucket is split into two new buckets,
                    // and for each new bucket, the entries come from the same old bucket.
                    // This will reduce the number of Entrys that we need to create.
                    Entry startOfLastChain = null;
                    int lastBucket = -1;
                    {
                        Entry e = bhead;
                        while (e != null) {
                            int ind = bucketForHash(hash(e.key), newBucketLength);

                            if (ind != lastBucket) {
                                // this is the start of a new chain!
                                lastBucket = ind;
                                startOfLastChain = e;
                            }
                            else {
                                // we are still part of the same chain.
                            }

                            alreadyContainsNewElem = alreadyContainsNewElem || (e.key == key);
                            e = e.next;
                        }
                    }

                    // we will reuse startOfLastChain.
                    if (lastBucket >= 0) {
                        newBuckets[lastBucket] = startOfLastChain;
                    }

                    Entry e = bhead;

                    while (e != null && e != startOfLastChain) {
                        int ekey = e.key;
                        int ind = bucketForHash(hash(ekey), newBucketLength);
                        newBuckets[ind] = new Entry(ekey, newBuckets[ind]);
                        e = e.next;
                    }
                }

                // now add the new entry
                if (!alreadyContainsNewElem) {
                    int ind = bucketForHash(hash, newBucketLength);
                    newBuckets[ind] = new Entry(key, newBuckets[ind]);
                }

                // now update the buckets
                this.buckets = newBuckets;

                return alreadyContainsNewElem ? ResizeResult.RESIZED_ALREADY_EXISTED : ResizeResult.RESIZED_ADDED;
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
            for (int i = 0; i < bs.length; i++) {
                Entry head = getBucketHead(bs, i);
                if (head != null) {
                    count += head.length;
                }
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

        static Entry getBucketHead(Entry[] b, int i) {
            return (Entry) UNSAFE.getObjectVolatile(b, ((long) i << BUCKETSHIFT) + BUCKETBASE);
        }

        private boolean compareAndSwapBucketHead(Entry[] b, int i, Entry oldEntry, Entry newEntry) {
            return UNSAFE.compareAndSwapObject(b, ((long) i << BUCKETSHIFT) + BUCKETBASE, oldEntry, newEntry);
        }

        /* ******************************************************************
         * Iterator to go through keys in the segment. Contains a reference to the bucket array,
         * so the iterator will work even if the Segment is resized.
         *
         */
        private static class SegmentKeyIterator implements IntIterator {
            final Entry[] bs;
            int currentIndex = -1;
            Entry currentEntry = null;
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

    /**
     * The Segments for this HashSet.
     */
    private final Segment[] segments;

    /**
     * The max key.
     */
    private final AtomicInteger max = new AtomicInteger(-1);

    /**
     * Initial size for segments when they are created.
     */
    private final int initialSegmentSize;

    /*
     * Used to compute the segment for a given hash.
     */
    private final int segmentMask;
    private final int segmentShift;

    public ConcurrentMonotonicIntHashSet() {
        this(DEFAULT_INITIAL_CAPACITY, DEFAULT_CONCURRENCY_LEVEL);
    }

    public ConcurrentMonotonicIntHashSet(int concurrencyLevel) {
        this(DEFAULT_INITIAL_CAPACITY, concurrencyLevel);
    }

    public ConcurrentMonotonicIntHashSet(int initialCapacity, int concurrencyLevel) {
        // Find the powers-of-two that is at least as big as concurrencyLevel and initialCapacity
        int segShift = 0;
        int segSize = 1;
        while (segSize < concurrencyLevel) {
            segShift++;
            segSize <<= 1;
        }

        this.segments = new Segment[segSize];
        this.segmentMask = segSize - 1;
        this.segmentShift = 32 - segShift;

        int initCap = 1;
        while (initCap < initialCapacity) {
            initCap <<= 1;
        }

        int iss = initCap > segSize ? (initCap / segSize) : 1;
        assert iss >= 1;
        this.initialSegmentSize = iss;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * We just added key, update max if needed.
     *
     * @param key
     */
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
        int hash = hash(i);
        Segment seg = segmentAt(segmentForHash(hash));
        if (seg == null) {
            return false;
        }
        return seg.containsKey(i, hash);
    }

    @Override
    public boolean add(int i) {
        int hash = hash(i);
        Segment seg = ensureSegmentAt(segmentForHash(hash));
        boolean res = seg.add(i, hash);
        if (res) {
            checkMax(i);
        }
        return res;
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

    /**
     * Has for a key, needs to spread out the bits since this hash is used both to determine which segment to use, and
     * within a segment, which bucket to use. The segment is determined by the most significant bits of the hash, and
     * the bucket is determined by the least significant bits of the hash.
     *
     * @param key
     * @return
     */
    private static int hash(int key) {
        // A single word Jenkins hash, taken from https://en.wikipedia.org/wiki/Jenkins_hash_function
        int hash = key;
        hash += (hash << 10);
        hash ^= (hash >> 6);
        hash += (hash << 3);
        hash ^= (hash >> 11);
        hash += (hash << 15);

        return hash;
    }

    private int segmentForHash(int hash) {
        return (hash >>> segmentShift) & segmentMask;
    }

    private Segment segmentAt(int i) {
        return (Segment) UNSAFE.getObjectVolatile(this.segments, ((long) i << SEGSHIFT) + SEGBASE);
    }

    private Segment ensureSegmentAt(int i) {
        Segment s = segmentAt(i);
        if (s != null) {
            return s;
        }
        Segment t = new Segment(this.initialSegmentSize);
        long offset = ((long) i << SEGSHIFT) + SEGBASE;
        do {
            if (UNSAFE.compareAndSwapObject(this.segments, offset, null, t)) {
                return t;
            }
        } while ((s = segmentAt(i)) == null);
        return s;
    }

    /*
     * Some simple test code.
     */
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
            throw new RuntimeException("size " + m.size() + " " + testSize);
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

        Set<Integer> mirror = new HashSet<Integer>();

        while (iter.hasNext()) {
            int i = iter.next();
            if (!mirror.add(i)) {
                throw new RuntimeException("duplicates: " + i);
            }
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
        if (m.max() != testSize - 1) {
            throw new RuntimeException("max is " + m.max() + " test size is " + testSize);
        }
        System.out.println("OK: " + m.max());
    }

    /*
     * Simple and unsupported methods.
     */

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

    /**
     * Non-thread-safe method that pretty prints the data structure
     *
     * @param s
     */
    public void dump(PrintStream str) {
        for (int i = 0; i < this.segments.length; i++) {
            Segment s = this.segments[i];
            if (s == null) {
                continue;
            }
            String pre = "Seg " + i + ": ";
            for (int j = 0; j < s.buckets.length; j++) {
                Entry e = s.buckets[j];
                if (e == null) {
                    continue;
                }
                // the bucket is non null
                str.print(pre);
                pre = "       ";
                str.print("b" + j + ": ");
                while (e != null) {
                    str.print(e.key);
                    e = e.next;
                    if (e != null) {
                        str.print(" -> ");
                    }
                }
                str.println();
            }
        }
    }
}
