package analysis.pointer.graph;

import java.util.Map;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.IMethod;

/**
 * Cache of the unique reference variable associated with each local variable
 */
public class ReferenceVariableCache {

    /**
     * Reference variables for local variables
     */
    private final Map<OrderedPair<Integer, IMethod>, ReferenceVariable> locals;

    /**
     * Create a cache of reference variables with the given map
     * 
     * @param locals
     *            reference variables for local variables
     */
    public ReferenceVariableCache(Map<OrderedPair<Integer, IMethod>, ReferenceVariable> locals) {
        this.locals = locals;
    }

    /**
     * Get the reference variable for the given local
     * 
     * @param local
     *            variable to get the reference variable for
     * @param m
     *            method containing the local variable
     * @return unique (non-null) reference variable for the local
     */
    public ReferenceVariable getReferenceVariable(int local, IMethod m) {
        OrderedPair<Integer, IMethod> key = new OrderedPair<>(local, m);
        ReferenceVariable rv = locals.get(key);
        assert rv != null : "Missing reference variable for " + local + " in " + PrettyPrinter.methodString(m);
        return rv;
    }
}
