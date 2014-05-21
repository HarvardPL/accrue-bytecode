package analysis.pointer.graph;

import java.util.Map;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.pointer.statements.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.ssa.IR;

/**
 * Cache of the unique reference variable associated with each local variable
 */
public class ReferenceVariableCache {

    private final Map<OrderedPair<Integer, IR>, ReferenceVariable> locals;

    public ReferenceVariableCache(Map<OrderedPair<Integer, IR>, ReferenceVariable> locals) {
        this.locals = locals;
    }

    /**
     * Get the reference variable for the given local
     * 
     * @param local
     *            variable to get the reference variable for
     * @param ir
     *            code containing the local variable
     * @return unique (non-null) reference variable for the local
     */
    public ReferenceVariable getReferenceVariable(int local, IR ir) {
        OrderedPair<Integer, IR> key = new OrderedPair<>(local, ir);
        ReferenceVariable rv = locals.get(key);
        assert rv != null : "Missing reference variable for " + PrettyPrinter.valString(local, ir) + " in "
                                        + PrettyPrinter.methodString(ir.getMethod());
        return rv;
    }
}
