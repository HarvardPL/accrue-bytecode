package analysis.dataflow.interprocedural;

import java.util.Map;
import java.util.Set;

import analysis.dataflow.AbstractValue;

public class VarContext<T extends AbstractValue<T>> implements AbstractValue<VarContext<T>> {

    private final Map<Integer, T> locals;
    private final Map<AbstractLocation, T> locations;
    private final T returnResult;
    private final T exceptionValue;

    public VarContext(Map<Integer, T> locals, Map<AbstractLocation, T> locations, T returnResult, T exceptionValue) {
        this.locals = locals;
        this.locations = locations;
        this.returnResult = returnResult;
        this.exceptionValue = exceptionValue;
    }

    /**
     * Get the abstract value for a local variable with the given value number
     * 
     * @param s
     * @return
     */
    public T getLocal(Integer s) {
        return locals.get(s);
    }

    /**
     * Get the abstract value for the given abstract heap location
     * 
     * @param loc
     * @return
     */
    public T getLocation(AbstractLocation loc) {
        return locations.get(loc);
    }

    /**
     * Get the abstract value for the return result, null if there is none.
     * 
     * @return abstract return value
     */
    public T getReturnResult() {
        return returnResult;
    }

    /**
     * Get the abstract value for the exception, null if there is none.
     * 
     * @return abstract exception value
     */
    public T getExceptionValue() {
        return exceptionValue;
    }

    /**
     * Replaces the current
     * 
     * @param i
     * @param val
     * @return
     */
    public VarContext<T> setLocal(int i, T val) {
        // TODO record Local
        return null;
    }

    /**
     * Joins with the current
     * 
     * @param loc
     * @param val
     * @return
     */
    public VarContext<T> joinLocation(AbstractLocation loc, T val) {
        // TODO record Location
        return null;
    }

    /**
     * Joins with the current
     * 
     * @param locs
     * @param val
     * @return
     */
    public VarContext<T> joinLocations(Set<AbstractLocation> locs, T val) {
        // TODO record Location
        return null;
    }

    /**
     * Replace the current return result with the given abstract value
     * 
     * @param returnAbsVal
     *            new abstract value for the return item
     * @return copy of the variable context with the new return value
     */
    public VarContext<T> setReturnResult(T returnAbsVal) {
        return new VarContext<T>(locals, locations, returnAbsVal, exceptionValue);
    }
    
    /**
     * Replace the current value for the exception with the given abstract value
     * 
     * @param exceptionAbsVal
     *            new abstract value for the exception
     * @return copy of the variable context with the new exception value
     */
    public VarContext<T> setExceptionValue(T exceptionAbsVal) {
        return new VarContext<T>(locals, locations, returnResult, exceptionValue);
    }

    @Override
    public boolean leq(VarContext<T> that) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isBottom() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public VarContext<T> join(VarContext<T> that) {
        // TODO Auto-generated method stub
        return null;
    }
}
