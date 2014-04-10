package analysis.dataflow.interprocedural;

import java.util.Map;
import java.util.Set;

import analysis.dataflow.AbstractValue;

public class VarContext<T extends AbstractValue<T>> implements AbstractValue<VarContext<T>> {

    private final Map<Integer, T> locals;
    private final Map<AbstractLocation, T> locations;
    private final T returnResult;

    public VarContext(Map<Integer, T> locals, Map<AbstractLocation, T> locations, T returnResult) {
        this.locals = locals;
        this.locations = locations;
        this.returnResult = returnResult;
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
     * Get the abstract value for the return result (if any)
     * 
     * @return
     */
    public T getReturnResult() {
        return returnResult;
    }

    public VarContext<T> recordLocal(Integer s, T val) {
        // TODO record Local
        return null;
    }

    public VarContext<T> recordLocation(AbstractLocation loc, T val) {
        // TODO record Location
        return null;
    }
    
    public VarContext<T> recordLocations(Set<AbstractLocation> locs, T val) {
        // TODO record Location
        return null;
    }

    public VarContext<T> setReturnResult(T returnAbsVal) {
        // TODO update return val
        return null;
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
    public VarContext<T> upperBound(VarContext<T> that) {
        // TODO Auto-generated method stub
        return null;
    }
}
