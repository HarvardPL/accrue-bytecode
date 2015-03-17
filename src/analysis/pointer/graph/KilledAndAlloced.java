package analysis.pointer.graph;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import util.intset.EmptyIntSet;

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
 */
public class KilledAndAlloced {
    //    /**
    //     * We use a distinguished constant for unreachable program points. The null value for the killed and alloced sets
    //     * represents the "universe" sets, e.g., if killed == null, then it means that all fields are killed on all paths to
    //     * the program point.
    //     */
    //    static final KilledAndAlloced UNREACHABLE = new KilledAndAlloced(null, null, null);

    private/*Set<PointsToGraphNode>*/MutableIntSet killed;
    private/*Set<InstanceKeyRecency>*/MutableIntSet alloced;
    private Set<FieldReference> maybeKilledFields;

    private KilledAndAlloced(MutableIntSet killed, Set<FieldReference> maybeKilledFields, MutableIntSet alloced) {
        this.killed = killed;
        this.maybeKilledFields = maybeKilledFields;
        this.alloced = alloced;
        assert (killed == null && maybeKilledFields == null && alloced == null)
                || (killed != null && maybeKilledFields != null && alloced != null);
    }

    /**
     * Create a killed and alloced object where all nodes and fields are killed and all instance keys are alloced
     *
     * @return new KilledAndAlloced where everything is killed or alloced
     */
    static KilledAndAlloced createUnreachable() {
        return new KilledAndAlloced(null, null, null);
    }

    /**
     * Combine (union) a and b.
     *
     * @param a
     * @param b
     */
    public static KilledAndAlloced join(KilledAndAlloced a, KilledAndAlloced b) {
        if (a.killed == null) {
            // represents everything!
            return a;
        }
        if (b.killed == null) {
            // represents everything!
            return b;
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

        return new KilledAndAlloced(killed, maybeKilledFields, alloced);
    }

    /**
     * Take the intersection of the killed and alloced sets with the corresponding sets in res. This method imperatively
     * updates the killed and alloced sets. It returns true if and only if the killed or alloced sets of this object
     * changed.
     */
    public synchronized boolean meet(KilledAndAlloced res) {
        assert (this.killed == null && this.maybeKilledFields == null && this.alloced == null)
                || (this.killed != null && this.maybeKilledFields != null && this.alloced != null) : "this has violated the invariants that either all fields are null or none of them are: "
                + this;
        assert (res.killed == null && res.maybeKilledFields == null && res.alloced == null)
                || (res.killed != null && res.maybeKilledFields != null && res.alloced != null) : "res has violated the invariants that either all fields are null or none of them are : "
                + res;

        if (this == res || res.killed == null) {
            // no change to this object.
            return false;
        }
        if (this.killed == null) {
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
    public synchronized boolean addKill(/*PointsToGraphNode*/int n) {
        assert killed != null;
        return this.killed.add(n);
    }

    public synchronized boolean addMaybeKilledField(FieldReference f) {
        assert maybeKilledFields != null;
        return this.maybeKilledFields.add(f);
    }

    /**
     * Add an instance key to the alloced set.
     */
    public synchronized boolean addAlloced(/*InstanceKeyRecency*/int justAllocatedKey) {
        assert alloced != null;
        return this.alloced.add(justAllocatedKey);
    }

    /**
     * Set the killed and alloced sets to empty. This should be used only as the first operation called after the
     * constructor.
     */
    public synchronized void setEmpty() {
        assert killed == null;
        assert maybeKilledFields == null;
        assert alloced == null;
        this.killed = MutableSparseIntSet.createMutableSparseIntSet(1);
        this.maybeKilledFields = Collections.emptySet();
        this.alloced = MutableSparseIntSet.createMutableSparseIntSet(1);
    }

    /**
     * Returns false if this.killed intersects with noKill, or if this.alloced intersents with noAlloc. Otherwise, it
     * returns true.
     *
     * @param g
     */
    public synchronized boolean allows(IntSet noKill, IntSet noAlloc, PointsToGraph g) {
        if ((this.killed != null && !this.killed.containsAny(noKill))
                && (this.alloced != null && !this.alloced.containsAny(noAlloc))) {
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
        return false;
    }

    protected MutableIntSet getAlloced() {
        return this.alloced;
    }

    protected MutableIntSet getKilled() {
        return this.killed;
    }

    protected Set<FieldReference> getMaybeKilledFields() {
        return this.maybeKilledFields;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((alloced == null) ? 0 : alloced.size());
        result = prime * result + ((killed == null) ? 0 : killed.size());
        result = prime * result + ((maybeKilledFields == null) ? 0 : maybeKilledFields.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof KilledAndAlloced)) {
            return false;
        }
        KilledAndAlloced other = (KilledAndAlloced) obj;
        if (alloced == null) {
            if (other.alloced != null) {
                return false;
            }
        }
        else if (other.alloced == null) {
            return false;
        }
        else if (!alloced.sameValue(other.alloced)) {
            return false;
        }
        if (killed == null) {
            if (other.killed != null) {
                return false;
            }
        }
        else if (!killed.sameValue(other.killed)) {
            return false;
        }
        if (maybeKilledFields == null) {
            if (other.maybeKilledFields != null) {
                return false;
            }
        }
        else if (!maybeKilledFields.equals(other.maybeKilledFields)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "killed=" + this.killed + " alloced=" + this.alloced + " killedFields=" + this.maybeKilledFields;
    }
}
