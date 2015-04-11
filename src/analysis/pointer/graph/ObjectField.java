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
     * Name of the declaring class
     */
    private final IClass declaringClass;

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
    public ObjectField(InstanceKey receiver, IClass declaringClass, String fieldName, IClass expectedType) {
        assert receiver != null;
        assert fieldName != null;
        assert expectedType != null : "No class for " + fieldName + " on " + receiver;
        this.receiver = receiver;
        this.fieldName = fieldName;
        this.declaringClass = declaringClass;
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
        this(receiver,
             ifield.getDeclaringClass(),
             ifield.getName().toString(),
             AnalysisUtil.getClassHierarchy().lookupClass(ifield.getFieldTypeReference()));
    }

    @Override
    public TypeReference getExpectedType() {
        return expectedType.getReference();
    }

    public int computeHashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.declaringClass == null) ? 0 : this.declaringClass.hashCode());
        result = prime * result + ((this.fieldName == null) ? 0 : this.fieldName.hashCode());
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
        if (this.declaringClass == null) {
            if (other.declaringClass != null) {
                return false;
            }
        }
        else if (!this.declaringClass.equals(other.declaringClass)) {
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
