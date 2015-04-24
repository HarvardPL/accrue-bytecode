package analysis.pointer.registrar;

import java.util.LinkedHashMap;
import java.util.Map;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.dataflow.interprocedural.ExitType;
import analysis.pointer.duplicates.RemoveDuplicateStatements.VariableIndex;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.statements.StatementFactory;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

/**
 * Factory for creating unique reference variables for locals, static fields, and method summaries.
 * <p>
 * If assertions are turned on then duplication of nodes will throw an AssertionError where appropriate.
 */
public class ReferenceVariableFactory {

    /**
     * Points-to graph nodes for local variables representing the contents of the inner dimensions of multi-dimensional
     * arrays
     */
    private final Map<ArrayContentsKey, ReferenceVariable> arrayContentsTemps =
            new LinkedHashMap<>();
    /**
     * Points-to graph nodes for implicit exceptions and errors
     */
    private final Map<ImplicitThrowKey, ReferenceVariable> implicitThrows =
            new LinkedHashMap<>();
    /**
     * Points-to graph nodes for static fields
     */
    private final Map<IField, ReferenceVariable> staticFields =
            new LinkedHashMap<>();
    /**
     * Nodes for local variables
     */
    private final Map<OrderedPair<Integer, IMethod>, ReferenceVariable> locals =
            new LinkedHashMap<>();
    /**
     * Nodes for singleton exceptions if they are created, there can be only one per type. The points-to analysis will
     * be less precise, but the points-to graph will be smaller and the points-to analysis faster. The creation is
     * governed by a flag in {@link StatementRegistrar}
     */
    private final Map<TypeReference, ReferenceVariable> singletons =
            new LinkedHashMap<>();

    /**
     * One node per type for java.lang.Class
     */
    private final Map<TypeReference, ReferenceVariable> classSingletons = new LinkedHashMap<>();

    /**
     * Get the reference variable for the given local in the given method. The local should not have a primitive type or
     * null. Create a reference variable if one does not already exist
     *
     * @param local
     *            local ID, the type of this should not be primitive or null
     * @param method
     *            method
     * @param pp
     *            Pretty printer used to get a name for the local (default name will be used if null)
     *
     * @return reference variable for the local
     */
    @SuppressWarnings("synthetic-access")
    protected ReferenceVariable getOrCreateLocal(int local, TypeReference type,
            IMethod method, PrettyPrinter pp) {
        assert !type.isPrimitiveType() : "No reference variables for primitives: "
                + PrettyPrinter.typeString(type);
        assert !(type == TypeReference.Null) : "Null literal don't have reference variables";
        OrderedPair<Integer, IMethod> key = new OrderedPair<>(local, method);
        ReferenceVariable rv = locals.get(key);
        if (rv == null) {
            String name;
            if (pp != null) {
                name = pp.valString(local) + "-" + method.getName();
            }
            else {
                name =
                        PrettyPrinter.getCanonical("v" + local + "-"
                                + method.getName());
            }
            rv = new ReferenceVariable(name, type, false);
            locals.put(key, rv);
        }
        return rv;
    }

    /**
     * Create the reference variable for an inner array of a multidimensional array. This should only be called once for
     * any given arguments.
     *
     *
     * @param dim
     *            Dimension (counted from the outside in) e.g. 1 is the contents of the actual declared
     *            multi-dimensional array
     * @param pc
     *            program counter for allocation instruction of the new array
     * @param method
     *            Method containing the instruction
     */
    @SuppressWarnings("synthetic-access")
    protected ReferenceVariable createInnerArray(int dim, int pc,
            TypeReference type, IMethod method) {
        ReferenceVariable local =
                new ReferenceVariable(PointsToGraph.ARRAY_CONTENTS + dim,
                                      type,
                                      false);
        // These should only be created once assert that this is true
        assert arrayContentsTemps.put(new ArrayContentsKey(dim, pc, method),
                                      local) == null;
        return local;
    }

    /**
     * Create a reference variable for an implicitly thrown exception/error. This should only be called once for any
     * given arguments.
     *
     * @param type
     *            type of exception being thrown
     * @param basicBlockID
     *            ID number of the basic block throwing the exception
     * @param method
     *            Method in which the exception is thrown
     * @return reference variable for an implicit throwable
     */
    @SuppressWarnings("synthetic-access")
    protected ReferenceVariable createImplicitExceptionNode(TypeReference type,
            int basicBlockID, IMethod method) {
        ReferenceVariable rv = new ReferenceVariable(PrettyPrinter.typeString(type), type, false);
        // These should only be created once assert that this is true
        ReferenceVariable prev = implicitThrows.put(new ImplicitThrowKey(type, basicBlockID, method), rv);
        assert prev == null;
        return rv;
    }

