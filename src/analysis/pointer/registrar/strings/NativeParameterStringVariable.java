package analysis.pointer.registrar.strings;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.types.TypeReference;

public class NativeParameterStringVariable implements StringVariable {

    private final IMethod method;
    private final int parameterNum;
    private final TypeReference klass;

    public static StringVariable makeString(IMethod method, int i) {
        return new NativeParameterStringVariable(method, i, TypeReference.JavaLangString);
    }

    public static StringVariable makeStringBuilder(IMethod method, int i) {
        return new NativeParameterStringVariable(method, i, TypeReference.JavaLangStringBuilder);
    }

    private NativeParameterStringVariable(IMethod method, int i, TypeReference klass) {
        this.method = method;
        this.parameterNum = i;
        this.klass = klass;
    }

    @Override
    public TypeReference getExpectedType() {
        return this.klass;
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

    @Override
    public String toString() {
        return "NativeParameterStringVariable [method=" + method + ", parameterNum=" + parameterNum + ", klass="
                + klass + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((klass == null) ? 0 : klass.hashCode());
        result = prime * result + ((method == null) ? 0 : method.hashCode());
        result = prime * result + parameterNum;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof NativeParameterStringVariable)) {
            return false;
        }
        NativeParameterStringVariable other = (NativeParameterStringVariable) obj;
        if (klass == null) {
            if (other.klass != null) {
                return false;
            }
        }
        else if (!klass.equals(other.klass)) {
            return false;
        }
        if (method == null) {
            if (other.method != null) {
                return false;
            }
        }
        else if (!method.equals(other.method)) {
            return false;
        }
        if (parameterNum != other.parameterNum) {
            return false;
        }
        return true;
    }

}
