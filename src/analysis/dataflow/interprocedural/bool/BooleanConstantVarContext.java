package analysis.dataflow.interprocedural.bool;

import java.util.LinkedHashMap;

import analysis.dataflow.util.AbstractLocation;
import analysis.dataflow.util.VarContext;

/**
 * Variable context holding abstract boolean values
 */
public class BooleanConstantVarContext extends VarContext<BooleanAbsVal> {

    /**
     * Create an empty variable context
     */
    protected BooleanConstantVarContext() {
        super(new LinkedHashMap<Integer, BooleanAbsVal>(), new LinkedHashMap<AbstractLocation, BooleanAbsVal>(), null,
                                        null, true, BooleanAbsVal.UNKNOWN);
    }

}
