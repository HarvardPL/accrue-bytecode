package analysis.dataflow.interprocedural;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import analysis.dataflow.AbstractValue;
import analysis.dataflow.interprocedural.nonnull.NonNullAbsVal;

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
     * @param i
     * @return
     */
    public T getLocal(Integer i) {
        return locals.get(i);
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
    public T getException() {
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

    /**
     * Get a copy of the variable context with an empty set of locals and null return and exception abstract values
     * 
     * @return variable context with no locals and no exits
     */
    public VarContext<T> clearLocalsAndExits() {
        return new VarContext<T>(new LinkedHashMap<Integer, T>(), locations, null, null);
    }
}
