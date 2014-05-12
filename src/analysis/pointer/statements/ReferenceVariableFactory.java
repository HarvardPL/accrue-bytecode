package analysis.pointer.statements;

import java.util.LinkedHashMap;
import java.util.Map;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.ExitType;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

public class ReferenceVariableFactory {
    
    /**
     * Points-to graph nodes for local variables representing the contents of
     * the inner dimensions of multi-dimensional arrays
     */
    private static final Map<ArrayContentsKey, ReferenceVariable> arrayContentsTemps = new LinkedHashMap<>();
    /**
     * Points-to graph nodes for implicit exceptions and errors
     */
    private static final Map<ImplicitThrowKey, ReferenceVariable> implicitThrows = new LinkedHashMap<>();
    /**
     * Points-to graph nodes for static fields
     */
    private static final Map<IField, ReferenceVariable> staticFields = new LinkedHashMap<>();
    /**
     * Nodes for local variables
     */
    private static final Map<LocalKey, ReferenceVariable> locals = new LinkedHashMap<>();
    /**
     * Nodes for method exit
     */
    private static final Map<MethodSummaryKey, ReferenceVariable> methodSummaries = new LinkedHashMap<>();

    /**
     * Get the reference variable for the given local in the given IR. The local
     * should not have a primitive type or null. Create a reference variable if
     * one does not already exist
     * 
     * @param local
     *            local ID, the type of this should not be primitive or null
     * @param ir
     *            method intermediate representation
     * @return points-to graph node for the local
     */
    @SuppressWarnings("synthetic-access")
    protected static ReferenceVariable getOrCreateLocal(int local, IR ir) {
        assert !TypeRepository.getType(local, ir).isPrimitiveType() : "No local nodes for primitives: "
                                        + PrettyPrinter.parseType(TypeRepository.getType(local, ir));
        LocalKey key = new LocalKey(local, ir);
        ReferenceVariable node = locals.get(key);
        if (node == null) {
            TypeReference type = TypeRepository.getType(local, ir);
            node = new ReferenceVariable(PrettyPrinter.valString(local, ir), type, ir);
            locals.put(key, node);
        }
        return node;
    }

    /**
     * Get the reference variable for the given local in the given IR. The local
     * should not have a primitive type or null.
     * 
     * @param local
     *            local ID, the type of this should not be primitive or null
     * @param ir
     *            method intermediate representation
     * @return reference variable for the local
     */
    public static ReferenceVariable getLocal(int local, IR ir) {
        assert !TypeRepository.getType(local, ir).isPrimitiveType() : "No local nodes for primitives: "
                                        + PrettyPrinter.parseType(TypeRepository.getType(local, ir));
        LocalKey key = new LocalKey(local, ir);
        assert locals.containsKey(key);
        return locals.get(key);
    }

    @SuppressWarnings("synthetic-access")
    protected static ReferenceVariable getOrCreateLocalForArrayContents(int dim, TypeReference type, SSANewInstruction i, IR ir) {
        // Need to create one for the inner and use it to get the outer or it
        // doesn't work
        ArrayContentsKey key = new ArrayContentsKey(dim, i, ir);
        ReferenceVariable local = arrayContentsTemps.get(key);
        if (local == null) {
            local = new ReferenceVariable(PointsToGraph.ARRAY_CONTENTS + dim, type, ir);
            arrayContentsTemps.put(key, local);
        }
        return local;
    }

    /**
     * Get a reference variable for an implicitly thrown exception/error, create
     * it if it does not already exist
     * 
     * @param type
     *            type of the exception
     * @param i
     *            instruction that throws
     * @param ir
     *            method containing the instruction that throws
     * @return reference variable for an implicit throwable
     */
    @SuppressWarnings("synthetic-access")
    protected static ReferenceVariable getOrCreateImplicitExceptionNode(TypeReference type, SSAInstruction i, IR ir) {
        ImplicitThrowKey key = new ImplicitThrowKey(type, ir, i);
        ReferenceVariable node = implicitThrows.get(key);
        if (node == null) {
            node = new ReferenceVariable("IMPLICIT-" + PrettyPrinter.parseType(type), type, ir);
            implicitThrows.put(key, node);
        }
        return node;
    }

    /**
     * Get a reference variable for a method exit summary node, create
     * it if it does not already exist
     * 
     * @param type
     *            type of the exception
     * @param ir
     *            method containing the instruction that throws
     * @return reference variable for an implicit throwable
     */
    @SuppressWarnings("synthetic-access")
    protected static ReferenceVariable getOrCreateMethodExitNode(TypeReference type, IR ir, ExitType exitType) {
        MethodSummaryKey key = new MethodSummaryKey(type, ir, exitType);
        ReferenceVariable node = methodSummaries.get(key);
        if (node == null) {
            String debugString = PrettyPrinter.parseMethod(ir.getMethod()) + "-" + exitType;
            node = new ReferenceVariable(debugString, type, ir);
            methodSummaries.put(key, node);
        }
        return node;
    }
    
