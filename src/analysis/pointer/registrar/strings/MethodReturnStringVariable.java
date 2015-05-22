package analysis.pointer.registrar.strings;

import java.util.Collections;
import java.util.Set;

import analysis.pointer.graph.strings.StringLikeLocationReplica;
import analysis.pointer.graph.strings.StringLocationReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

public class MethodReturnStringVariable implements StringLikeVariable {
    private final IMethod method;
    private final TypeReference type;

    public static StringLikeVariable makeString(IMethod method) {
        return new MethodReturnStringVariable(method, TypeReference.JavaLangString);
    }

    public static StringLikeVariable makeObject(IMethod method) {
        return new MethodReturnStringVariable(method, TypeReference.JavaLangObject);
    }

    private MethodReturnStringVariable(IMethod method, TypeReference type) {
        this.method = method;
        this.type = type;
    }

    @Override
    public Set<StringLikeLocationReplica> getStringLocationReplicas(Context context) {
        return Collections.<StringLikeLocationReplica> singleton(StringLocationReplica.make(context, this));
    }

    @Override
    public TypeReference getExpectedType() {
        return this.type;
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

    @Override
    public String toString() {
        return "MethodReturnStringVariable [method=" + method + ", type=" + type + "]";
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

}
