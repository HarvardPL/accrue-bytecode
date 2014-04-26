package analysis.dataflow.util;

/**
 * Abstract value that is a partially ordered set of two elements with a join
 * operation
 */
public abstract class TwoElementSemiLattice<T extends AbstractValue<T>> implements AbstractValue<T> {

    @Override
    public boolean leq(T that) {
        assert that != null;
        return this.isBottom() || !that.isBottom();
    }

    @Override
    public T join(T that) {
        if (this.isBottom() && that != null) {
            return that;
        }
        return this.isBottom() ? getBottom() : getTop();
    }

    @Override
    public final boolean isBottom() {
        return this.equals(getBottom());
    }

    /**
     * Get the top element of the semi-lattice
     * 
     * @return top abstract value
     */
    protected abstract T getTop();

    /**
     * Get the bottom element of the semi-lattice
     * 
     * @return bottom abstract value
     */
    protected abstract T getBottom();
}
