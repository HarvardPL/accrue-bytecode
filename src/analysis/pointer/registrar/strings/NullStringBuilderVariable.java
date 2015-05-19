package analysis.pointer.registrar.strings;

import java.util.Collections;
import java.util.Set;

import analysis.pointer.graph.strings.EscapedStringLocationReplica;
import analysis.pointer.graph.strings.StringLikeLocationReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

public class NullStringBuilderVariable implements StringLikeVariable {

    private static final NullStringBuilderVariable NULLVAR = new NullStringBuilderVariable();

    @Override
    public TypeReference getExpectedType() {
        return TypeReference.JavaLangStringBuilder;
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

    @Override
    public boolean isStringBuilder() {
        return true;
    }

    @Override
    public boolean isString() {
        return false;
    }

    @Override
    public boolean isNull() {
        return true;
    }

    @Override
    public Set<StringLikeLocationReplica> getStringLocationReplicas(Context context) {
        return Collections.singleton(EscapedStringLocationReplica.make());
    }

    public static StringLikeVariable make(IMethod method, int varNum) {
        return NULLVAR;
    }

}
