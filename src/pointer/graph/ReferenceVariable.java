package pointer.graph;

import com.ibm.wala.types.TypeReference;

/**
 * A ReferenceVariable represents either an allocation site (@see AllocSiteNode)
 * or a LocalNode (@see LocalNode, meaning the result of a local variable or
 * expression). A ReferenceVariableReplica is a ReferenceVariable with a
 * context.
 */
public abstract class ReferenceVariable {
    private final int id;
    private final String debugString;
    private final TypeReference expectedType;

    private static int count;

    public ReferenceVariable(String debugString, TypeReference expectedType) {
        assert (!expectedType.isPrimitiveType());
        this.id = ++count;
        this.debugString = debugString;
        this.expectedType = expectedType;
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
        return System.identityHashCode(id);
    }

    /**
     * Is this graph base node a singleton? That is, should there be only a
     * single ReferenceVariableReplica for it? This should return true for
     * reference variables that represent e.g., static fields. Because there is
     * only one location represented by the static field, there should not be
     * multiple replicas of the reference variable that represents the static
     * field.
     */
    public boolean isStatic() {
        return false;
    }

    public TypeReference getExpectedType() {
        return expectedType;
    }

}
