package analysis.pointer.registrar.strings;

import com.ibm.wala.types.TypeReference;

public interface StringVariable {
    @Override
    public abstract String toString();

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public abstract int hashCode();

    TypeReference getExpectedType();

    boolean isSingleton();

}
