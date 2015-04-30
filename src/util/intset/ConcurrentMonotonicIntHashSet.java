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
 * XXX in rare cases, more than one thread may return true for add(n).
 */
public class ConcurrentMonotonicIntHashSet implements MutableIntSet {
    private static final int INITIAL_BUCKET_SIZE = 16;

    /**
     * The threshold length on any one bucket to trigger a rehashing.
     */
    private static final int THRESHOLD_BUCKET_LENGTH = 8;

    private static class Entry {
        Entry(int key, Entry next) {
            this.next = next;
            this.key = key;
            this.length = next == null ? 1 : next.length + 1;
        }

        final Entry next;
        final int key; // also the hash
        final int length; // length of the list starting with this node.
    }

    private volatile Entry[] buckets;

    /**
     * Best guess at the max key.
     */
    private final AtomicInteger max = new AtomicInteger(-1);

    /**
     * Is a resize in progress? We treat this like a lock.
     */
    private final AtomicBoolean resizeInProgress = new AtomicBoolean(false);

    /**
     * An object used to let threads wait until the resizing is finished.
     */
    private final Object resizingNotification = new Object();

    private final ReadWriteLock bucketUpdateLock = new ReentrantReadWriteLock(true);

    public ConcurrentMonotonicIntHashSet() {
        this(INITIAL_BUCKET_SIZE);
    }

    private ConcurrentMonotonicIntHashSet(int initialCapacity) {
        this.buckets = new Entry[initialCapacity];
    }

    @Override
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
        int h = i;
        h ^= (h >>> 20) ^ (h >>> 12);
        h ^= (h >>> 7) ^ (h >>> 4);
        return h % numBuckets;
    }

    @Override
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


            // there wasn't an entry

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
                Entry newE = new Entry(key, firstEntry);
                boolean success = compareAndSwapBucketHead(b, ind, firstEntry, newE);
                readLock.unlock();
                readLock = null;
                if (success) {
                    if (this.resizeInProgress.get() || this.buckets != b) {
                        // Doh! we may have been swapped out.
                        // Try again, but return true always.
                        add(key);
                    }
                    // we swapped it!
                    checkMax(key);
                    checkResize(ind);
                    return true;
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
    public boolean remove(int key) {
        throw new UnsupportedOperationException();
    }

    private void checkResize(int justUpdatedBucket) {
        Entry[] b = this.buckets;
        Entry e = getBucketHead(b, justUpdatedBucket);
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
    private void resize(Entry[] b) {
        if (!this.resizeInProgress.compareAndSet(false, true)) {
            // someone else is resizing already
            return;
        }
        Lock writeLock = this.bucketUpdateLock.writeLock();
        writeLock.lock();
        try {
            Entry[] existingBuckets = this.buckets;

            if (b != existingBuckets) {
                // someone already resized it from when we decided we needed to.
                return;
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
        Entry[] bs = this.buckets;
        for (int i = 0; i < bs.length; i++) {
            Entry e = getBucketHead(bs, i);
            if (e != null) {
                count += e.length;
            }
        }
        return count;
    }

    @Override
    public IntIterator intIterator() {
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
    private static class KeyIterator implements IntIterator {
        final Entry[] bs;
        int currentIndex = -1;
        ConcurrentMonotonicIntHashSet.Entry currentEntry = null;
        boolean valid = false;

        public KeyIterator(Entry[] buckets) {
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
        System.out.println("OK: " + m.buckets.length);
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

}
