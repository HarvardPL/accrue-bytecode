package analysis.pointer.graph;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

/**
 * Point-to graph node representing a non-static field of an object
 */
public final class ObjectField implements PointsToGraphNode {

    /**
     * Heap context for the receiver of the field
     */
    private final InstanceKey receiver;

    /**
     * Name of the field
     */
    private final String fieldName;
    /**
     * Type of the field
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
    public ObjectField(InstanceKey receiver, String fieldName,
            TypeReference expectedType) {
        assert receiver != null;
        assert fieldName != null;
        assert expectedType != null;
        this.receiver = receiver;
        this.fieldName = fieldName;
        this.expectedType = expectedType;
        memoizedHashCode = computeHashCode();
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
        this(receiver,
             fieldReference.getName().toString(),
             fieldReference.getFieldType());
    }

    @Override
    public TypeReference getExpectedType() {
        return expectedType;
    }

    public int computeHashCode() {
        final int prime = 31;
        int result = 1;
        result =
                prime * result
                        + (expectedType == null ? 0 : expectedType.hashCode());
        result =
                prime * result + (fieldName == null ? 0 : fieldName.hashCode());
        result = prime * result + memoizedHashCode;
        result = prime * result + (receiver == null ? 0 : receiver.hashCode());
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
        if (!receiver.equals(other.receiver)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "{" + receiver() + "}." + fieldName();
    }

    public InstanceKey receiver() {
        return receiver;
    }

    public String fieldName() {
        return fieldName;
    }

    public TypeReference expectedType() {
        return expectedType;
    }
}
