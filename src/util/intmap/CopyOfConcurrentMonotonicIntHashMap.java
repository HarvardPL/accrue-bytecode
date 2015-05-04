package util.intmap;

import java.lang.reflect.Field;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.ibm.wala.util.intset.IntIterator;

/**
 * XXX DOCO TODO
 */
public class CopyOfConcurrentMonotonicIntHashMap<V> implements ConcurrentIntMap<V> {
    private static final int INITIAL_BUCKET_SIZE = 16;
    private static final int DEFAULT_CONCURRENCY_LEVEL = 32;

    /**
     * The threshold length on any one bucket to trigger a rehashing.
     */
    private static final int THRESHOLD_BUCKET_LENGTH = 6;

    private static final class Entry<V> {
        Entry(int key, V value, Entry<V> next) {
            this.next = next;
            this.key = key;
            this.value = value;
            this.length = (byte) (next == null ? 1 : next.length + 1);
        }

        final int key;
        volatile Entry<V> next;
        V value;
        volatile byte length; // length of the list starting with this node.

        /**
         * Unsafe stuff...
         */
        static final sun.misc.Unsafe UNSAFE;
        static final long valueOffset;
        static final long nextOffset;
        static {
            try {
                Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                UNSAFE = (sun.misc.Unsafe) f.get(null);
                Class k = Entry.class;
                valueOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("value"));
                nextOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("next"));
            }
            catch (Exception e) {
                throw new Error(e);
            }
        }

        boolean compareAndSetValue(V oldValue, V newValue) {
            return UNSAFE.compareAndSwapObject(this, valueOffset, oldValue, newValue);
        }

        V getAndSetValue(V newValue) {
            V old;
            do {
                old = this.value;
            } while (!UNSAFE.compareAndSwapObject(this, valueOffset, old, newValue));
            return old;
        }

        void setNext(Entry newNext) {
            UNSAFE.putOrderedObject(this, nextOffset, newNext);
            this.length = (byte) (1 + (newNext == null ? 0 : newNext.length));
        }

    }


    private static class Segment<V> {
        private volatile Entry<V>[] buckets;
        /**
         * Number of deleted entries in the map (i.e., key-value pairs where the value is null). (May be approximate.)
         */
        private final AtomicInteger deletedEntries = new AtomicInteger(0);

        /**
         * Is a resize in progress? We treat this like a lock.
         */
        private final AtomicBoolean resizeInProgress = new AtomicBoolean(false);

        /**
         * A read/write lock for the bucket array. Putting a new Entry requires a read lock.
         * Resizing the Segment (i.e., replacing the bucket array) requires the write lock.
         */
        private final Lock bucketArrayReadLock;
        private final Lock bucketArrayWriteLock;

        public Segment(int initialCapacity) {
            this.buckets = new Entry[initialCapacity];
            ReadWriteLock bucketArrayLock = new ReentrantReadWriteLock(false);
            bucketArrayReadLock = bucketArrayLock.readLock();
            bucketArrayWriteLock = bucketArrayLock.writeLock();
        }


        public boolean containsKey(int i, int hash) {
            Entry<V> e = getEntry(i, hash);
            return e != null && e.value != null;
        }

        public V get(int i, int hash) {
            Entry<V> e = getEntry(i, hash);
            if (e != null) {
                return e.value;
            }
            return null;
        }

        private Entry<V> getEntry(int i, int hash) {
            Entry<V>[] b = this.buckets;
            Entry<V> e = getBucketHead(b, bucketForHash(hash, b.length));
            while (e != null) {
                if (e.key == i) {
                    return e;
                }
                e = e.next;
            }

            // hmmm, we didn't get it. Try a read lock in case there was a race with resizing...
            this.bucketArrayReadLock.lock();
            try {
                b = this.buckets;
                e = getBucketHead(b, bucketForHash(hash, b.length));
                while (e != null) {
                    if (e.key == i) {
                        return e;
                    }
                    e = e.next;
                }
            }
            finally {
                this.bucketArrayReadLock.unlock();
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

        public V put(int i, int hash, V val) {
            assert val != null;
            return put(i, hash, val, false);
        }

        public V putIfAbsent(int key, int hash, V value) {
            assert value != null;
            return put(key, hash, value, true);
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
        private V put(int key, int hash, V val, boolean onlyIfAbsent) {
            outer: while (true) {
                // check if we have an entry for i
                Entry<V> e = getEntry(key, hash);
                if (e != null) {
                    // we have an entry!
                    while (true) {
                        V existing = e.value;
                        if (onlyIfAbsent && existing != null) {
                            // we don't want to put, since there is already an entry.
                            return existing;
                        }
                        // update it and return.
                        if (e.compareAndSetValue(existing, val)) {
                            // we successfully swapped it
                            if (existing != null && val == null) {
                                // we effectively deleted it
                                this.deletedEntries.incrementAndGet();
                            }
                            else if (existing == null && val != null) {
                                // we effectively un-deleted it
                                this.deletedEntries.decrementAndGet();
                            }

                            return existing;
                        }
                        // we failed to update it, so try again.
                    }
                }

                // there wasn't an entry, so we must be putting a non-empty value.
                assert val != null;

                // We add a new Entry
                Entry<V>[] b = this.buckets;
                int ind = bucketForHash(hash, b.length);
                Entry<V> firstEntry = getBucketHead(b, ind);
                if (firstEntry != null && firstEntry.length >= THRESHOLD_BUCKET_LENGTH) {
                    // yes, we want to resize
                    if (resize(b, key, hash, val)) {
                        // we successfully resized and added the new entry
                        return null;
                    }
                    // we decided not to resize, and haven't added the entry. Fall through and do it now.
                }

                // We want to add an Entry without resizing.
                // get a read lock
                this.bucketArrayReadLock.lock();
                try {
                    Entry<V>[] currentBuckets = this.buckets;
                    if (currentBuckets != b) {
                        // doh! the buckets changed under us, just try again.
                        continue outer;
                    }
                    Entry<V> newE = new Entry<>(key, val, firstEntry);
                    if (compareAndSwapBucketHead(b, ind, firstEntry, newE)) {
                        // we swapped it! We are done.
                        // Return, which will unlock the read lock.
                        return null;
                    }
                    // we failed to add it to the head.
                    // Release the read lock and try the put again, by continuing at the outer loop.
                }
                finally {
                    this.bucketArrayReadLock.unlock();
                }
            }
        }

        public V remove(int key, int hash) {
            Entry<V> e = getEntry(key, hash);
            if (e == null) {
                return null;
            }
            V prev = e.getAndSetValue(null);
            if (prev != null) {
                this.deletedEntries.incrementAndGet();
            }
            return prev;
        }

        public boolean remove(int key, int hash, V value) {
            assert value != null;
            Entry<V> e = getEntry(key, hash);
            if (e == null) {
                return false;
            }
            if (e.compareAndSetValue(value, null)) {
                this.deletedEntries.incrementAndGet();
                return true;
            }
            return false;
        }

        public boolean replace(int key, int hash, V oldValue, V newValue) {
            assert newValue != null;
            Entry<V> e = getEntry(key, hash);
            if (e == null || e.value == null) {
                return false;
            }
            return e.compareAndSetValue(oldValue, newValue);
        }

        public V replace(int key, int hash, V value) {
            assert value != null;
            Entry<V> e = getEntry(key, hash);
            if (e == null || e.value == null) {
                return null;
            }
            return e.getAndSetValue(value);
        }

        /**
         * Resize the buckets, and then add the key, value pair.
         */
        private boolean resize(Entry<V>[] b, int key, int hash, V value) {
            if (!this.resizeInProgress.compareAndSet(false, true)) {
                // someone else is resizing already
                return false;
            }
            this.bucketArrayWriteLock.lock();
            try {
                Entry<V>[] existingBuckets = this.buckets;

                if (b != existingBuckets) {
                    // someone already resized it from when we decided we needed to.
                    return false;
                }
                int newBucketLength = existingBuckets.length * 2;
                Entry<V>[] newBuckets = new Entry[newBucketLength];
                for (int i = 0; i < existingBuckets.length; i++) {
                    Entry<V> e = getBucketHead(existingBuckets, i);
                    while (e != null) {
                        Entry<V> enext = e.next;
                        int ind = bucketForHash(hash(e.key), newBucketLength);
                        e.setNext(newBuckets[ind]);
                        newBuckets[ind] = e;
                        e = enext;
                    }
                }

                // now add the new entry
                int ind = bucketForHash(hash, newBucketLength);
                newBuckets[ind] = new Entry<>(key, value, newBuckets[ind]);

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
            Entry<V>[] bs = this.buckets;
            int deleted = this.deletedEntries.get();
            for (int i = 0; i < bs.length; i++) {
                Entry<V> e = getBucketHead(bs, i);
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
            return new SegmentKeyIterator<>(this.buckets);
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

        static <V> Entry<V> getBucketHead(CopyOfConcurrentMonotonicIntHashMap.Entry<V>[] b, int i) {
            return (Entry<V>) UNSAFE.getObjectVolatile(b, ((long) i << BUCKETSHIFT) + BUCKETBASE);
        }

        private boolean compareAndSwapBucketHead(Entry<V>[] b, int i, Entry<V> oldEntry, Entry<V> newEntry) {
            return UNSAFE.compareAndSwapObject(b, ((long) i << BUCKETSHIFT) + BUCKETBASE, oldEntry, newEntry);
        }

        /* ******************************************************************
         * Iterator
         *
         */
        private static class SegmentKeyIterator<V> implements IntIterator {
            final Entry<V>[] bs;
            int currentIndex = -1;
            CopyOfConcurrentMonotonicIntHashMap.Entry<?> currentEntry = null;
            boolean valid = false;

            public SegmentKeyIterator(Entry<V>[] buckets) {
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
                        if (currentEntry != null && currentEntry.value != null) {
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
                do {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    valid = false;
                    if (currentEntry.value != null) {
                        return currentEntry.key;
                    }
                    // whoops, we have a deleted entry. Try again.
                } while (true);
            }

        }

    }

    private final Segment<V>[] segments;

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

    public CopyOfConcurrentMonotonicIntHashMap() {
        this(INITIAL_BUCKET_SIZE, DEFAULT_CONCURRENCY_LEVEL);
    }

    public CopyOfConcurrentMonotonicIntHashMap(int concurrencyLevel) {
        this(INITIAL_BUCKET_SIZE, concurrencyLevel);
    }

    private CopyOfConcurrentMonotonicIntHashMap(int initialCapacity, int concurrencyLevel) {
        // Find the power-of-two that is at least as big as concurrencyLevel and initialCapacity
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
    public boolean containsKey(int i) {
        int hash = hash(i);
        Segment<V> seg = segmentAt(segmentForHash(hash));
        if (seg == null) {
            return false;
        }
        return seg.containsKey(i, hash);
    }

    @Override
    public V get(int i) {
        int hash = hash(i);
        Segment<V> seg = segmentAt(segmentForHash(hash));
        if (seg == null) {
            return null;
        }
        return seg.get(i, hash);
    }

    @Override
    public V put(int i, V val) {
        int hash = hash(i);
        Segment<V> seg = ensureSegmentAt(segmentForHash(hash));
        V res = seg.put(i, hash, val);
        checkMax(i);
        return res;
    }

    @Override
    public int size() {
        int count = 0;
        for (int i = 0; i < this.segments.length; i++) {
            Segment<V> seg = segmentAt(i);
            if (seg != null) {
                count += seg.size();
            }
        }
        return count;
    }

    @Override
    public IntIterator keyIterator() {
        return new KeyIterator();
    }

    @Override
    public V remove(int key) {
        int hash = hash(key);
        Segment<V> seg = segmentAt(segmentForHash(hash));
        if (seg == null) {
            return null;
        }
        return seg.remove(key, hash);
    }

    @Override
    public V putIfAbsent(int key, V value) {
        int hash = hash(key);
        Segment<V> seg = ensureSegmentAt(segmentForHash(hash));
        V res = seg.putIfAbsent(key, hash, value);
        if (res == null) {
            checkMax(key);
        }
        return res;
    }

    @Override
    public boolean remove(int key, V value) {
        int hash = hash(key);
        Segment<V> seg = segmentAt(segmentForHash(hash));
        if (seg == null) {
            return false;
        }
        return seg.remove(key, hash, value);
    }

    @Override
    public boolean replace(int key, V oldValue, V newValue) {
        int hash = hash(key);
        Segment<V> seg = segmentAt(segmentForHash(hash));
        if (seg == null) {
            return false;
        }
        return seg.replace(key, hash, oldValue, newValue);
    }

    @Override
    public V replace(int key, V value) {
        int hash = hash(key);
        Segment<V> seg = segmentAt(segmentForHash(hash));
        if (seg == null) {
            return null;
        }
        return seg.replace(key, hash, value);
    }

    private class KeyIterator implements IntIterator {
        int currSeg = 0;
        IntIterator currIter = null;

        @Override
        public boolean hasNext() {
            while (currSeg < segments.length && (currIter == null || !currIter.hasNext())) {
                Segment<V> s = segmentAt(currSeg++);
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

    private Segment<V> segmentAt(int i) {
        return (Segment<V>) UNSAFE.getObjectVolatile(this.segments, ((long) i << SEGSHIFT) + SEGBASE);
    }

    private Segment<V> ensureSegmentAt(int i) {
        Segment<V> s = segmentAt(i);
        if (s != null) {
            return s;
        }
        Segment<V> t = new Segment<>(this.segmentInitialCapacity);
        do {
            if (UNSAFE.compareAndSwapObject(this.segments, ((long) i << SEGSHIFT) + SEGBASE, null, t)) {
                return t;
            }
        } while ((s = segmentAt(i)) == null);
        return s;
    }

    public static void main(String[] args) {
        CopyOfConcurrentMonotonicIntHashMap<String> m = new CopyOfConcurrentMonotonicIntHashMap<>();
        int testSize = 100000;
        for (int i = 0; i < testSize; i++) {
            if (m.putIfAbsent(i, "val" + i) != null) {
                throw new RuntimeException("putIfAbsent");
            }
        }
        System.out.println("OK: A");

        if (m.size() != testSize) {
            throw new RuntimeException("size");
        }

        System.out.println("OK: B");

        if (!m.put(10, "val" + 10).equals("val10")) {
            throw new RuntimeException("put");
        }
        if (!m.remove(9).equals("val9")) {
            throw new RuntimeException("remove");
        }
        if (m.containsKey(9)) {
            throw new RuntimeException("containsKey");
        }
        if (m.get(9) != null) {
            throw new RuntimeException("get");
        }
        if (m.remove(9) != null) {
            throw new RuntimeException("remove");
        }

        if (!m.putIfAbsent(11, "val11").equals("val11")) {
            throw new RuntimeException("putIfAbsent");
        }
        if (m.size() != testSize - 1) {
            throw new RuntimeException("size");
        }
        IntIterator iter = m.keyIterator();
        int count = 0;
        System.out.println("OK: C");

        while (iter.hasNext()) {
            int i = iter.next();
            //System.out.println(i);
            if (!m.get(i).equals("val" + i)) {
                throw new RuntimeException("get");
            }
            if (!m.containsKey(i)) {
                throw new RuntimeException("get");
            }
            count++;
            //            if (count % 1000 == 0) {
            //                //System.out.println(iter.next());
            //            }
        }
        System.out.println("OK: D");
        if (m.containsKey(testSize + 5)) {
            throw new RuntimeException("containsKey");
        }
        if (m.get(testSize + 5) != null) {
            throw new RuntimeException("get");
        }
        if (m.remove(testSize + 5) != null) {
            throw new RuntimeException("remove");
        }

        if (count != testSize - 1) {
            throw new RuntimeException("count is " + count);
        }
        if (m.max() != testSize - 1) {
            throw new RuntimeException("max is " + m.max());
        }
        System.out.println("OK: " + m.max());
    }

}
