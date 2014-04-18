package analysis.dataflow.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mapping from local variables and abstract heap locations to abstract values
 * 
 * @param <T>
 *            type of abstract values
 */
public class VarContext<T extends AbstractValue<T>> implements AbstractValue<VarContext<T>> {

    /**
     * If true then heap locations will be tracked, otherwise they will not
     */
    private final boolean trackHeapLocations;
    /**
     * If heap locations are not tracked then this is the value for all heap
     * locations
     */
    private final T untrackedHeapLocationValue;
    /**
     * Map from local variables to abstract values
     */
    private final Map<Integer, T> locals;
    /**
     * Map from abstract heap location to abstract values
     */
    private final Map<AbstractLocation, T> locations;
    /**
     * Abstract value for the return result of a procedure
     */
    private final T returnResult;
    /**
     * Abstract value for an exception thrown by a procedure
     */
    private final T exceptionValue;

    /**
     * Create a new variable context with the given entries
     * 
     * @param locals
     *            map from local variables to abstract values
     * @param locations
     *            map from abstract heap locations to abstract values
     * @param returnResult
     *            abstract value for the return result
     * @param exceptionValue
     *            abstract value for the exception
     */
    protected VarContext(Map<Integer, T> locals, Map<AbstractLocation, T> locations, T returnResult, T exceptionValue,
                                    boolean trackHeapLocations, T untrackedHeapLocationValue) {
        this.locals = locals;
        this.locations = locations;
        this.returnResult = returnResult;
        this.exceptionValue = exceptionValue;
        this.trackHeapLocations = trackHeapLocations;
        this.untrackedHeapLocationValue = untrackedHeapLocationValue;
    }

    /**
     * Get the abstract value for a local variable with the given value number
     * 
     * @param i
     *            variable value number
     * @return abstract value for the variable
     */
    public T getLocal(Integer i) {
        return locals.get(i);
    }

    /**
     * Set of all mapped local variables
     * 
     * @return value numbers for variables mapped to abstract values
     */
    public Set<Integer> getLocals() {
        return Collections.unmodifiableSet(locals.keySet());
    }

    /**
     * Get the abstract value for the given abstract heap location
     * 
     * @param loc
     *            location
     * @return abstract value for location
     */
    public T getLocation(AbstractLocation loc) {
        if (!trackHeapLocations) {
            return untrackedHeapLocationValue;
        }
        return locations.get(loc);
    }

