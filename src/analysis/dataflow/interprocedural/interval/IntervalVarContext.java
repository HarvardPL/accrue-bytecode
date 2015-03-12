package analysis.dataflow.interprocedural.interval;

import java.util.Collections;
import java.util.Map;

import analysis.dataflow.util.AbstractLocation;
import analysis.dataflow.util.VarContext;

public class IntervalVarContext extends VarContext<IntervalAbsVal> {

    /**
     * Create a new variable context with the given values
     *
     * @param locals values for local variables with the given value numbers
     * @param returnResult abstract value for the return result
     * @param exceptionValue abstract value for the exception thrown by the procedure
     * @param trackHeapLocations should heap locations be tracked (flow-sensitively) in the var context
     */
    private IntervalVarContext(Map<Integer, IntervalAbsVal> locals, IntervalAbsVal returnResult,
                              IntervalAbsVal exceptionValue, boolean trackHeapLocations) {
        super(locals,
              Collections.<AbstractLocation, IntervalAbsVal> emptyMap(),
              returnResult,
              exceptionValue,
              trackHeapLocations,
              IntervalAbsVal.TOP_ELEMENT);
    }

    /**
     * Create an empty variable context
     *
     * @param returnResult abstract value for the return result
     * @param exceptionValue abstract value for the exception thrown by the procedure
     * @param trackHeapLocations should heap locations be tracked (flow-sensitively) in the var context
     */
    public IntervalVarContext(IntervalAbsVal returnResult, IntervalAbsVal exceptionValue, boolean trackHeapLocations) {
        this(Collections.<Integer, IntervalAbsVal> emptyMap(), returnResult, exceptionValue, trackHeapLocations);
    }


}
