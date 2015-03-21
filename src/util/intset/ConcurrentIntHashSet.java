package util.intset;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.ReentrantLock;

import analysis.pointer.graph.AbstractIntSet;

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * A hash set supporting full concurrency of retrievals and adjustable expected concurrency for updates.
 *
 * This class is based on ConcurrentHashMap from the java.util.concurrency package, but is specialized for an int hash
 * set.
 */
@SuppressWarnings("restriction")
public class ConcurrentIntHashSet extends AbstractIntSet implements MutableIntSet {

    /* ---------------- Constants -------------- */

    /**
     * The default initial capacity for this table, used when not otherwise specified in a constructor.
     */
    static final int DEFAULT_INITIAL_CAPACITY = 16;

    /**
     * The default load factor for this table, used when not otherwise specified in a constructor.
     */
    static final float DEFAULT_LOAD_FACTOR = 0.75f;

    /**
     * The default concurrency level for this table, used when not otherwise specified in a constructor.
     */
    static final int DEFAULT_CONCURRENCY_LEVEL = 16;

    /**
     * The maximum capacity, used if a higher value is implicitly specified by either of the constructors with
     * arguments. MUST be a power of two <= 1<<30 to ensure that entries are indexable using ints.
     */
    static final int MAXIMUM_CAPACITY = 1 << 30;

    /**
     * The minimum capacity for per-segment tables. Must be a power of two, at least two to avoid immediate resizing on
     * next use after lazy construction.
     */
    static final int MIN_SEGMENT_TABLE_CAPACITY = 2;

    /**
     * The maximum number of segments to allow; used to bound constructor arguments. Must be power of two less than 1 <<
     * 24.
     */
    static final int MAX_SEGMENTS = 1 << 16; // slightly conservative

    /**
     * Number of unsynchronized retries in size and containsValue methods before resorting to locking. This is used to
     * avoid unbounded retries if tables undergo continuous modification which would make it impossible to obtain an
     * accurate result.
     */
    static final int RETRIES_BEFORE_LOCK = 2;

    /* ---------------- Fields -------------- */


    /**
     * Mask value for indexing into segments. The upper bits of a key's hash code are used to choose the segment.
     */
    final int segmentMask;

    /**
     * Shift value for indexing within segments.
     */
    final int segmentShift;

    /**
     * The segments, each of which is a specialized hash table.
     */
    final Segment[] segments;

    transient MutableIntSet keySet;

    /**
     * ConcurrentHashMap list entry. Note that this is never exported out as a user-visible Map.Entry.
     */
    static final class HashEntry {
        final int hash;
        final int key;
        volatile HashEntry next;

        HashEntry(int hash, int key, HashEntry next) {
            this.hash = hash;
            this.key = key;
            this.next = next;
        }

        /**
         * Sets next field with volatile write semantics. (See above about use of putOrderedObject.)
         */
        final void setNext(HashEntry n) {
            UNSAFE.putOrderedObject(this, nextOffset, n);
        }

