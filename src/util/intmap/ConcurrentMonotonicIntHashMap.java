package util.intmap;

import java.lang.reflect.Field;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.ibm.wala.util.intset.IntIterator;

/**
 * XXX DOCO TODO
 */
public class ConcurrentMonotonicIntHashMap<V> implements ConcurrentIntMap<V> {
    private static final int INITIAL_BUCKET_SIZE = 16;

    /**
     * The threshold length on any one bucket to trigger a rehashing.
     */
    private static final int THRESHOLD_BUCKET_LENGTH = 8;

    private static class Entry<V> {
        Entry(int key, AtomicReference<V> valueRef, Entry<V> next) {
            this.next = next;
            this.key = key;
            this.valueRef = valueRef;
            this.length = next == null ? 1 : next.length + 1;
        }
        final Entry<V> next;
        final int key; // also the hash
        final AtomicReference<V> valueRef;
        final int length; // length of the list starting with this node.

        //        /* ******************************************************************
        //         * Unsafe operations
        //         *
        //         */
        //        /**
        //         * Sets next field with volatile write semantics. (See above about use of putOrderedObject.)
        //         */
        //        final boolean compareAndSetValue(V oldValue, V newValue) {
        //            return UNSAFE.compareAndSwapObject(this, valueOffset, oldValue, newValue);
        //        }
        //
        //        // Unsafe mechanics
        //        static final sun.misc.Unsafe UNSAFE;
        //        static final long valueOffset;
        //        static {
        //            try {
        //                Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        //                f.setAccessible(true);
        //                UNSAFE = (sun.misc.Unsafe) f.get(null);
        //                Class k = Entry.class;
        //                valueOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("value"));
        //            }
        //            catch (Exception e) {
        //                throw new Error(e);
        //            }
        //        }

    }


    private volatile Entry<V>[] buckets;

    /**
     * Best guess at the max key.
     */
    private final AtomicInteger max = new AtomicInteger(-1);

    /**
     * Number of deleted entries in the map (i.e., key-value pairs where the value is null). (May be approximate.)
     */
    private final AtomicInteger deletedEntries = new AtomicInteger(0);

    /**
     * Is a resize in progress? We treat this like a lock.
     */
    private final AtomicBoolean resizeInProgress = new AtomicBoolean(false);

    /**
     * An object used to let threads wait until the resizing is finished.
     */
    private final Object resizingNotification = new Object();

    private final ReadWriteLock bucketUpdateLock = new ReentrantReadWriteLock(false);

    public ConcurrentMonotonicIntHashMap() {
        this(INITIAL_BUCKET_SIZE);
    }

    private ConcurrentMonotonicIntHashMap(int initialCapacity) {
        this.buckets = new Entry[initialCapacity];
    }

    @Override
    public boolean containsKey(int i) {
        Entry<V>[] b = this.buckets;
        AtomicReference<V> ref = getRef(i, b);
        return ref != null && ref.get() != null;
    }

    @Override
    public V get(int i) {
        Entry<V>[] b = this.buckets;
        AtomicReference<V> ref = getRef(i, b);
        if (ref != null) {
            return ref.get();
        }
        return null;
    }

    private static <V> AtomicReference<V> getRef(int i, Entry<V>[] b) {
        Entry<V> e = getBucketHead(b, bucketForKey(i, b.length));
        while (e != null) {
            if (e.key == i) {
                return e.valueRef;
            }
            e = e.next;
        }
        return null;
    }

    /**
     * XXX
     *
     * @param i
     * @return
     */
    private static int bucketForKey(int i, int numBuckets) {
        int h = i;
        h ^= (h >>> 20) ^ (h >>> 12);
        h ^= (h >>> 7) ^ (h >>> 4);
        return h % numBuckets;
    }

    @Override
    public V put(int i, V val) {
        assert val != null;
        return put(i, val, false);
    }

