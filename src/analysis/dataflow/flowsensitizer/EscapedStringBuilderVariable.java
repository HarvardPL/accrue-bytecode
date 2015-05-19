package analysis.dataflow.flowsensitizer;

import java.util.Set;

import analysis.AnalysisUtil;
import analysis.pointer.graph.strings.EscapedStringBuilderLocationReplica;
import analysis.pointer.graph.strings.StringLikeLocationReplica;
import analysis.pointer.registrar.strings.StringLikeVariable;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

public class EscapedStringBuilderVariable implements StringLikeVariable {
    private static final EscapedStringBuilderVariable ESCAPED = new EscapedStringBuilderVariable();

    public static EscapedStringBuilderVariable make() {
        return ESCAPED;
    }

    private EscapedStringBuilderVariable() {
        // no fields
    }

    @Override
    public Set<StringLikeLocationReplica> getStringLocationReplicas(Context context) {
        Set<StringLikeLocationReplica> s = AnalysisUtil.createConcurrentSet();
        s.add(EscapedStringBuilderLocationReplica.make());
        return s;
    }

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
        return false;
    }
}