    /**
     * Create a singleton node for a type. This can be used to decrease the size of the points-to graph and the run-time
     * of the pointer analysis at the cost of less precision. This should only be called once for any given type.
     *
     * @param type type of the objects pointed to by the singleton node
     * @return singleton reference variable for the given type
     */
    @SuppressWarnings("synthetic-access")
    protected ReferenceVariable createSingletonReferenceVariable(TypeReference type) {
        ReferenceVariable rv = new ReferenceVariable(PrettyPrinter.typeString(type) + "(SINGLETON)", type, true);
        // These should only be created once assert that this is true
        assert singletons.put(type, rv) == null;
        return rv;
    }

    /**
     * Create a node for java.lang.Class per type.
     *
     * @param type type of the generic argument to java.lang.Class
     * @return singleton reference variable for the given type
     */
    @SuppressWarnings("synthetic-access")
    protected ReferenceVariable createClassReferenceVariable(TypeReference type) {
        ReferenceVariable rv = new ReferenceVariable("java.lang.Class<" + PrettyPrinter.typeString(type) + ">",
                                                     TypeReference.JavaLangClass,
                                                     true);
        // These should only be created once assert that this is true
        assert classSingletons.put(type, rv) == null;
        return rv;
    }

    /**
     * Get a reference variable for a method exit summary node. This should only be called once for any given arguments
     *
     * @param type
     *            type of the exception or return value
     * @param method
     *            method the summary node is for
     * @param exitType
     *            whether this is for a normal return value or an exception
     *
     * @return reference variable for a return value or an exception thrown by a method
     */
    @SuppressWarnings("synthetic-access")
    protected static ReferenceVariable createMethodExit(TypeReference type,
            IMethod method, ExitType exitType) {
        ReferenceVariable rv =
                new ReferenceVariable(PrettyPrinter.methodString(method) + "-"
                        + exitType, type, false);
        return rv;
    }

    /**
     * Create a reference variable representing the local variable for an exception within a native method
     *
     * @param exType
     *            exception type
     * @param m
     *            native method
     */
    @SuppressWarnings("synthetic-access")
    protected static ReferenceVariable createNativeException(TypeReference exType,
            IMethod m) {
        assert m.isNative();
        ReferenceVariable rv =
                new ReferenceVariable("NATIVE-"
                        + PrettyPrinter.typeString(exType), exType, false);
        return rv;
    }

    /**
     * Get a reference variable for a formal parameter summary node. This should only be called once for any given
     * arguments
     *
     * @param paramNum
     *            formal parameter index (by convention the 0th argument is "this" for non-static methods)
     * @param type
     *            type of the formal
     * @param method
     *            method these are summary nodes for
     *
     * @return reference variable for a formal parameter
     */
    @SuppressWarnings("synthetic-access")
    protected static ReferenceVariable createFormal(int paramNum, TypeReference type,
            IMethod method) {
        ReferenceVariable rv =
                new ReferenceVariable(PrettyPrinter.methodString(method)
                        + "-formal(" + paramNum + ")", type, false);
        return rv;
    }

    /**
     * Get the reference variable for the given static field
     *
     * @param field
     *            field to get the node for
     * @return reference variable for the static field
     */
    @SuppressWarnings("synthetic-access")
    protected ReferenceVariable getOrCreateStaticField(FieldReference field) {
        IField f = AnalysisUtil.getClassHierarchy().resolveField(field);
        ReferenceVariable node = staticFields.get(f);
        if (node == null) {
            if (f.getFieldTypeReference().isPrimitiveType()) {
                throw new RuntimeException("Trying to create reference variable for a static field with a primitive type.");
            }

            node =
                    new ReferenceVariable(PrettyPrinter.typeString(f.getDeclaringClass())
                                                  + "."
                                                  + f.getName().toString(),
                                          f.getFieldTypeReference(),
                                          true);
            staticFields.put(f, node);
        }
        return node;
    }

    /**
     * Get a reference variable for the value field of a new String literal
     *
     * @return Reference variable for the value field of a String literal
     */
    @SuppressWarnings("synthetic-access")
    protected static ReferenceVariable createStringLitField() {
        ReferenceVariable rv =
                new ReferenceVariable(StatementFactory.STRING_LIT_FIELD_DESC,
                                      AnalysisUtil.STRING_VALUE_TYPE,
                                      false);
        return rv;
    }

    /**
     * Get the mapping from local variable to unique reference variable
     *
     * @return Cache of reference variables for each local variable
     */
    public ReferenceVariableCache getRvCache(Map<IMethod, VariableIndex> replacementMap,
                                             Map<IMethod, MethodSummaryNodes> methods) {
        return new ReferenceVariableCache(locals, replacementMap, arrayContentsTemps, implicitThrows, staticFields, methods);
    }

    /**
     * Key uniquely identifying an implicitly thrown exception or error
     */
    public static class ImplicitThrowKey {
        /**
         * Method in which the exception is thrown
         */
        private final IMethod method;
        /**
         * Number for basic block that throws the exception/error
         */
        private final int basicBlockID;
        /**
         * Type of exception/error
         */
        private final TypeReference type;
        /**
         * Compute the hashcode once
         */
        private final int memoizedHashCode;

