package pointer;

import com.ibm.wala.types.TypeReference;

/**
 * Represents a local variable or static field.
 */
public class LocalNode extends ReferenceVariable {

    private final boolean isStatic;

    public LocalNode(String debugString, TypeReference expectedType, boolean isStatic) {
        super(debugString, expectedType);
        this.isStatic = isStatic;
    }

    @Override
    public boolean isStatic() {
        return this.isStatic;
    }
}
