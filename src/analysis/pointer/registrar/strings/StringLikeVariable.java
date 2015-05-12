package analysis.pointer.registrar.strings;

import java.util.Set;

import analysis.pointer.graph.strings.StringLikeLocationReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

public interface StringLikeVariable {
    @Override
    public abstract String toString();

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public abstract int hashCode();

    TypeReference getExpectedType();

    boolean isSingleton();

    boolean isStringBuilder();

    boolean isString();

    boolean isNull();

    public abstract Set<StringLikeLocationReplica> getStringLocationReplicas(Context context);

}
