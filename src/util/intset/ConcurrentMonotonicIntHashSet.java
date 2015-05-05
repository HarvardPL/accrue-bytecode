package util.intset;

import java.lang.reflect.Field;
import java.util.NoSuchElementException;
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
 * XXX DOCO TODO
 *
 */
public final class ConcurrentMonotonicIntHashSet implements MutableIntSet {
    private static final int INITIAL_BUCKET_SIZE = 8;
    private static final int DEFAULT_CONCURRENCY_LEVEL = 32;

    /**
     * The threshold length on any one bucket to trigger a rehashing. Must be less than 127.
     */
    private static final int THRESHOLD_BUCKET_LENGTH = 8;

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


    private static final class Segment {
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
        private final ReadLock bucketArrayReadLock;
        private final WriteLock bucketArrayWriteLock;

        public Segment(int initialCapacity) {
            this.buckets = new Entry[initialCapacity];
            ReentrantReadWriteLock bucketArrayLock = new ReentrantReadWriteLock(false);
            bucketArrayReadLock = bucketArrayLock.readLock();
            bucketArrayWriteLock = bucketArrayLock.writeLock();
        }


        public boolean containsKey(int i, int hash) {
            Entry[] b = this.buckets;
            Entry ref = getEntry(i, hash, b);
            return ref != null;
        }

        private static Entry getEntry(int i, int hash, Entry[] b) {
            Entry e = getBucketHead(b, bucketForHash(hash, b.length));
            while (e != null) {
                if (e.key == i) {
                    return e;
                }
                Entry enext = e.next;
                assert (enext == null) ? (e.length == 1) : (e.length == enext.length + 1) : "Entry lengths do not agree: "
                        + e.length + " and the next is " + (enext == null ? "null" : enext.length);
                e = enext;
            }
            return null;
        }

        /**
         * XXX
         *
         * @param i
         * @return
         */
        private static int bucketForHash(int hash, int numBuckets) {
            return (numBuckets - 1) & hash;
        }

        /**
         * Put the entry (key, val) into the map. If onlyIfAbsent is true, then the put will only occur if there is not
         * already a mapping for key.
         *
         * @param key
         * @param val
         * @param onlyIfAbsent
         * @param onlyIfPresent
         * @param oldValue
         * @return
         */
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

                    // there wasn't an entry

                    // We add a new Entry
                    // Check first to see if we want to resize
                    if (bucketHead != null && bucketHead.length >= THRESHOLD_BUCKET_LENGTH) {
                        // yes, we want to resize
                        if (resize(b, key, hash)) {
                            // we successfully resized and added the new entry
                            releasedReadLock = true;
                            return true;
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
         * Resize the buckets, and then add the key, value pair.
         *
         * Readlock must be held before calling. The read lock will be held at the end of the method if and only if it
         * returns false.
         */
        private boolean resize(Entry[] b, int key, int hash) {
            if (!this.resizeInProgress.compareAndSet(false, true)) {
                // someone else is resizing already
                return false;
            }
            // we want the write lock. Upgrade by first releasing the read lock
            this.bucketArrayReadLock.unlock();
            this.bucketArrayWriteLock.lock();
            try {
                Entry[] existingBuckets = this.buckets;

                if (b != existingBuckets) {
                    // someone already resized it from when we decided we needed to.
                    // Upgrade to a read lock, but getting the read lock.
                    this.bucketArrayReadLock.lock();
                    return false;
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
                int ind = bucketForHash(hash, newBucketLength);
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

        static Entry getBucketHead(Entry[] b, int i) {
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
                    do {
                        if (currentEntry != null) {
                            // we have a valid entry!
                            valid = true;
                            return true;
                        }
                        if (currentEntry != null) {
                            currentEntry = currentEntry.next;
                        }
                    } while (currentEntry != null);
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
    private final int segmentShift;

    public ConcurrentMonotonicIntHashSet() {
        this(INITIAL_BUCKET_SIZE, DEFAULT_CONCURRENCY_LEVEL);
    }

    public ConcurrentMonotonicIntHashSet(int concurrencyLevel) {
        this(INITIAL_BUCKET_SIZE, concurrencyLevel);
    }

    public ConcurrentMonotonicIntHashSet(int initialCapacity, int concurrencyLevel) {
        // Find the powers-of-two that is at least as big as concurrencyLevel and initialCapacity
        int sshift = 0;
        int ssize = 1;
        while (ssize < concurrencyLevel) {
            ++sshift;
            ssize <<= 1;
        }

        this.segments = new Segment[ssize];
        this.segmentMask = ssize - 1;
        this.segmentShift = 32 - sshift;

        int initCap = 1;
        while (initCap < initialCapacity) {
            initCap <<= 1;
        }

        this.segmentInitialCapacity = initCap;
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
     * Use a hash to figure out the segment to use for a key.
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
        Segment t = new Segment(this.segmentInitialCapacity);
        long offset = ((long) i << SEGSHIFT) + SEGBASE;
        do {
            if (UNSAFE.compareAndSwapObject(this.segments, offset, null, t)) {
                return t;
            }
        } while ((s = segmentAt(i)) == null);
        return s;
    }


    public static void main(String[] args) {
        ConcurrentMonotonicIntHashSet m = new ConcurrentMonotonicIntHashSet();
        int testSize = 10000;
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
        if (m.max() != testSize - 1) {
            throw new RuntimeException("max is " + m.max() + " test size is " + testSize);
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
