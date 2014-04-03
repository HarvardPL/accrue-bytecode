package analysis.pointer.graph;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.types.TypeReference;

/**
 * Represents an allocation site in the code
 */
public class AllocSiteNode {

    /**
     * Class allocation occurs in
     */
    private final IClass containingClass;
    /**
     * Allocated class
     */
    private final IClass instantiatedClass;

    /**
     * Unique ID, used for testing whether two {@link AllocSiteNode}s are equal
     */
    private final int id;
    /**
     * String used for printing and debugging
     */
    private final String debugString;
    /**
     * Counter for unique IDs
     */
    private static int count;

    /**
     * Represents the allocation of a new object
     * 
     * @param debugString
     *            String for printing and debugging
     * @param expectedType
     *            type of the newly allocated object
     * @param instantiatedClass
     *            class being allocated
     * @param containingClass
     *            class where allocation occurs
     */
    public AllocSiteNode(String debugString, IClass instantiatedClass, IClass containingClass) {
        this.id = ++count;
        this.debugString = debugString;
        this.containingClass = containingClass;
        this.instantiatedClass = instantiatedClass;
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

    public TypeReference getExpectedType() {
        return instantiatedClass.getReference();
    }

    public IClass getContainingClass() {
        return containingClass;
    }

    public IClass getInstantiatedClass() {
        return instantiatedClass;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        AllocSiteNode other = (AllocSiteNode) obj;
        if (id != other.id)
            return false;
        return true;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
