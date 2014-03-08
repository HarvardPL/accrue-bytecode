package pointer;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.types.TypeReference;

/**
 * Represents an allocation site in the code
 */
public class AllocSiteNode extends ReferenceVariable {

    private final IClass containingClass;

    public AllocSiteNode(String debugString, TypeReference instantiatedType, IClass containingClass) {
        super(debugString, instantiatedType);
        this.containingClass = containingClass;
    }

    public IClass getContainingClass() {
        return containingClass;
    }
}