    @Override
    public V putIfAbsent(int key, V value) {
        assert value != null;
        return put(key, value, true);
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
    private V put(int key, V val, boolean onlyIfAbsent) {
        outer: while (true) {
            Entry<V>[] b = this.buckets;

            // check if we have an entry for i
            int ind = bucketForKey(key, b.length);
            Entry<V> e = getBucketHead(b, ind);
            Entry<V> firstEntry = e;
            while (e != null) {
                if (e.key == key) {
                    // we have an entry!
                    while (true) {
                        V existing = e.valueRef.get();
                        if (onlyIfAbsent && existing != null) {
                            // we don't want to put, since there is already an entry.
                            return existing;
                        }
                        // update it and return.
                        if (e.valueRef.compareAndSet(existing, val)) {
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
                e = e.next;
            }


            // there wasn't an entry, so we must be putting a non-empty value.
            assert val != null;

            // wait until there is no resizing going on.
            if (this.resizeInProgress.get()) {
                waitUntilResizingFinished();
                // and try again, since we were delayed
                continue outer;
            }

            // We add a new Entry
            // get a read lock
            Lock readLock = this.bucketUpdateLock.readLock();
            readLock.lock();
            try {
                Entry<V>[] currentBuckets = this.buckets;
                if (currentBuckets != b) {
                    // doh! the buckets changed under us, just try again.
                    continue outer;
                }
                AtomicReference<V> ref = new AtomicReference<>(val);

                Entry<V> newE = new Entry<V>(key, ref, firstEntry);
                boolean success = compareAndSwapBucketHead(b, ind, firstEntry, newE);
                readLock.unlock();
                readLock = null;
                if (success) {
                    // we swapped it!
                    checkMax(key);
                    checkResize(ind);
                    return null;
                }
                // we failed to add it to the head. Try the put again, by continuing at the outer loop.
            }
            finally {
                if (readLock != null) {
                    readLock.unlock();
                }
            }
        }
    }

    @Override
    public V remove(int key) {
        AtomicReference<V> ref = getRef(key, this.buckets);
        if (ref == null) {
            return null;
        }
        V prev = ref.getAndSet(null);
        if (prev != null) {
            this.deletedEntries.incrementAndGet();
        }
        return prev;
    }

    @Override
    public boolean remove(int key, V value) {
        assert value != null;
        AtomicReference<V> ref = getRef(key, this.buckets);
        if (ref == null) {
            return false;
        }
        if (ref.compareAndSet(value, null)) {
            this.deletedEntries.incrementAndGet();
            return true;
        }
        return false;
    }

    @Override
    public boolean replace(int key, V oldValue, V newValue) {
        assert newValue != null;
        AtomicReference<V> ref = getRef(key, this.buckets);
        if (ref == null) {
            return false;
        }
        return ref.compareAndSet(oldValue, newValue);
    }

    @Override
    public V replace(int key, V value) {
        assert value != null;
        AtomicReference<V> ref = getRef(key, this.buckets);
        if (ref == null) {
            return null;
        }
        return ref.getAndSet(value);
    }

    private void checkResize(int justUpdatedBucket) {
        Entry<V>[] b = this.buckets;
        Entry<V> e = getBucketHead(b, justUpdatedBucket);
        if (e != null && e.length >= THRESHOLD_BUCKET_LENGTH) {
            // we are over the threshold. Let's try to resize.
            resize(b);
        }
    }

    private void waitUntilResizingFinished() {
        while (this.resizeInProgress.get()) {
            // wait until the resizing is finished
            try {
                synchronized (this.resizingNotification) {
                    //System.err.println("waiting");
                    this.resizingNotification.wait(0, 500000);
                }
            }
            catch (InterruptedException e) {
                // just ignore it.
            }

        }
    }
    /**
     * Resize the buckets
     */
    private void resize(Entry<V>[] b) {
        if (!this.resizeInProgress.compareAndSet(false, true)) {
            // someone else is resizing already
            return;
        }
        Lock writeLock = this.bucketUpdateLock.writeLock();
        writeLock.lock();
        try {
            Entry<V>[] existingBuckets = this.buckets;

            if (b != existingBuckets) {
                // someone already resized it form when we decided we needed to.
                return;
            }
            int newBucketLength = existingBuckets.length * 2;
            Entry<V>[] newBuckets = new Entry[newBucketLength];
            for (int i = 0; i < existingBuckets.length; i++) {
                Entry<V> e = getBucketHead(existingBuckets, i);
                while (e != null) {
                    int ekey = e.key;
                    AtomicReference<V> evalueRef = e.valueRef;
                    int ind = bucketForKey(ekey, newBucketLength);
                    newBuckets[ind] = new Entry<>(ekey, evalueRef, newBuckets[ind]);
                    e = e.next;
                }
            }
            // now update the buckets
            this.buckets = newBuckets;
        }
        finally {
            // unlock
            if (writeLock != null) {
                writeLock.unlock();
            }
            this.resizeInProgress.set(false);
            synchronized (this.resizingNotification) {
                this.resizingNotification.notifyAll();
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
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

    @Override
    public IntIterator keyIterator() {
        return new KeyIterator(this.buckets);
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

    static <V> Entry<V> getBucketHead(ConcurrentMonotonicIntHashMap.Entry<V>[] b, int i) {
        return (Entry<V>) UNSAFE.getObjectVolatile(b, ((long) i << BUCKETSHIFT) + BUCKETBASE);
    }

    private boolean compareAndSwapBucketHead(Entry<V>[] b, int i, Entry<V> oldEntry, Entry<V> newEntry) {
        return UNSAFE.compareAndSwapObject(b, ((long) i << BUCKETSHIFT) + BUCKETBASE, oldEntry, newEntry);
    }


    /* ******************************************************************
     * Iterator
     *
     */
    private static class KeyIterator<V> implements IntIterator {
        final Entry<V>[] bs;
        int currentIndex = -1;
        ConcurrentMonotonicIntHashMap.Entry<?> currentEntry = null;
        boolean valid = false;

        public KeyIterator(Entry<V>[] buckets) {
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
                    if (currentEntry != null && currentEntry.valueRef.get() != null) {
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
                if (currentEntry.valueRef.get() != null) {
                    return currentEntry.key;
                }
                // whoops, we have a deleted entry. Try again.
            } while (true);
        }

    }

    public static void main(String[] args) {
        ConcurrentMonotonicIntHashMap<String> m = new ConcurrentMonotonicIntHashMap<>();
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
        System.out.println("OK: " + m.buckets.length);
        System.out.println("OK: " + m.max());
    }

}
