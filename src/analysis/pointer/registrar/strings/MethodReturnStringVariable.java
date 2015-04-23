package analysis.pointer.registrar.strings;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.types.TypeReference;

public class MethodReturnStringVariable implements StringVariable {
    private final IMethod method;
    private final TypeReference type;

    public static StringVariable makeString(IMethod method) {
        return new MethodReturnStringVariable(method, TypeReference.JavaLangString);
    }

    public static StringVariable makeStringBuilder(IMethod method) {
        return new MethodReturnStringVariable(method, TypeReference.JavaLangStringBuilder);
    }

    private MethodReturnStringVariable(IMethod method, TypeReference type) {
        this.method = method;
        this.type = type;
    }

    @Override
    public TypeReference getExpectedType() {
        return this.type;
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

}