        /**
         * Create a key that uniquely identifies an implicitly thrown exception.
         *
         * @param type
         *            type of exception being thrown
         * @param basicBlockID
         *            ID number of the basic block throwing the exception
         * @param method
         *            Method in which the exception is thrown
         */
        public ImplicitThrowKey(TypeReference type, int basicBlockID,
                IMethod method) {
            assert type != null;
            assert basicBlockID >= 0;
            assert method != null;
            this.type = type;
            this.basicBlockID = basicBlockID;
            this.method = method;
            memoizedHashCode = computeHashCode();
        }

        private int computeHashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + basicBlockID;
            result = prime * result + (method == null ? 0 : method.hashCode());
            result = prime * result + (type == null ? 0 : type.hashCode());
            return result;
        }

        @Override
        public int hashCode() {
            return memoizedHashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ImplicitThrowKey other = (ImplicitThrowKey) obj;
            if (basicBlockID != other.basicBlockID) {
                return false;
            }
            if (!method.equals(other.method)) {
                return false;
            }
            if (type != other.type) {
                return false;
            }
            return true;
        }
    }

    /**
     * Key into the array containing temporary local variables created to represent the contents of the inner dimensions
     * of multi-dimensional arrays
     */
    public static class ArrayContentsKey {
        /**
         * Dimension (counted from the outside in) e.g. 1 is the contents of the actual declared multi-dimensional array
         */
        private final int dim;
        /**
         * program counter for allocation instruction of the new array
         */
        private final int programCounter;
        /**
         * Method containing the instruction
         */
        private final IMethod method;
        /**
         * Compute the hashcode once
         */
        private final int memoizedHashCode;

        /**
         * Create a new key for the contents of the inner dimensions of multi-dimensional arrays
         *
         * @param dim
         *            Dimension (counted from the outside in) e.g. 1 is the contents of the actual declared
         *            multi-dimensional array
         * @param pc
         *            program counter for allocation instruction of the new array
         * @param method
         *            Method containing the instruction
         */
        public ArrayContentsKey(int dim, int pc, IMethod method) {
            // The 0th dimension is the entire array not the contents which is handled elsewhere (when allocating the
            // new array)
            assert dim > 0;
            assert pc >= 0;
            assert method != null;
            this.dim = dim;
            programCounter = pc;
            this.method = method;
            memoizedHashCode = computeHashCode();
        }

        public int computeHashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + dim;
            result = prime * result + (method == null ? 0 : method.hashCode());
            result = prime * result + programCounter;
            return result;
        }

        @Override
        public int hashCode() {
            return memoizedHashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ArrayContentsKey other = (ArrayContentsKey) obj;
            if (dim != other.dim) {
                return false;
            }
            if (method == null) {
                if (other.method != null) {
                    return false;
                }
            }
            else if (!method.equals(other.method)) {
                return false;
            }
            if (programCounter != other.programCounter) {
                return false;
            }
            return true;
        }
    }

    /**
     * Represents a local variable or static field.
     */
    public static class ReferenceVariable {

        /**
         * True if this node represents a static field, or other node for which only one global reference variable
         * should be created
         */
        private final boolean isSingleton;
        /**
         * String used for debugging
         */
        private final String debugString;
        /**
         * Type of the reference variable
         */
        private final TypeReference expectedType;

        /**
         * Create a new (unique) reference variable for a local variable, do not call this outside the pointer analysis.
         *
         * @param debugString String used for debugging and printing
         * @param expectedType Type of the variable this represents
         * @param isSingleton Whether this reference variable represents a static field, or other global singleton (for
         *            which only one reference variable replica will be created usually in the initial context)
         */
        private ReferenceVariable(String debugString, TypeReference expectedType, boolean isSingleton) {
            assert debugString != null;
            assert !debugString.equals("null");
            assert expectedType != null;
            assert !expectedType.isPrimitiveType();

            this.debugString = debugString;
            this.expectedType = expectedType;
            this.isSingleton = isSingleton;
        }

        @Override
        public String toString() {
            return debugString;
        }

        @Override
        public final boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public final int hashCode() {
            return System.identityHashCode(this);
        }

        /**
         * Type of the reference variable
         *
         * @return The type of the reference variable
         */
        public TypeReference getExpectedType() {
            return expectedType;
        }

        /**
         * Is this reference variable a singleton? That is, should there be only a single ReferenceVariableReplica for
         * it? This should return true for reference variables that represent e.g., static fields. Because there is only
         * one location represented by the static field, there should not be multiple replicas of the reference variable
         * that represents the static field.
         *
         * @return true if this is a static variable
         */
        public boolean isSingleton() {
            return isSingleton;
        }
    }
}
