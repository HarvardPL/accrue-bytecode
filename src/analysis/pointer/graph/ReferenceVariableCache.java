package analysis.pointer.graph;

import java.util.Map;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.pointer.duplicates.RemoveDuplicateStatements.VariableIndex;
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
     * Map from method to index mapping replaced variable to their replacements
     */
    private final Map<IMethod, VariableIndex> replacementMap;

    /**
     * Create a cache of reference variables with the given map
     * 
     * @param locals reference variables for local variables
     * @param replacementMap Map from method to index mapping replaced variable to their replacements
     */
    public ReferenceVariableCache(Map<OrderedPair<Integer, IMethod>, ReferenceVariable> locals,
                                  Map<IMethod, VariableIndex> replacementMap) {
        this.locals = locals;
        this.replacementMap = replacementMap;
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
        ReferenceVariable rvRet = replacementMap.get(m).lookup(rv);
        assert rvRet != null : "Missing replacement variable for " + local + " in " + PrettyPrinter.methodString(m);
        return rvRet;
    }
}