        // Unsafe mechanics
        static final sun.misc.Unsafe UNSAFE;
        static final long nextOffset;
        static {
            try {
                Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                UNSAFE = (sun.misc.Unsafe) f.get(null);
                Class k = HashEntry.class;
                nextOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("next"));
            }
            catch (Exception e) {
                throw new Error(e);
            }
        }
    }


    /**
     * Gets the ith element of given table (if nonnull) with volatile read semantics. Note: This is manually integrated
     * into a few performance-sensitive methods to reduce call overhead.
     */
    @SuppressWarnings("unchecked")
    static final HashEntry entryAt(HashEntry[] tab, int i) {
        return (tab == null) ? null : (HashEntry) UNSAFE.getObjectVolatile(tab, ((long) i << TSHIFT)
                + TBASE);
    }

    /**
     * Sets the ith element of given table, with volatile write semantics. (See above about use of putOrderedObject.)
     */
    static final void setEntryAt(HashEntry[] tab, int i, HashEntry e) {
        UNSAFE.putOrderedObject(tab, ((long) i << TSHIFT) + TBASE, e);
    }

    /**
     * Applies a supplemental hash function to a given hashCode, which defends against poor quality hash functions. This
     * is critical because ConcurrentHashMap uses power-of-two length hash tables, that otherwise encounter collisions
     * for hashCodes that do not differ in lower or upper bits.
     */
    private int hash(int k) {
        int h = k;
        // Spread bits to regularize both segment and index locations,
        // using variant of single-word Wang/Jenkins hash.
        h += (h << 15) ^ 0xffffcd7d;
        h ^= (h >>> 10);
        h += (h << 3);
        h ^= (h >>> 6);
        h += (h << 2) + (h << 14);
        return h ^ (h >>> 16);
    }

    /**
     * Segments are specialized versions of hash tables. This subclasses from ReentrantLock opportunistically, just to
     * simplify some locking and avoid separate construction.
     */
    static final class Segment extends ReentrantLock implements Serializable {
        /*
         * Segments maintain a table of entry lists that are always
         * kept in a consistent state, so can be read (via volatile
         * reads of segments and tables) without locking.  This
         * requires replicating nodes when necessary during table
         * resizing, so the old lists can be traversed by readers
         * still using old version of table.
         *
         * This class defines only mutative methods requiring locking.
         * Except as noted, the methods of this class perform the
         * per-segment versions of ConcurrentHashMap methods.  (Other
         * methods are integrated directly into ConcurrentHashMap
         * methods.) These mutative methods use a form of controlled
         * spinning on contention via methods scanAndLock and
         * scanAndLockForPut. These intersperse tryLocks with
         * traversals to locate nodes.  The main benefit is to absorb
         * cache misses (which are very common for hash tables) while
         * obtaining locks so that traversal is faster once
         * acquired. We do not actually use the found nodes since they
         * must be re-acquired under lock anyway to ensure sequential
         * consistency of updates (and in any case may be undetectably
         * stale), but they will normally be much faster to re-locate.
         * Also, scanAndLockForPut speculatively creates a fresh node
         * to use in put if no node is found.
         */

        private static final long serialVersionUID = 2249069246763182397L;

        /**
         * The maximum number of times to tryLock in a prescan before possibly blocking on acquire in preparation for a
         * locked segment operation. On multiprocessors, using a bounded number of retries maintains cache acquired
         * while locating nodes.
         */
        static final int MAX_SCAN_RETRIES = Runtime.getRuntime().availableProcessors() > 1 ? 64 : 1;

        /**
         * The per-segment table. Elements are accessed via entryAt/setEntryAt providing volatile semantics.
         */
        transient volatile HashEntry[] table;

        /**
         * The number of elements. Accessed only either within locks or among other volatile reads that maintain
         * visibility.
         */
        transient int count;

        /**
         * The total number of mutative operations in this segment. Even though this may overflows 32 bits, it provides
         * sufficient accuracy for stability checks in CHM isEmpty() and size() methods. Accessed only either within
         * locks or among other volatile reads that maintain visibility.
         */
        transient int modCount;

        /**
         * The table is rehashed when its size exceeds this threshold. (The value of this field is always
         * <tt>(int)(capacity *
         * loadFactor)</tt>.)
         */
        transient int threshold;

        /**
         * The load factor for the hash table. Even though this value is same for all segments, it is replicated to
         * avoid needing links to outer object.
         *
         * @serial
         */
        final float loadFactor;

        Segment(float lf, int threshold, HashEntry[] tab) {
            this.loadFactor = lf;
            this.threshold = threshold;
            this.table = tab;
        }

        final boolean put(int key, int hash) {
            HashEntry node = tryLock() ? null : scanAndLockForPut(key, hash);
            boolean alreadyPresent;
            try {
                HashEntry[] tab = table;
                int index = (tab.length - 1) & hash;
                HashEntry first = entryAt(tab, index);
                for (HashEntry e = first;;) {
                    if (e != null) {
                        int k;
                        if ((k = e.key) == key || (e.hash == hash && key == k)) {
                            alreadyPresent = true;
                            ++modCount;
                            break;
                        }
                        e = e.next;
                    }
                    else {
                        if (node != null) {
                            node.setNext(first);
                        }
                        else {
                            node = new HashEntry(hash, key, first);
                        }
                        int c = count + 1;
                        if (c > threshold && tab.length < MAXIMUM_CAPACITY) {
                            rehash(node);
                        }
                        else {
                            setEntryAt(tab, index, node);
                        }
                        ++modCount;
                        count = c;
                        alreadyPresent = false;
                        break;
                    }
                }
            }
            finally {
                unlock();
            }
            return !alreadyPresent;
        }

        /**
         * Doubles size of table and repacks entries, also adding the given node to new table
         */
        @SuppressWarnings("unchecked")
        private void rehash(HashEntry node) {
            /*
             * Reclassify nodes in each list to new table.  Because we
             * are using power-of-two expansion, the elements from
             * each bin must either stay at same index, or move with a
             * power of two offset. We eliminate unnecessary node
             * creation by catching cases where old nodes can be
             * reused because their next fields won't change.
             * Statistically, at the default threshold, only about
             * one-sixth of them need cloning when a table
             * doubles. The nodes they replace will be garbage
             * collectable as soon as they are no longer referenced by
             * any reader thread that may be in the midst of
             * concurrently traversing table. Entry accesses use plain
             * array indexing because they are followed by volatile
             * table write.
             */
            HashEntry[] oldTable = table;
            int oldCapacity = oldTable.length;
            int newCapacity = oldCapacity << 1;
            threshold = (int) (newCapacity * loadFactor);
            HashEntry[] newTable = new HashEntry[newCapacity];
            int sizeMask = newCapacity - 1;
            for (int i = 0; i < oldCapacity; i++) {
                HashEntry e = oldTable[i];
                if (e != null) {
                    HashEntry next = e.next;
                    int idx = e.hash & sizeMask;
                    if (next == null) {
                        newTable[idx] = e;
                    }
                    else { // Reuse consecutive sequence at same slot
                        HashEntry lastRun = e;
                        int lastIdx = idx;
                        for (HashEntry last = next; last != null; last = last.next) {
                            int k = last.hash & sizeMask;
                            if (k != lastIdx) {
                                lastIdx = k;
                                lastRun = last;
                            }
                        }
                        newTable[lastIdx] = lastRun;
                        // Clone remaining nodes
                        for (HashEntry p = e; p != lastRun; p = p.next) {
                            int h = p.hash;
                            int k = h & sizeMask;
                            HashEntry n = newTable[k];
                            newTable[k] = new HashEntry(h, p.key, n);
                        }
                    }
                }
            }
            int nodeIndex = node.hash & sizeMask; // add the new node
            node.setNext(newTable[nodeIndex]);
            newTable[nodeIndex] = node;
            table = newTable;
        }

        /**
         * Scans for a node containing given key while trying to acquire lock, creating and returning one if not found.
         * Upon return, guarantees that lock is held. UNlike in most methods, calls to method equals are not screened:
         * Since traversal speed doesn't matter, we might as well help warm up the associated code and accesses as well.
         *
         * @return a new node if key not found, else null
         */
        private HashEntry scanAndLockForPut(int key, int hash) {
            HashEntry first = entryForHash(this, hash);
            HashEntry e = first;
            HashEntry node = null;
            int retries = -1; // negative while locating node
            while (!tryLock()) {
                HashEntry f; // to recheck first below
                if (retries < 0) {
                    if (e == null) {
                        if (node == null) {
                            node = new HashEntry(hash, key, null);
                        }
                        retries = 0;
                    }
                    else if (key == e.key) {
                        retries = 0;
                    }
                    else {
                        e = e.next;
                    }
                }
                else if (++retries > MAX_SCAN_RETRIES) {
                    lock();
                    break;
                }
                else if ((retries & 1) == 0 && (f = entryForHash(this, hash)) != first) {
                    e = first = f; // re-traverse if entry changed
                    retries = -1;
                }
            }
            return node;
        }

        /**
         * Scans for a node containing the given key while trying to acquire lock for a remove or replace operation.
         * Upon return, guarantees that lock is held. Note that we must lock even if the key is not found, to ensure
         * sequential consistency of updates.
         */
        private void scanAndLock(Object key, int hash) {
            // similar to but simpler than scanAndLockForPut
            HashEntry first = entryForHash(this, hash);
            HashEntry e = first;
            int retries = -1;
            while (!tryLock()) {
                HashEntry f;
                if (retries < 0) {
                    if (e == null || key.equals(e.key)) {
                        retries = 0;
                    }
                    else {
                        e = e.next;
                    }
                }
                else if (++retries > MAX_SCAN_RETRIES) {
                    lock();
                    break;
                }
                else if ((retries & 1) == 0 && (f = entryForHash(this, hash)) != first) {
                    e = first = f;
                    retries = -1;
                }
            }
        }

        /**
         * Remove; match on key only if value null, else match both.
         */
        final boolean remove(int key, int hash) {
            if (!tryLock()) {
                scanAndLock(key, hash);
            }
            boolean existing = false;
            try {
                HashEntry[] tab = table;
                int index = (tab.length - 1) & hash;
                HashEntry e = entryAt(tab, index);
                HashEntry pred = null;
                while (e != null) {
                    int k;
                    HashEntry next = e.next;
                    if ((k = e.key) == key || (e.hash == hash && key==k)) {
                        if (pred == null) {
                            setEntryAt(tab, index, next);
                        }
                        else {
                            pred.setNext(next);
                        }
                        ++modCount;
                        --count;
                        existing = true;
                        break;
                    }
                    pred = e;
                    e = next;
                }
            }
            finally {
                unlock();
            }
            return existing;
        }

        final void clear() {
            lock();
            try {
                HashEntry[] tab = table;
                for (int i = 0; i < tab.length; i++) {
                    setEntryAt(tab, i, null);
                }
                ++modCount;
                count = 0;
            }
            finally {
                unlock();
            }
        }
    }

    // Accessing segments

    /**
     * Gets the jth element of given segment array (if nonnull) with volatile element access semantics via Unsafe. (The
     * null check can trigger harmlessly only during deserialization.) Note: because each element of segments array is
     * set only once (using fully ordered writes), some performance-sensitive methods rely on this method only as a
     * recheck upon null reads.
     */
    @SuppressWarnings("unchecked")
    static final Segment segmentAt(Segment[] ss, int j) {
        long u = (j << SSHIFT) + SBASE;
        return ss == null ? null : (Segment) UNSAFE.getObjectVolatile(ss, u);
    }

    /**
     * Returns the segment for the given index, creating it and recording in segment table (via CAS) if not already
     * present.
     *
     * @param k the index
     * @return the segment
     */
    @SuppressWarnings("unchecked")
    private Segment ensureSegment(int k) {
        final Segment[] ss = this.segments;
        long u = (k << SSHIFT) + SBASE; // raw offset
        Segment seg;
        if ((seg = (Segment) UNSAFE.getObjectVolatile(ss, u)) == null) {
            Segment proto = ss[0]; // use segment 0 as prototype
            int cap = proto.table.length;
            float lf = proto.loadFactor;
            int threshold = (int) (cap * lf);
            HashEntry[] tab = new HashEntry[cap];
            if ((seg = (Segment) UNSAFE.getObjectVolatile(ss, u)) == null) { // recheck
                Segment s = new Segment(lf, threshold, tab);
                while ((seg = (Segment) UNSAFE.getObjectVolatile(ss, u)) == null) {
                    if (UNSAFE.compareAndSwapObject(ss, u, null, seg = s)) {
                        break;
                    }
                }
            }
        }
        return seg;
    }

    // Hash-based segment and entry accesses

    /**
     * Get the segment for the given hash
     */
    @SuppressWarnings("unchecked")
    private Segment segmentForHash(int h) {
        long u = (((h >>> segmentShift) & segmentMask) << SSHIFT) + SBASE;
        return (Segment) UNSAFE.getObjectVolatile(segments, u);
    }

    /**
     * Gets the table entry for the given segment and hash
     */
    @SuppressWarnings("unchecked")
    static final HashEntry entryForHash(Segment seg, int h) {
        HashEntry[] tab;
        return (seg == null || (tab = seg.table) == null) ? null
                : (HashEntry) UNSAFE.getObjectVolatile(tab, ((long) (((tab.length - 1) & h)) << TSHIFT)
                        + TBASE);
    }

    /* ---------------- Public operations -------------- */

    /**
     * Creates a new, empty map with the specified initial capacity, load factor and concurrency level.
     *
     * @param initialCapacity the initial capacity. The implementation performs internal sizing to accommodate this many
     *            elements.
     * @param loadFactor the load factor threshold, used to control resizing. Resizing may be performed when the average
     *            number of elements per bin exceeds this threshold.
     * @param concurrencyLevel the estimated number of concurrently updating threads. The implementation performs
     *            internal sizing to try to accommodate this many threads.
     * @throws IllegalArgumentException if the initial capacity is negative or the load factor or concurrencyLevel are
     *             nonpositive.
     */
    @SuppressWarnings("unchecked")
    public ConcurrentIntHashSet(int initialCapacity, float loadFactor, int concurrencyLevel) {
        if (!(loadFactor > 0) || initialCapacity < 0 || concurrencyLevel <= 0) {
            throw new IllegalArgumentException();
        }
        if (concurrencyLevel > MAX_SEGMENTS) {
            concurrencyLevel = MAX_SEGMENTS;
        }
        // Find power-of-two sizes best matching arguments
        int sshift = 0;
        int ssize = 1;
        while (ssize < concurrencyLevel) {
            ++sshift;
            ssize <<= 1;
        }
        this.segmentShift = 32 - sshift;
        this.segmentMask = ssize - 1;
        if (initialCapacity > MAXIMUM_CAPACITY) {
            initialCapacity = MAXIMUM_CAPACITY;
        }
        int c = initialCapacity / ssize;
        if (c * ssize < initialCapacity) {
            ++c;
        }
        int cap = MIN_SEGMENT_TABLE_CAPACITY;
        while (cap < c) {
            cap <<= 1;
        }
        // create segments and segments[0]
        Segment s0 = new Segment(loadFactor, (int) (cap * loadFactor), new HashEntry[cap]);
        Segment[] ss = new Segment[ssize];
        UNSAFE.putOrderedObject(ss, SBASE, s0); // ordered write of segments[0]
        this.segments = ss;
    }

    /**
     * Creates a new, empty map with the specified initial capacity and load factor and with the default
     * concurrencyLevel (16).
     *
     * @param initialCapacity The implementation performs internal sizing to accommodate this many elements.
     * @param loadFactor the load factor threshold, used to control resizing. Resizing may be performed when the average
     *            number of elements per bin exceeds this threshold.
     * @throws IllegalArgumentException if the initial capacity of elements is negative or the load factor is
     *             nonpositive
     *
     * @since 1.6
     */
    public ConcurrentIntHashSet(int initialCapacity, float loadFactor) {
        this(initialCapacity, loadFactor, DEFAULT_CONCURRENCY_LEVEL);
    }

    /**
     * Creates a new, empty map with the specified initial capacity, and with default load factor (0.75) and
     * concurrencyLevel (16).
     *
     * @param initialCapacity the initial capacity. The implementation performs internal sizing to accommodate this many
     *            elements.
     * @throws IllegalArgumentException if the initial capacity of elements is negative.
     */
    public ConcurrentIntHashSet(int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR, DEFAULT_CONCURRENCY_LEVEL);
    }

    /**
     * Creates a new, empty map with a default initial capacity (16), load factor (0.75) and concurrencyLevel (16).
     */
    public ConcurrentIntHashSet() {
        this(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, DEFAULT_CONCURRENCY_LEVEL);
    }


    /**
     * Returns <tt>true</tt> if this map contains no key-value mappings.
     *
     * @return <tt>true</tt> if this map contains no key-value mappings
     */
    @Override
    public boolean isEmpty() {
        /*
         * Sum per-segment modCounts to avoid mis-reporting when
         * elements are concurrently added and removed in one segment
         * while checking another, in which case the table was never
         * actually empty at any point. (The sum ensures accuracy up
         * through at least 1<<31 per-segment modifications before
         * recheck.)  Methods size() and containsValue() use similar
         * constructions for stability checks.
         */
        long sum = 0L;
        final Segment[] segments = this.segments;
        for (int j = 0; j < segments.length; ++j) {
            Segment seg = segmentAt(segments, j);
            if (seg != null) {
                if (seg.count != 0) {
                    return false;
                }
                sum += seg.modCount;
            }
        }
        if (sum != 0L) { // recheck unless no modifications
            for (int j = 0; j < segments.length; ++j) {
                Segment seg = segmentAt(segments, j);
                if (seg != null) {
                    if (seg.count != 0) {
                        return false;
                    }
                    sum -= seg.modCount;
                }
            }
            if (sum != 0L) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the number of key-value mappings in this map. If the map contains more than <tt>Integer.MAX_VALUE</tt>
     * elements, returns <tt>Integer.MAX_VALUE</tt>.
     *
     * @return the number of key-value mappings in this map
     */
    @Override
    public int size() {
        // Try a few times to get accurate count. On failure due to
        // continuous async changes in table, resort to locking.
        final Segment[] segments = this.segments;
        int size;
        boolean overflow; // true if size overflows 32 bits
        long sum; // sum of modCounts
        long last = 0L; // previous sum
        int retries = -1; // first iteration isn't retry
        try {
            for (;;) {
                if (retries++ == RETRIES_BEFORE_LOCK) {
                    for (int j = 0; j < segments.length; ++j) {
                        ensureSegment(j).lock(); // force creation
                    }
                }
                sum = 0L;
                size = 0;
                overflow = false;
                for (int j = 0; j < segments.length; ++j) {
                    Segment seg = segmentAt(segments, j);
                    if (seg != null) {
                        sum += seg.modCount;
                        int c = seg.count;
                        if (c < 0 || (size += c) < 0) {
                            overflow = true;
                        }
                    }
                }
                if (sum == last) {
                    break;
                }
                last = sum;
            }
        }
        finally {
            if (retries > RETRIES_BEFORE_LOCK) {
                for (int j = 0; j < segments.length; ++j) {
                    segmentAt(segments, j).unlock();
                }
            }
        }
        return overflow ? Integer.MAX_VALUE : size;
    }

    /**
     * Tests if the specified object is a key in this table.
     *
     * @param key possible key
     * @return <tt>true</tt> if and only if the specified object is a key in this table, as determined by the
     *         <tt>equals</tt> method; <tt>false</tt> otherwise.
     * @throws NullPointerException if the specified key is null
     */
    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(int key) {
        Segment s; // same as get() except no need for volatile value read
        HashEntry[] tab;
        int h = hash(key);
        long u = (((h >>> segmentShift) & segmentMask) << SSHIFT) + SBASE;
        if ((s = (Segment) UNSAFE.getObjectVolatile(segments, u)) != null && (tab = s.table) != null) {
            for (HashEntry e = (HashEntry) UNSAFE.getObjectVolatile(tab,
                                                                                ((long) (((tab.length - 1) & h)) << TSHIFT)
                                                                                        + TBASE); e != null; e = e.next) {
                int k;
                if ((k = e.key) == key || (e.hash == h && key==k)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Maps the specified key to the specified value in this table. Neither the key nor the value can be null.
     *
     * <p>
     * The value can be retrieved by calling the <tt>get</tt> method with a key that is equal to the original key.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with <tt>key</tt>, or <tt>null</tt> if there was no mapping for
     *         <tt>key</tt>
     * @throws NullPointerException if the specified key or value is null
     */
    @Override
    @SuppressWarnings("unchecked")
    public boolean add(int key) {
        Segment s;
        int hash = hash(key);
        int j = (hash >>> segmentShift) & segmentMask;
        if ((s = (Segment) UNSAFE.getObject // nonvolatile; recheck
        (segments, (j << SSHIFT) + SBASE)) == null) {
            s = ensureSegment(j);
        }
        return s.put(key, hash);
    }

    /**
     * Removes the key (and its corresponding value) from this map. This method does nothing if the key is not in the
     * map.
     *
     * @param key the key that needs to be removed
     * @return the previous value associated with <tt>key</tt>, or <tt>null</tt> if there was no mapping for
     *         <tt>key</tt>
     * @throws NullPointerException if the specified key is null
     */
    @Override
    public boolean remove(int key) {
        int hash = hash(key);
        Segment s = segmentForHash(hash);
        return s == null ? false : s.remove(key, hash);
    }


    /**
     * Returns an enumeration of the keys in this table.
     * @return
     *
     * @return an enumeration of the keys in this table
     * @see #keySet()
     */
    @Override
    public IntIterator intIterator() {
        return new KeyIterator();
    }


    /* ---------------- Iterator Support -------------- */

    abstract class HashIterator {
        int nextSegmentIndex;
        int nextTableIndex;
        HashEntry[] currentTable;
        HashEntry nextEntry;
        HashEntry lastReturned;

        HashIterator() {
            nextSegmentIndex = segments.length - 1;
            nextTableIndex = -1;
            advance();
        }

        /**
         * Set nextEntry to first node of next non-empty table (in backwards order, to simplify checks).
         */
        final void advance() {
            for (;;) {
                if (nextTableIndex >= 0) {
                    if ((nextEntry = entryAt(currentTable, nextTableIndex--)) != null) {
                        break;
                    }
                }
                else if (nextSegmentIndex >= 0) {
                    Segment seg = segmentAt(segments, nextSegmentIndex--);
                    if (seg != null && (currentTable = seg.table) != null) {
                        nextTableIndex = currentTable.length - 1;
                    }
                }
                else {
                    break;
                }
            }
        }

        final HashEntry nextEntry() {
            HashEntry e = nextEntry;
            if (e == null) {
                throw new NoSuchElementException();
            }
            lastReturned = e; // cannot assign until after null check
            if ((nextEntry = e.next) == null) {
                advance();
            }
            return e;
        }

        public final boolean hasNext() {
            return nextEntry != null;
        }

        public final boolean hasMoreElements() {
            return nextEntry != null;
        }

        public final void remove() {
            if (lastReturned == null) {
                throw new IllegalStateException();
            }
            ConcurrentIntHashSet.this.remove(lastReturned.key);
            lastReturned = null;
        }
    }

    final class KeyIterator extends HashIterator implements IntIterator {
        @Override
        public final int next() {
            return super.nextEntry().key;
        }
    }


    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long SBASE;
    private static final int SSHIFT;
    private static final long TBASE;
    private static final int TSHIFT;

    static {
        int ss, ts;
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
            Class tc = HashEntry[].class;
            Class sc = Segment[].class;
            TBASE = UNSAFE.arrayBaseOffset(tc);
            SBASE = UNSAFE.arrayBaseOffset(sc);
            ts = UNSAFE.arrayIndexScale(tc);
            ss = UNSAFE.arrayIndexScale(sc);
        }
        catch (Exception e) {
            throw new Error(e);
        }
        if ((ss & (ss - 1)) != 0 || (ts & (ts - 1)) != 0) {
            throw new Error("data type scale not a power of two");
        }
        SSHIFT = 31 - Integer.numberOfLeadingZeros(ss);
        TSHIFT = 31 - Integer.numberOfLeadingZeros(ts);
    }

    @Override
    public int max() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        final Segment[] segments = this.segments;
        for (int j = 0; j < segments.length; ++j) {
            Segment s = segmentAt(segments, j);
            if (s != null) {
                s.clear();
            }
        }
    }

    @Override
    public boolean addAll(IntSet set) {
        boolean changed = false;
        IntIterator iter = set.intIterator();
        while (iter.hasNext()) {
            int i = iter.next();
            changed |= add(i);
        }
        return changed;
    }

    @Override
    public void intersectWith(IntSet set) {
        IntIterator iter = this.intIterator();
        while (iter.hasNext()) {
            int n = iter.next();
            if (!set.contains(n)) {
                this.remove(n);
            }
        }
    }

    @Override
    public boolean addAllInIntersection(IntSet other, IntSet filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void copySet(IntSet set) {
        throw new UnsupportedOperationException();
    }

    public static void main(String[] args) {
        // test this class
        ConcurrentIntHashSet m = new ConcurrentIntHashSet();
        m.add(0);
        m.add(1);
        m.add(2);
        IntIterator keys = m.intIterator();
        while (keys.hasNext()) {
            int k = keys.next();
            System.out.println(k);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        IntIterator iter = this.intIterator();
        while (iter.hasNext()) {
            sb.append(iter.next());
            if (!iter.hasNext()) {
                return sb.append("}").toString();
            }
            sb.append(", ");
        }
        sb.append("}");
        return super.toString();
    }
}
