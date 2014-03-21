package pointer.graph;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.TypeReference;

public class ObjectField implements PointsToGraphNode {

	private final InstanceKey receiver;
	private final String fieldName;
	private final TypeReference expectedType;
    private final TypeReference containerType;
	
	public ObjectField(InstanceKey receiver, String fieldName, TypeReference expectedType, TypeReference containerType) {
		this.receiver = receiver;
		this.fieldName = fieldName;
		this.expectedType = expectedType;
		this.containerType = containerType;
	}
	
    public ObjectField(InstanceKey receiver, String fieldName, TypeReference expectedType) {
        this(receiver, fieldName, expectedType, null);
        assert fieldName.equals(PointsToGraph.ARRAY_CONTENTS);
    }
	
	@Override
	public TypeReference getExpectedType() {
	    return expectedType;
	}

	@Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((containerType == null) ? 0 : containerType.hashCode());
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
        if (containerType == null) {
            if (other.containerType != null)
                return false;
        } else if (!containerType.equals(other.containerType))
            return false;
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
