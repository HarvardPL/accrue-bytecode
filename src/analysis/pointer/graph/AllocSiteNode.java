package analysis.pointer.graph;

import com.ibm.wala.classLoader.IClass;

/**
 * Represents an allocation site in the code
 */
public class AllocSiteNode extends ReferenceVariable {

    private final IClass containingClass;
    private final IClass instantiatedClass;

    public AllocSiteNode(String debugString, IClass instantiatedClass, IClass containingClass) {
        super(debugString, instantiatedClass.getReference());
        this.containingClass = containingClass;
        this.instantiatedClass = instantiatedClass;
    }

    public IClass getContainingClass() {
        return containingClass;
    }

    public IClass getInstantiatedClass() {
        return instantiatedClass;
    }
}
