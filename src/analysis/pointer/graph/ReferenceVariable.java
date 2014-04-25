package analysis.pointer.graph;

import com.ibm.wala.types.TypeReference;

/**
 * Represents a local variable or static field.
 */
public class ReferenceVariable {

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
    /**
     * counter for unique IDs
     */
    private static int count;

    /**
     * Create a new (unique) reference variable, do not call this outside the
     * pointer analysis
     * 
     * @param debugString
     *            String used for debugging and printing
     * @param expectedType
     *            Type of the variable this represents
     * @param isStatic
     *            True if this node represents a static field
     */
    public ReferenceVariable(String debugString, TypeReference expectedType, boolean isStatic) {
        assert (!expectedType.isPrimitiveType());
        this.id = ++count;
        this.debugString = debugString;
        this.expectedType = expectedType;
        this.isSingleton = isStatic;
        if (debugString == null) {
            throw new RuntimeException("Need debug string");
        }
        if ("null".equals(debugString)) {
            throw new RuntimeException("Weird debug string");
        }
    }

    @Override
    public String toString() {
        return debugString;
    }

    @Override
    public boolean equals(Object obj) {
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
    public int hashCode() {
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
