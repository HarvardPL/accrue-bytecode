package analysis.pointer.graph;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import util.intset.EmptyIntSet;
import analysis.AnalysisUtil;

import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;

/**
 * A KilledAndAlloced object is simply the pair of two sets, one set which records which PointsToGraphNodes have been
 * killed, and the other set records which InstanceKeys have been allocated.
 *
 * KilledAndAlloced objects are used as program analysis facts. That is, when analyzing a method, we may record for each
 * program point pp in the method, which PointsToGraphNodes must have been killed on all path from the method entry to
 * pp, and which InstanceKeyRecency must have been newly allocated on all paths from the method entry to pp.
 *
 * The super class presents a read-only interface. Only the subclasses present a mutable interface.
 */
public abstract class KilledAndAlloced {
    protected/*Set<PointsToGraphNode>*/MutableIntSet killed;
    protected/*Set<InstanceKeyRecency>*/MutableIntSet alloced;
    protected Set<FieldReference> maybeKilledFields;

    @Override
    public final boolean equals(Object obj) {
        throw new UnsupportedOperationException();
    }

    @Override
    public final int hashCode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        return "killed=" + this.killed + " alloced=" + this.alloced + " killedFields=" + this.maybeKilledFields;
    }

    /**
     * Does this KilledAndAlloced represent an unreachable set? (The unreachable set is the top element of the lattice)
     *
     * @return
     */
    public abstract boolean isUnreachable();

    /**
     * Does this KilledAndAlloced represent the empty set (i.e., killed, alloced, and maybeKilledFields are all empty)?
     * If so, this is the bottom element of the lattice.
     *
     * @return
     */
    public abstract boolean isEmpty();

    /**
     * Returns false if this.killed intersects with noKill, or if this.alloced intersects with noAlloc. Otherwise, it
     * returns true.
     *
     * @param g
     */
    public boolean allows(IntSet noKill, IntSet noAlloc, PointsToGraph g) {
        if (this.isUnreachable()) {
            return false;
        }
        if (this.isEmpty()) {
            return true;
        }
        if (this.killed.containsAny(noKill)) {
            return false;
        }
        if (this.alloced.containsAny(noAlloc)) {
            return false;
        }
        // check if the killed fields might be a problem.
        if (!this.maybeKilledFields.isEmpty()) {
            IntIterator iter = noKill.intIterator();
            while (iter.hasNext()) {
                int n = iter.next();
                PointsToGraphNode node = g.lookupPointsToGraphNodeDictionary(n);
                if (node instanceof ObjectField
                        && this.maybeKilledFields.contains(((ObjectField) node).fieldReference())) {
                    // the field may be killed.
                    return false;
                }
            }
        }
        return true;
    }


    public static ThreadLocalKilledAndAlloced createLocalUnreachable() {
        return new ThreadLocalKilledAndAlloced(null, null, null);
    }

    public static ThreadLocalKilledAndAlloced createLocalEmpty() {
        return new ThreadLocalKilledAndAlloced(MutableSparseIntSet.makeEmpty(),
                                               new HashSet<FieldReference>(),
                                               MutableSparseIntSet.makeEmpty());
    }

    public static ThreadSafeKilledAndAlloced createThreadSafeUnreachable() {
        return new ThreadSafeKilledAndAlloced();
    }

    /**
     * A non-thread-safe version of KilledAndAlloced, which should NOT be used for any KilledAndAlloced that may be
     * accessed concurrently.
     */
    static class ThreadLocalKilledAndAlloced extends KilledAndAlloced {
        ThreadLocalKilledAndAlloced(MutableIntSet killed, Set<FieldReference> maybeKilledFields,
                                            MutableIntSet alloced) {
            this.killed = killed;
            this.maybeKilledFields = maybeKilledFields;
            this.alloced = alloced;
            assert (killed == null && maybeKilledFields == null && alloced == null)
                    || (killed != null && maybeKilledFields != null && alloced != null);
        }

        /**
         * Combine (union) a and b. The result should not be imperatively updated.
         *
         * @param a
         * @param b
         */
        public static KilledAndAlloced join(KilledAndAlloced a, KilledAndAlloced b) {
            if (a.isUnreachable()) {
                // represents everything!
                return a;
            }
            if (b.isUnreachable()) {
                // represents everything!
                return b;
            }

            if (a.isEmpty()) {
                // a is the bottom of the lattice
                return b;
            }
            if (b.isEmpty()) {
                // b is the bottom of the lattice
                return a;
            }
            int killedSize = a.killed.size() + b.killed.size();
            MutableIntSet killed = killedSize == 0 ? EmptyIntSet.INSTANCE
                    : MutableSparseIntSet.createMutableSparseIntSet(killedSize);
            Set<FieldReference> maybeKilledFields = new LinkedHashSet<>();
            int allocedSize = a.alloced.size() + b.alloced.size();
            MutableIntSet alloced = allocedSize == 0 ? EmptyIntSet.INSTANCE
                    : MutableSparseIntSet.createMutableSparseIntSet(allocedSize);

            if (killedSize > 0) {
                killed.addAll(a.killed);
                killed.addAll(b.killed);
            }
            maybeKilledFields.addAll(a.maybeKilledFields);
            maybeKilledFields.addAll(b.maybeKilledFields);
            if (allocedSize > 0) {
                alloced.addAll(a.alloced);
                alloced.addAll(b.alloced);
            }

            return new ThreadLocalKilledAndAlloced(killed, maybeKilledFields, alloced);
        }

        /**
         * Take the intersection of the killed and alloced sets with the corresponding sets in res. This method
         * imperatively updates the killed and alloced sets. It returns true if and only if the killed or alloced sets
         * of this object changed.
         */
        public boolean meet(KilledAndAlloced res) {
            assert (this.killed == null && this.maybeKilledFields == null && this.alloced == null)
                    || (this.killed != null && this.maybeKilledFields != null && this.alloced != null) : "this has violated the invariants that either all fields are null or none of them are: "
                    + this;
            assert (res.isUnreachable() || (res.killed != null && res.maybeKilledFields != null && res.alloced != null)) : "res has violated the invariants that either all fields are null or none of them are : "
                    + res + " :: " + res.getClass();

            if (this == res || this.isEmpty() || res.isUnreachable()) {
                // no change to this object.
                return false;
            }
            if (this.isUnreachable()) {
                // we represent the "universal" sets, so intersecting with
                // the sets in res just gives us directly the sets in res.
                // So copy over the sets res.killed and res.alloced.
                this.killed = MutableSparseIntSet.createMutableSparseIntSet(2);
                this.killed.copySet(res.killed);
                this.maybeKilledFields = new LinkedHashSet<>(res.maybeKilledFields);
                this.alloced = MutableSparseIntSet.createMutableSparseIntSet(2);
                this.alloced.copySet(res.alloced);
                return true;
            }
            assert !res.isUnreachable() && (res.killed != null && res.maybeKilledFields != null && res.alloced != null);

            // intersect the sets, and see if the size of either of them changed.
            int origKilledSize = this.killed.size();
            int origAllocedSize = this.alloced.size();
            this.killed.intersectWith(res.killed);
            this.alloced.intersectWith(res.alloced);
            boolean changed = this.maybeKilledFields.retainAll(res.maybeKilledFields);
            return changed || (this.killed.size() != origKilledSize || this.alloced.size() != origAllocedSize);

        }

        /**
         * Add a points to graph node to the kill set.
         */
        public boolean addKill(/*PointsToGraphNode*/int n) {
            assert killed != null;
            return this.killed.add(n);
        }

        public boolean addMaybeKilledField(FieldReference f) {
            assert maybeKilledFields != null;
            return this.maybeKilledFields.add(f);
        }

        /**
         * Add an instance key to the alloced set.
         */
        public boolean addAlloced(/*InstanceKeyRecency*/int justAllocatedKey) {
            assert alloced != null;
            return this.alloced.add(justAllocatedKey);
        }

        /**
         * Set the killed and alloced sets to empty. This should be used only as the first operation called after the
         * constructor.
         */
        public void setEmpty() {
            assert this.isUnreachable();
            assert killed == null;
            assert maybeKilledFields == null;
            assert alloced == null;
            this.killed = MutableSparseIntSet.createMutableSparseIntSet(1);
            this.maybeKilledFields = Collections.emptySet();
            this.alloced = MutableSparseIntSet.createMutableSparseIntSet(1);
        }

        @Override
        public boolean isUnreachable() {
            return this.killed == null;
        }

        @Override
        public boolean isEmpty() {
            return this.killed != null && this.killed.isEmpty() && this.alloced.isEmpty()
                    && this.maybeKilledFields.isEmpty();
        }
    }

    /**
     * A thread-safe version of KilledAndAlloced, which is used for any KilledAndAlloced that may be accessed
     * concurrently.
     */
    @SuppressWarnings("restriction")
    public static class ThreadSafeKilledAndAlloced extends KilledAndAlloced {
        private volatile boolean isUnreachable = true;
        private volatile boolean isEmpty = false;

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long KILLEDOFFSET;
        private static final long ALLOCEDOFFSET;
        private static final long MAYBEKILLEDOFFSET;

        static {
            try {
                Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                UNSAFE = (sun.misc.Unsafe) f.get(null);


                KILLEDOFFSET = UNSAFE.objectFieldOffset(KilledAndAlloced.class.getDeclaredField("killed"));
                ALLOCEDOFFSET = UNSAFE.objectFieldOffset(KilledAndAlloced.class.getDeclaredField("alloced"));
                MAYBEKILLEDOFFSET = UNSAFE.objectFieldOffset(KilledAndAlloced.class.getDeclaredField("maybeKilledFields"));

            }
            catch (Exception e) {
                throw new Error(e);
            }
        }

        @Override
        public boolean isUnreachable() {
            return this.isUnreachable;
        }

        @Override
        public boolean isEmpty() {
            return this.isEmpty;
        }

        public void setEmpty() {
            // install the new sets, ensuring that we don't overwrite a non-null value.
            if (!UNSAFE.compareAndSwapObject(this, KILLEDOFFSET, null, AnalysisUtil.createConcurrentIntSet())) {
                throw new RuntimeException("Failed to set empty!");
            }
            if (!UNSAFE.compareAndSwapObject(this, ALLOCEDOFFSET, null, AnalysisUtil.createConcurrentIntSet())) {
                throw new RuntimeException("Failed to set empty!");
            }
            if (!UNSAFE.compareAndSwapObject(this, MAYBEKILLEDOFFSET, null, AnalysisUtil.createConcurrentSet())) {
                throw new RuntimeException("Failed to set empty!");
            }

            this.isUnreachable = false;
            this.isEmpty = true;
        }

        public boolean meet(KilledAndAlloced kaa) {
            if (this == kaa || this.isEmpty || kaa.isUnreachable()) {
                // no change to this object.
                return false;
            }

            if (this.isUnreachable) {
                // we represent the "universal" sets, so intersecting with
                // the sets in res just gives us directly the sets in kaa.
                // So copy over the sets kaa.killed and kaa.alloced.

                // First, the killed set.
                {
                    MutableIntSet newKilled = AnalysisUtil.createConcurrentIntSet();
                    newKilled.addAll(kaa.killed);

                    // try to install it
                    if (UNSAFE.compareAndSwapObject(this, KILLEDOFFSET, null, newKilled)) {
                        // success!
                    }
                    else {
                        // ensure we intersect with kaa.killed
                        this.killed.intersectWith(kaa.killed);
                    }
                }

                // Second, the alloced set.
                {
                    MutableIntSet newAlloced = AnalysisUtil.createConcurrentIntSet();
                    newAlloced.addAll(kaa.alloced);

                    // try to install it
                    if (UNSAFE.compareAndSwapObject(this, ALLOCEDOFFSET, null, newAlloced)) {
                        // success!
                    }
                    else {
                        // ensure we intersect with kaa.alloced
                        this.alloced.intersectWith(kaa.alloced);
                    }
                }

                // Third, the maybe killed set
                {
                    Set<FieldReference> newMaybeKilled = AnalysisUtil.createConcurrentSet();
                    newMaybeKilled.addAll(kaa.maybeKilledFields);

                    // try to install it
                    if (UNSAFE.compareAndSwapObject(this, MAYBEKILLEDOFFSET, null, newMaybeKilled)) {
                        // success!
                    }
                    else {
                        // ensure we intersect with kaa.maybeKilledFields
                        this.maybeKilledFields.retainAll(kaa.maybeKilledFields);
                    }
                }
                this.isUnreachable = false;
                this.isEmpty = (this.killed.isEmpty() && this.alloced.isEmpty() && this.maybeKilledFields.isEmpty());
                return true;
            }

            if (kaa.isEmpty()) {
                this.killed.clear();
                this.alloced.clear();
                this.maybeKilledFields.clear();
                this.isEmpty = true;
                return true;
            }

            // intersect the sets, and see if the size of either of them changed. Wish the MutableIntSet interface provided a cleaner way of determining if
            // it had changed. The good news is that the only way the thread safe KAAs change is by shrinking, so this is sound.
            int origKilledSize = this.killed.size();
            int origAllocedSize = this.alloced.size();
            this.killed.intersectWith(kaa.killed);
            this.alloced.intersectWith(kaa.alloced);
            boolean changed = this.maybeKilledFields.retainAll(kaa.maybeKilledFields);
            this.isEmpty = (this.killed.isEmpty() && this.alloced.isEmpty() && this.maybeKilledFields.isEmpty());
            return changed || (this.killed.size() != origKilledSize || this.alloced.size() != origAllocedSize);
        }
    }
}