    /**
     * Get a reference variable for an implicitly thrown exception/error
     * 
     * @param type
     *            type of the exception
     * @param i
     *            instruction that throws
     * @param ir
     *            method containing the instruction that throws
     * @return reference variable for an implicit throwable
     */
    public static ReferenceVariable getImplicitExceptionNode(TypeReference type, SSAInstruction i, IR ir) {
        ImplicitThrowKey key = new ImplicitThrowKey(type, ir, i);
        assert implicitThrows.containsKey(key);
        return implicitThrows.get(key);
    }

    /**
     * Get the reference variable for the given static field
     * 
     * @param field
     *            field to get the node for
     * @return reference variable for the static field
     */
    @SuppressWarnings("synthetic-access")
    protected static ReferenceVariable getOrCreateNodeForStaticField(FieldReference field, IClassHierarchy cha) {
        IField f = cha.resolveField(field);
        ReferenceVariable node = staticFields.get(f);
        if (node == null) {
            if (f.getFieldTypeReference().isPrimitiveType()) {
                throw new RuntimeException(
                                                "Trying to create reference variable for a static field with a primitive type.");
            }

            node = new ReferenceVariable(PrettyPrinter.parseType(f.getDeclaringClass().getReference()) + "."
                                            + f.getName().toString(), f.getFieldTypeReference());
            staticFields.put(f, node);
        }
        return node;
    }

    /**
     * Get the reference variable for the given static field
     * 
     * @param field
     *            field to get the node for
     * @return reference variable for the static field
     */
    public static ReferenceVariable getNodeForStaticField(FieldReference field, IClassHierarchy cha) {
        IField f = cha.resolveField(field);
        assert staticFields.containsKey(f);
        return staticFields.get(f);
    }
    
    /**
     * Key uniquely identifying an implicitly thrown exception or error
     */
    private static class ImplicitThrowKey {
        /**
         * Containing method
         */
        private final IR ir;
        /**
         * Instruction that throws the exception/error
         */
        private final SSAInstruction i;
        /**
         * Type of exception/error
         */
        private final TypeReference type;

        /**
         * Create a new key
         * 
         * @param type
         *            Type of exception/error
         * @param ir
         *            Containing method
         * @param i
         *            Instruction that throws the exception/error
         */
        public ImplicitThrowKey(TypeReference type, IR ir, SSAInstruction i) {
            this.type = type;
            this.ir = ir;
            this.i = i;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((i == null) ? 0 : i.hashCode());
            result = prime * result + ((ir == null) ? 0 : ir.hashCode());
            result = prime * result + ((type == null) ? 0 : type.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ImplicitThrowKey other = (ImplicitThrowKey) obj;
            if (i == null) {
                if (other.i != null)
                    return false;
            } else if (!i.equals(other.i))
                return false;
            if (ir == null) {
                if (other.ir != null)
                    return false;
            } else if (!ir.equals(other.ir))
                return false;
            if (type == null) {
                if (other.type != null)
                    return false;
            } else if (!type.equals(other.type))
                return false;
            return true;
        }
    }
    
    /**
     * Key uniquely identifying a method summary node
     */
    private static class MethodSummaryKey {
        /**
         * Containing method
         */
        private final IR ir;
        /**
         * Type of exception/error
         */
        private final TypeReference type;

        private final ExitType exitType;

        /**
         * Create a new key
         * 
         * @param type
         *            Type of exception/error
         * @param ir
         *            Containing method
         * @param exitType
         *            normal or exceptional termination
         */
        public MethodSummaryKey(TypeReference type, IR ir, ExitType exitType) {
            this.type = type;
            this.ir = ir;
            this.exitType = exitType;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((exitType == null) ? 0 : exitType.hashCode());
            result = prime * result + ((ir == null) ? 0 : ir.hashCode());
            result = prime * result + ((type == null) ? 0 : type.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            MethodSummaryKey other = (MethodSummaryKey) obj;
            if (exitType == null) {
                if (other.exitType != null)
                    return false;
            } else if (!exitType.equals(other.exitType))
                return false;
            if (ir == null) {
                if (other.ir != null)
                    return false;
            } else if (!ir.equals(other.ir))
                return false;
            if (type == null) {
                if (other.type != null)
                    return false;
            } else if (!type.equals(other.type))
                return false;
            return true;
        }
    }

    /**
     * Key uniquely identifying a local variable.
     */
    private static class LocalKey {
        /**
         * local ID
         */
        private final int value;
        /**
         * Code
         */
        private final IR ir;

