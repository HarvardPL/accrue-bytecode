package analysis.pointer.registrar.strings;

import java.util.Collections;
import java.util.Set;

import analysis.StringAndReflectiveUtil;
import analysis.pointer.graph.strings.StringLikeLocationReplica;
import analysis.pointer.graph.strings.StringLocationReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

public class LocalStringVariable implements StringLikeVariable {
    private final IMethod method;
    private final int varNum;
    private final TypeReference type;

    public static StringLikeVariable makeString(IMethod method, int varNum) {
        return new LocalStringVariable(method, varNum, StringAndReflectiveUtil.JavaLangStringTypeReference);
    }

    public static StringLikeVariable makeObject(IMethod method, int varNum) {
        return new LocalStringVariable(method, varNum, StringAndReflectiveUtil.JavaLangObjectTypeReference);
    }

    private LocalStringVariable(IMethod method, int varNum, TypeReference type) {
        this.method = method;
        this.varNum = varNum;
        this.type = type;
    }

    @Override
    public Set<StringLikeLocationReplica> getStringLocationReplicas(Context context) {
        return Collections.<StringLikeLocationReplica> singleton(StringLocationReplica.make(context, this));
    }

    @Override
    public TypeReference getExpectedType() {
        return type;
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

    @Override
    public boolean isStringBuilder() {
        return false;
    }

    @Override
    public boolean isString() {
        return true;
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((method == null) ? 0 : method.hashCode());
        result = prime * result + varNum;
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
        if (!(obj instanceof LocalStringVariable)) {
            return false;
        }
        LocalStringVariable other = (LocalStringVariable) obj;
        if (method == null) {
            if (other.method != null) {
                return false;
            }
        }
        else if (!method.equals(other.method)) {
            return false;
        }
        if (varNum != other.varNum) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "LSV [method=" + method.getDeclaringClass().getName().getClassName() + "." + method.getName()
                + ", varNum=" + varNum + "]";
    }

}
