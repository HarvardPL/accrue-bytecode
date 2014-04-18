package analysis.dataflow.util;

/**
 * Abstract value used for data-flow analysis
 * 
 * @param <T>
 *            Type of the implementing class (e.g. MyAbsVal implements
 *            AbstractValue&ltMyAbsVal&gt)
 */
public interface AbstractValue<T> {

    /**
     * Is this abstract value less than or equal to the given abstract value
     * 
     * @param that
     *            value to compare
     * @return true if this is less than or equal to that
     */
    boolean leq(T that);

    /**
     * Is this the bottom element
     * 
     * @return true if this is the bottom element
     */
    public boolean isBottom();

    /**
     * Take the upper bound of this abstract value and the given abstract value
     * 
     * @param that
     *            value to take the upper bound with
     * @return the upper bound of this and that
     */
    public T join(T that);
}
