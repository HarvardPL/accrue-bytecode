package analysis.pointer.graph;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.TypeReference;

/**
 * Point-to graph node representing a non-static field of an object
 */
public class ObjectField implements PointsToGraphNode {

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
        this.receiver = receiver;
        this.fieldName = fieldName;
        this.expectedType = expectedType;
    }

    @Override
    public TypeReference getExpectedType() {
        return expectedType;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((expectedType == null) ? 0 : expectedType.hashCode());
        result = prime * result + ((fieldName == null) ? 0 : fieldName.hashCode());
        result = prime * result + ((receiver == null) ? 0 : receiver.hashCode());
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
        ObjectField other = (ObjectField) obj;
        if (expectedType == null) {
            if (other.expectedType != null)
                return false;
        } else if (!expectedType.equals(other.expectedType))
            return false;
        if (fieldName == null) {
            if (other.fieldName != null)
                return false;
        } else if (!fieldName.equals(other.fieldName))
            return false;
        if (receiver == null) {
            if (other.receiver != null)
                return false;
        } else if (!receiver.equals(other.receiver))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "{" + receiver + "}." + fieldName;
    }
}
