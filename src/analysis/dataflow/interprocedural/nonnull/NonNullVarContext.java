package analysis.dataflow.interprocedural.nonnull;

import java.util.Collections;
import java.util.Map;

import analysis.dataflow.util.AbstractLocation;
import analysis.dataflow.util.VarContext;

/**
 * Variable context for a Non-null data-flow analysis
 */
public class NonNullVarContext extends VarContext<NonNullAbsVal> {

    /**
     * Create a new variable context with the given values
     * 
     * @param locals
     *            values for local variables with the given value numbers
     * @param returnResult
     *            abstract value for the return result
     * @param exceptionValue
     *            abstract value for the exception thrown by the procedure
     */
    public NonNullVarContext(Map<Integer, NonNullAbsVal> locals, NonNullAbsVal returnResult,
                                    NonNullAbsVal exceptionValue) {
        super(locals, Collections.<AbstractLocation, NonNullAbsVal> emptyMap(), returnResult, exceptionValue, false,
                                        NonNullAbsVal.MAY_BE_NULL);
    }

    /**
     * Create an empty variable context
     */
    public NonNullVarContext() {
        this(Collections.<Integer, NonNullAbsVal> emptyMap(), null, null);
    }

}
