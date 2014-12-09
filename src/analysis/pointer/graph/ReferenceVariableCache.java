package analysis.pointer.graph;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.pointer.duplicates.RemoveDuplicateStatements.VariableIndex;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.ReferenceVariableFactory.ArrayContentsKey;
import analysis.pointer.registrar.ReferenceVariableFactory.ImplicitThrowKey;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.types.TypeReference;

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
     * Map from the key for inner array of a multi-dimensional array to the corresponding reference variable
     */
    private final Map<ArrayContentsKey, ReferenceVariable> arrayContentsTemps;
    /**
     * Map from generated exception key to the corresponding reference variable
     */
    private final Map<ImplicitThrowKey, ReferenceVariable> implicitThrows;
    /**
     * Map from static field to the corresponding reference variable
     */
    private final Map<IField, ReferenceVariable> staticFields;
    /**
     * Map from method signature to nodes representing formals and returns
     */
    private final ConcurrentMap<IMethod, MethodSummaryNodes> methods;

    /**
     * Create a cache of reference variables with the given map
     *
     * @param locals reference variables for local variables
     * @param replacementMap Map from method to index mapping replaced variable to their replacements
     * @param staticFields
     * @param implicitThrows
     * @param arrayContentsTemps
     */
    public ReferenceVariableCache(Map<OrderedPair<Integer, IMethod>, ReferenceVariable> locals,
        Map<IMethod, VariableIndex> replacementMap, Map<ArrayContentsKey, ReferenceVariable> arrayContentsTemps,
        Map<ImplicitThrowKey, ReferenceVariable> implicitThrows, Map<IField, ReferenceVariable> staticFields,
        ConcurrentMap<IMethod, MethodSummaryNodes> methods) {

        this.locals = locals;
        this.replacementMap = replacementMap;
        this.arrayContentsTemps = arrayContentsTemps;
        this.implicitThrows = implicitThrows;
        this.staticFields = staticFields;
        this.methods = methods;
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

    /**
     * Get the reference variable for an implicitly thrown exception/error.
     *
     * @param type
     *            type of exception being thrown
     * @param basicBlockID
     *            ID number of the basic block throwing the exception
     * @param method
     *            Method in which the exception is thrown
     * @return reference variable for an implicit throwable
     */
    public ReferenceVariable getImplicitExceptionNode(TypeReference type,
            int basicBlockID, IMethod method) {
        return implicitThrows.get(new ImplicitThrowKey(type, basicBlockID, method));
    }

    /**
     * Get the reference variable for the given static field
     *
     * @param field field to get the node for
     * @return reference variable for the static field
     */
    public ReferenceVariable getStaticField(IField field) {
        assert field.isStatic();
        return staticFields.get(field);
    }

    /**
     * Get the reference variable for an inner array of a multidimensional array.
     *
     * @param dim Dimension (counted from the outside in) e.g. 1 is the contents of the actual declared
     *            multi-dimensional array
     * @param pc program counter for allocation instruction of the new array
     * @param method Method containing the instruction
     */
    public ReferenceVariable getInnerArray(int dim, int pc, IMethod method) {
        return arrayContentsTemps.get(new ArrayContentsKey(dim, pc, method));
    }

    /**
     * Get the method summary nodes for the given method
     *
     * @param method method to get summary nodes for
     */
    public MethodSummaryNodes getMethodSummary(IMethod method) {
        MethodSummaryNodes msn = this.methods.get(method);
        return msn;
    }
}