        /**
         * Create a new key
         * 
         * @param value
         *            local variable ID
         * @param ir
         *            code
         */
        public LocalKey(int value, IR ir) {
            this.value = value;
            this.ir = ir;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ir.hashCode();
            result = prime * result + value;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            LocalKey other = (LocalKey) obj;
            if (ir == null) {
                if (other.ir != null)
                    return false;
            } else if (ir != other.ir)
                return false;
            if (value != other.value)
                return false;
            return true;
        }

        @Override
        public String toString() {
            return PrettyPrinter.valString(value, ir) + " in " + PrettyPrinter.parseMethod(ir.getMethod());
        }
    }

    /**
     * Key into the array containing temporary local variables created to
     * represent the contents of the inner dimensions of multi-dimensional
     * arrays
     */
    private static class ArrayContentsKey {
        /**
         * Dimension (counted from the outside in) e.g. 1 is the contents of the
         * actual declared multi-dimensional array
         */
        private final int dim;
        /**
         * instruction for new array
         */
        private final SSANewInstruction i;
        /**
         * Code containing the instruction
         */
        private final IR ir;

        /**
         * Create a new key for the contents of the inner dimensions of
         * multi-dimensional arrays
         * 
         * @param dim
         *            Dimension (counted from the outside in) e.g. 1 is the
         *            contents of the actual declared multi-dimensional array
         * @param i
         *            instruction for new array
         * @param ir
         *            Code containing the instruction
         */
        public ArrayContentsKey(int dim, SSANewInstruction i, IR ir) {
            this.dim = dim;
            this.i = i;
            this.ir = ir;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + dim;
            result = prime * result + ((i == null) ? 0 : i.hashCode());
            result = prime * result + ((ir == null) ? 0 : ir.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ArrayContentsKey other = (ArrayContentsKey) obj;
            if (dim != other.dim)
                return false;
            if (i == null) {
                if (other.i != null)
                    return false;
            } else if (!i.equals(other.i))
                return false;
            if (ir == null) {
                if (other.ir != null)
                    return false;
            } else if (!ir.equals(other.ir))
                return false;
            return true;
        }
    }
    
    /**
     * Represents a local variable or static field.
     */
    public static class ReferenceVariable {

        /**
         * True if this node represents a static field
         */
        private final boolean isSingleton;
        /**
         * Unique ID
         */
        private final int id;
        /**
         * String used for debugging
         */
        private final String debugString;
        /**
         * Type of the reference variable
         */
        private final TypeReference expectedType;
        private final IR ir;
        /**
         * counter for unique IDs
         */
        private static int count;

        /**
         * Create a new (unique) reference variable for a local variable, do not
         * call this outside the pointer analysis
         * 
         * @param debugString
         *            String used for debugging and printing
         * @param expectedType
         *            Type of the variable this represents
         * @param isStatic
         *            True if this node represents a static field
         */
        private ReferenceVariable(String debugString, TypeReference expectedType, IR ir) {
            assert (!expectedType.isPrimitiveType());
            this.id = ++count;
            this.debugString = debugString;
            this.expectedType = expectedType;
            this.isSingleton = false;
            this.ir = ir;
            if (debugString == null) {
                throw new RuntimeException("Need debug string");
            }
            if ("null".equals(debugString)) {
                throw new RuntimeException("Weird debug string");
            }
        }

        /**
         * Create a new (unique) reference variable for a static field, do not call
         * this outside the pointer analysis
         * 
         * @param debugString
         *            String used for debugging and printing
         * @param expectedType
         *            Type of the variable this represents
         * @param isStatic
         *            True if this node represents a static field
         */
        private ReferenceVariable(String debugString, TypeReference expectedType) {
            assert (!expectedType.isPrimitiveType());
            this.id = ++count;
            this.debugString = debugString;
            this.expectedType = expectedType;
            this.isSingleton = true;
            this.ir = null;
            if (debugString == null) {
                throw new RuntimeException("Need debug string");
            }
            if ("null".equals(debugString)) {
                throw new RuntimeException("Weird debug string");
            }
        }

        public IR getCode() {
            return ir;
        }

        @Override
        public String toString() {
            return debugString;
        }

        @Override
        public final boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ReferenceVariable other = (ReferenceVariable) obj;
            if (id != other.id)
                return false;
            return true;
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
         * Is this graph base node a singleton? That is, should there be only a
         * single ReferenceVariableReplica for it? This should return true for
         * reference variables that represent e.g., static fields. Because there is
         * only one location represented by the static field, there should not be
         * multiple replicas of the reference variable that represents the static
         * field.
         * 
         * @return true if this is a static variable
         */
        public boolean isSingleton() {
            return this.isSingleton;
        }
    }
}
