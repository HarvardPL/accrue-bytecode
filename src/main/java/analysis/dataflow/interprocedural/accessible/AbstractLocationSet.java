package analysis.dataflow.interprocedural.accessible;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import analysis.dataflow.util.AbstractLocation;
import analysis.dataflow.util.AbstractValue;

/**
 * Functional set of abstract locations
 */
public class AbstractLocationSet implements AbstractValue<AbstractLocationSet>, Iterable<AbstractLocation> {
    /**
     * Internal representation
     */
    private final Set<AbstractLocation> locs;

    /**
     * Unmodifiable empty set of locations
     */
    public static final AbstractLocationSet EMPTY = new AbstractLocationSet(Collections.<AbstractLocation> emptySet());

    /**
     * Create a location set from a raw set of abstract locations
     *
     * @param locs locations to wrap
     */
    private AbstractLocationSet(Set<AbstractLocation> locs) {
        this.locs = Collections.unmodifiableSet(locs);
    }

    @Override
    public Iterator<AbstractLocation> iterator() {
        return this.locs.iterator();
    }

    /**
     * Add a location to those in the current set, will not modify "this"
     *
     * @param e location to add
     * @return Set containing the given element and all elements of "this"
     */
    public AbstractLocationSet add(AbstractLocation e) {
        if (locs.contains(e)) {
            return this;
        }
        Set<AbstractLocation> newLocs = new LinkedHashSet<>(locs);
        newLocs.add(e);
        return new AbstractLocationSet(newLocs);
    }

    /**
     * Add locations to those in the current set, will not modify "this"
     *
     * @param c set of locations to add
     * @return Set containing the given elements and all elements of "this"
     */
    public AbstractLocationSet addAll(Collection<? extends AbstractLocation> c) {
        if (locs.containsAll(c)) {
            return this;
        }
        Set<AbstractLocation> newLocs = new LinkedHashSet<>(locs);
        newLocs.addAll(c);
        return new AbstractLocationSet(newLocs);
    }

    @Override
    public boolean leq(AbstractLocationSet that) {
        return that.locs.containsAll(this.locs);
    }

    @Override
    public boolean isBottom() {
        return locs.isEmpty();
    }

    @Override
    public AbstractLocationSet join(AbstractLocationSet that) {
        assert that != null : "Joining null abs val";
        if (this == that || this.equals(that)) {
            return this;
        }
        Set<AbstractLocation> newLocs = new LinkedHashSet<>();
        newLocs.addAll(locs);
        newLocs.addAll(that.locs);
        return new AbstractLocationSet(newLocs);
    }

    /**
     * Join all of the given sets, returns a new set unless the input is a singleton
     *
     * @param toJoin sets to join
     * @return new set combining the elements of the input sets
     */
    public static AbstractLocationSet joinAll(Set<AbstractLocationSet> toJoin) {
        assert toJoin.size() != 0;
        if (toJoin.size() == 1) {
            // Only one input, return it
            return toJoin.iterator().next();
        }

        Set<AbstractLocation> newLocs = new LinkedHashSet<>();
        for (AbstractLocationSet s : toJoin) {
            newLocs.addAll(s.locs);
        }
        return new AbstractLocationSet(newLocs);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.locs == null) ? 0 : this.locs.hashCode());
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
        if (getClass() != obj.getClass()) {
            return false;
        }
        AbstractLocationSet other = (AbstractLocationSet) obj;
        if (this.locs == null) {
            if (other.locs != null) {
                return false;
            }
        }
        else if (!this.locs.equals(other.locs)) {
            return false;
        }
        return true;
    }

    /**
     * Get the internal representation, should only be called after the analysis has completed
     *
     * @return internal set of locations
     */
    public Set<AbstractLocation> getRawSet() {
        return locs;
    }
}
