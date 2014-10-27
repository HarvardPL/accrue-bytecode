package analysis.pointer.graph;

import analysis.pointer.analyses.recency.InstanceKeyRecency;

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
    private final InstanceKeyRecency receiver;
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
     * Is this ObjectField representing the contents of an array?
     */
    private final boolean isArrayContentsDummyField;
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
    public ObjectField(InstanceKeyRecency receiver, String fieldName,
                       TypeReference expectedType,
                       boolean isArrayContentsDummyField) {
        this(receiver, null, fieldName, expectedType, isArrayContentsDummyField);
    }

    /**
     * Points-to graph node representing a non-static field of an object
     *
     * @param receiver
     *            heap context for the receiver
     * @param fieldReference
     *            the field
     */
    public ObjectField(InstanceKeyRecency receiver, FieldReference fieldReference) {
        this(receiver, fieldReference, fieldReference.getName().toString(), fieldReference.getFieldType(), false);
        assert receiver != null && receiver.getConcreteType() != null : "Bad receiver, should be non null";
    }

    private ObjectField(InstanceKeyRecency receiver, FieldReference fieldReference, String fieldName,
                        TypeReference expectedType, boolean isArrayContentsDummyField) {
        assert receiver != null;
        assert fieldName != null;
        assert expectedType != null;
        assert fieldReference == null ? isArrayContentsDummyField : true;

        this.receiver = receiver;
        this.fieldReference = fieldReference;
        this.fieldName = fieldName;
        this.expectedType = expectedType;
        this.isArrayContentsDummyField = isArrayContentsDummyField;
        memoizedHashCode = computeHashCode();
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
        if (!receiver.equals(other.receiver)) {
            return false;
        }
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
        return true;
    }

    @Override
    public String toString() {
        return "{" + receiver() + "}." + fieldName();
    }

    public InstanceKey receiver() {
        return receiver;
    }

    public FieldReference fieldReference() {
        return fieldReference;
    }


    public String fieldName() {
        return fieldName;
    }

    public TypeReference expectedType() {
        return expectedType;
    }

    @Override
    public boolean isFlowSensitive() {
        // Be flow sensitive for the recent objects (if we are not the array contents).
        return this.receiver.isRecent() && !this.isArrayContentsDummyField;
    }

    /**
     * Return an ObjectField that is identical to this one except that it has newReceiver as the receiver.
     *
     * @param newReceiver
     * @return
     */
    public ObjectField receiver(InstanceKeyRecency newReceiver) {
        if (this.receiver == newReceiver || this.receiver.equals(newReceiver)) {
            return this;
        }
        return new ObjectField(newReceiver,
                               this.fieldReference,
                               this.fieldName,
                               this.expectedType,
                               this.isArrayContentsDummyField);
    }

}
