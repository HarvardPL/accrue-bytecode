package analysis.pointer.graph;

import analysis.pointer.analyses.recency.InstanceKeyRecency;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

/**
 * Point-to graph node representing a non-static field of an object
 */
public final class ObjectField implements PointsToGraphNode {

    /**
     * Heap context for the receiver of the field
     */
    private final InstanceKeyRecency receiver;
    /**
     * Type of the class declaring the field
     */
    private final IClass declaringClass;
    /**
     * Name of the field
     */
    private final String fieldName;
    /**
     * FieldReference, if one exists for this field. (FieldReferences won't exist for "dummy" fields.
     */
    private final FieldReference fieldReference;
    /**
     * Type of the field
     */
    private final TypeReference expectedType;
    /**
     * Hash code computed once
     */
    private final int memoizedHashCode;
    /**
     * Whether to track this field flow sensitively if the receiver is a most-recent abstract object
     */
    private final boolean isFlowSensitive;

    /**
     * Points-to graph node representing a non-static field of an object
     *
     * @param receiver heap context for the receiver
     * @param declaringClass class declaring this field
     * @param fieldName name of the field
     * @param expectedType type of the field
     */
    public ObjectField(InstanceKeyRecency receiver, IClass declaringClass, String fieldName, IClass expectedType) {
        this(receiver, declaringClass, null, fieldName, expectedType.getReference());
    }

    /**
     * Points-to graph node representing a non-static field of an object
     *
     * @param receiver heap context for the receiver
     * @param ifield the field
     */
    public ObjectField(InstanceKeyRecency receiver, IField ifield) {
        this(receiver, ifield.getDeclaringClass(), ifield, ifield.getName().toString(), ifield.getFieldTypeReference());
        assert receiver != null && receiver.getConcreteType() != null : "Bad receiver, should be non null";
    }

    private ObjectField(InstanceKeyRecency receiver, IClass declaringClass, IField ifield, String fieldName,
                        TypeReference expectedType) {
        assert receiver != null;
        assert fieldName != null;
        assert expectedType != null;

        this.receiver = receiver;
        this.fieldReference = ifield != null ? ifield.getReference() : null;
        this.declaringClass = declaringClass;
        this.fieldName = fieldName;
        this.expectedType = expectedType;
        this.isFlowSensitive = fieldName != PointsToGraph.ARRAY_CONTENTS;
        memoizedHashCode = computeHashCode();
    }

    @Override
    public TypeReference getExpectedType() {
        return expectedType;
    }

    public int computeHashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.declaringClass == null) ? 0 : this.declaringClass.hashCode());
        result = prime * result + ((this.fieldName == null) ? 0 : this.fieldName.hashCode());
        result = prime * result + (this.isFlowSensitive ? 1231 : 1237);
        result = prime * result + ((this.receiver == null) ? 0 : this.receiver.hashCode());
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
        ObjectField other = (ObjectField) obj;
        if (this.isFlowSensitive != other.isFlowSensitive) {
            return false;
        }
        if (this.fieldName == null) {
            if (other.fieldName != null) {
                return false;
            }
        }
        else if (!this.fieldName.equals(other.fieldName)) {
            return false;
        }
        if (this.receiver == null) {
            if (other.receiver != null) {
                return false;
            }
        }
        else if (!this.receiver.equals(other.receiver)) {
            return false;
        }
        if (this.declaringClass == null) {
            if (other.declaringClass != null) {
                return false;
            }
        }
        else if (!this.declaringClass.equals(other.declaringClass)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "{" + receiver + "}." + fieldName;
    }

    public InstanceKeyRecency receiver() {
        return receiver;
    }

    public FieldReference fieldReference() {
        return fieldReference;
    }


    public String fieldName() {
        return fieldName;
    }

    @Override
    public boolean isFlowSensitive() {
        // Be flow sensitive for the recent objects (if we do not explicitly say otherwise e.g., for array contents).
        return this.receiver.isRecent() && this.isFlowSensitive;
    }

}
