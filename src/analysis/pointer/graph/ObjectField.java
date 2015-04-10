package analysis.pointer.graph;

import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
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
    private final IClass expectedType;
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
    public ObjectField(InstanceKey receiver, String fieldName, IClass expectedType) {
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
     * @param ifield
     *            the field
     */
    public ObjectField(InstanceKey receiver, IField ifield) {
        this(receiver, ifield.getName().toString(), AnalysisUtil.getClassHierarchy()
                                                                .lookupClass(ifield.getFieldTypeReference()));
    }

    @Override
    public TypeReference getExpectedType() {
        return expectedType.getReference();
    }

    public int computeHashCode() {
        final int prime = 31;
        int result = 1;
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

    public String fieldName() {
        return fieldName;
    }
}
