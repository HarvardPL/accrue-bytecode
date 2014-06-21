package analysis.pointer.graph;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

/**
 * Point-to graph node representing a non-static field of an object
 */
public class ObjectField implements PointsToGraphNode {

    /**
     * Heap context for the receiver of the field
     */
    private final InstanceKey receiver;

    // Either FieldReference is non-null, xor both fieldName and expectedType are non-null
    /**
     * FieldReference, if one exists
     */
    private final FieldReference fieldReference;
    /**
     * Name of the field (null if fieldReference is non-null)
     */
    private final String fieldName;
    /**
     * Type of the field (null if fieldReference is non-null)
     */
    private final TypeReference expectedType;
    /**
     * Hash code computed once
     */
    private final int memoizedHashCode;

    /**
     * Points-to graph node representing a non-static field of an object
     * 
     * @param receiver
     *            heap context for the receiver
     * @param fieldName
     *            name of the field
     * @param expectedType
     *            type of the field
     */
    public ObjectField(InstanceKey receiver, String fieldName, TypeReference expectedType) {
        assert receiver != null;
        assert fieldName != null;
        assert expectedType != null;
        this.receiver = receiver;
        this.fieldName = fieldName;
        this.expectedType = expectedType;
        this.fieldReference = null;
        this.memoizedHashCode = computeHashCode();
    }

    /**
     * Points-to graph node representing a non-static field of an object
     * 
     * @param receiver
     *            heap context for the receiver
     * @param fieldReference
     *            the field
     */
    public ObjectField(InstanceKey receiver, FieldReference fieldReference) {
        assert receiver != null;
        assert fieldReference != null;
        this.receiver = receiver;
        this.fieldName = null;
        this.expectedType = null;
        this.fieldReference = fieldReference;
        this.memoizedHashCode = computeHashCode();
    }

    @Override
    public TypeReference getExpectedType() {
        return fieldReference == null ? expectedType : fieldReference.getFieldType();
    }

    public int computeHashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((expectedType == null) ? 0 : expectedType.hashCode());
        result = prime * result + ((fieldName == null) ? 0 : fieldName.hashCode());
        result = prime * result + ((fieldReference == null) ? 0 : fieldReference.hashCode());
        result = prime * result + memoizedHashCode;
        result = prime * result + ((receiver == null) ? 0 : receiver.hashCode());
        return result;
    }

    @Override
    public int hashCode() {
        return this.memoizedHashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof ObjectField)) {
            return false;
        }
        ObjectField other = (ObjectField) obj;
        if (expectedType == null) {
            if (other.expectedType != null) {
                return false;
            }
        }
        else if (!expectedType.equals(other.expectedType)) {
            return false;
        }
        if (fieldName == null) {
            if (other.fieldName != null) {
                return false;
            }
        }
        else if (!fieldName.equals(other.fieldName)) {
            return false;
        }
        if (fieldReference == null) {
            if (other.fieldReference != null) {
                return false;
            }
        }
        else if (!fieldReference.equals(other.fieldReference)) {
            return false;
        }
        if (!receiver.equals(other.receiver)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "{" + receiver() + "}." + (fieldReference == null ? fieldName : fieldReference.getName().toString());
    }

    public InstanceKey receiver() {
        return this.receiver;
    }

    public FieldReference fieldReference() {
        return this.fieldReference;
    }

    public Object fieldName() {
        return this.fieldReference == null ? this.fieldName : this.fieldReference.getName().toString();
    }

    public Object expectedType() {
        return this.fieldReference == null ? this.expectedType : this.fieldReference.getFieldType();
    }
}