    /**
     * Get the set of abstract heap locations mapped to abstract values
     * 
     * @return set of all mapped abstract heap locations
     */
    public Set<AbstractLocation> getLocations() {
        if (!trackHeapLocations) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(locations.keySet());
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
     * Get a new variable context. Replace the current abstract value for the
     * variable with the given value number.
     * 
     * @param valueNumber
     *            variable value number
     * @param val
     *            new abstract value for the variable
     * @return new variable context with the value for the variable replaced
     */
    public VarContext<T> setLocal(int valueNumber, T val) {
        if (val.equals(getLocal(valueNumber))) {
            return this;
        }
        Map<Integer, T> newLocals = new LinkedHashMap<>(locals);
        newLocals.put(valueNumber, val);
        return new VarContext<T>(newLocals, locations, returnResult, exceptionValue, trackHeapLocations,
                                        untrackedHeapLocationValue);
    }

    /**
     * Get a new variable context. Replace the current abstract value for the
     * given location.
     * 
     * @param location
     *            abstract heap location
     * @param val
     *            new abstract value for the location
     * @return new variable context with the value for the location replaced
     */
    public VarContext<T> setLocation(AbstractLocation loc, T val) {
        if (!trackHeapLocations || val.equals(getLocation(loc))) {
            return this;
        }
        Map<AbstractLocation, T> newLocations = new LinkedHashMap<>(locations);
        newLocations.put(loc, val);
        return new VarContext<T>(locals, newLocations, returnResult, exceptionValue, trackHeapLocations,
                                        untrackedHeapLocationValue);
    }

    /**
     * Replace the current return result with the given abstract value
     * 
     * @param returnAbsVal
     *            new abstract value for the return item
     * @return copy of the variable context with the new return value
     */
    public VarContext<T> setReturnResult(T returnAbsVal) {
        return new VarContext<T>(locals, locations, returnAbsVal, exceptionValue, trackHeapLocations,
                                        untrackedHeapLocationValue);
    }

    /**
     * Replace the current value for the exception with the given abstract value
     * 
     * @param exceptionAbsVal
     *            new abstract value for the exception
     * @return copy of the variable context with the new exception value
     */
    public VarContext<T> setExceptionValue(T exceptionAbsVal) {
        return new VarContext<T>(locals, locations, returnResult, exceptionAbsVal, trackHeapLocations,
                                        untrackedHeapLocationValue);
    }

    @Override
    public boolean leq(VarContext<T> that) {
        if (that == null) {
            return false;
        }

        for (Integer i : this.getLocals()) {
            T thatAbsVal = that.getLocal(i);
            if (thatAbsVal == null || !this.getLocal(i).leq(thatAbsVal)) {
                return false;
            }
        }

        if (this.trackHeapLocations != that.trackHeapLocations) {
            throw new RuntimeException(
                                            "Inconsistent trackHeapLocations. Should be same for all VarContexts in an analysis");
        }

        if (this.trackHeapLocations) {
            for (AbstractLocation l : getLocations()) {
                T thatAbsVal = that.locations.get(l);
                if (thatAbsVal == null || !this.locations.get(l).leq(thatAbsVal)) {
                    return false;
                }
            }
        }

        if (this.returnResult != null && (that.returnResult == null || !this.returnResult.leq(that.returnResult))) {
            return false;
        }

        if (this.exceptionValue != null
                                        && (that.exceptionValue == null || !this.exceptionValue
                                                                        .leq(that.exceptionValue))) {
            return false;
        }

        return true;
    }

    @Override
    public boolean isBottom() {
        return getLocals().isEmpty() && getLocations().isEmpty() && returnResult == null && exceptionValue == null;
    }

    @Override
    public VarContext<T> join(VarContext<T> that) {
        return join(this, that);
    }

    /**
     * Join the given set of variable contexts
     * 
     * @param contexts
     *            variable contexts to join
     * @return least upper bound of all the input items
     */
    public static <T extends AbstractValue<T>> VarContext<T> join(Set<VarContext<T>> contexts) {
        VarContext<T> output = null;
        for (VarContext<T> vc : contexts) {
            output = join(vc, output);
        }
        return output;
    }

    /**
     * Join the given variable contexts
     * 
     * @param c1
     *            first variable context
     * @param c2
     *            second variable context
     * @return least upper bound of the input contexts
     */
    public static <T extends AbstractValue<T>> VarContext<T> join(VarContext<T> c1, VarContext<T> c2) {
        if (c2 == null) {
            return c1;
        }
        if (c1 == null) {
            return c2;
        }

        Map<Integer, T> newLocals = new LinkedHashMap<>();
        Set<Integer> allLocals = new LinkedHashSet<>(c1.getLocals());
        allLocals.addAll(c2.getLocals());
        for (Integer i : allLocals) {
            T joined = c1.getLocal(i).join(c2.getLocal(i));
            if (joined == null) {
                throw new RuntimeException("Null AbsVal when joining: " + c1.getLocal(i) + " and " + c2.getLocal(i));
            }
            newLocals.put(i, joined);
        }

        if (c1.trackHeapLocations != c2.trackHeapLocations) {
            throw new RuntimeException(
                                            "Inconsistent trackHeapLocations. Should be same for all VarContexts in an analysis");
        }

        Map<AbstractLocation, T> newLocations = null;
        if (c1.trackHeapLocations) {
            newLocations = new LinkedHashMap<>();
            Set<AbstractLocation> allLocations = new LinkedHashSet<>(c1.getLocations());
            allLocations.addAll(c2.getLocations());
            for (AbstractLocation loc : allLocations) {
                T joined = c1.getLocation(loc).join(c2.getLocation(loc));
                if (joined == null) {
                    throw new RuntimeException("Null AbsVal when joining: " + c1.getLocation(loc) + " and "
                                                    + c2.getLocation(loc));
                }
                newLocations.put(loc, joined);
            }
        }

        T newReturnResult = c1.getReturnResult() == null ? c2.getReturnResult() : c1.getReturnResult().join(
                                        c2.getReturnResult());
        T newExceptionValue = c1.getException() == null ? c2.getException() : c1.getException().join(c2.getException());

        return new VarContext<T>(newLocals, newLocations, newReturnResult, newExceptionValue, c1.trackHeapLocations,
                                        c1.untrackedHeapLocationValue);
    }

    /**
     * Get a copy of the variable context with an empty set of locals and null
     * return and exception abstract values
     * 
     * @return variable context with no locals and no exits
     */
    public VarContext<T> clearLocalsAndExits() {
        return new VarContext<T>(new LinkedHashMap<Integer, T>(), locations, null, null, trackHeapLocations,
                                        untrackedHeapLocationValue);
    }
    
    @Override
    public String toString() {
        return "LOCALS: " + locals + " LOCATIONS: " + locations + " RET: " + returnResult + " EX: " + exceptionValue;
    }
}
