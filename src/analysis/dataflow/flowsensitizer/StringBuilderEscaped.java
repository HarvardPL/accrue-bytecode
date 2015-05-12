package analysis.dataflow.flowsensitizer;

import java.util.Set;

import analysis.AnalysisUtil;
import analysis.pointer.graph.strings.EscapedStringLocationReplica;
import analysis.pointer.graph.strings.StringLikeLocationReplica;

import com.ibm.wala.ipa.callgraph.Context;

public class StringBuilderEscaped implements StringBuilderLocation {
    private static final StringBuilderEscaped ESCAPED = new StringBuilderEscaped();

    public static StringBuilderEscaped make() {
        return ESCAPED;
    }

    private StringBuilderEscaped() {
        // no fields
    }

    @Override
    public Set<StringLikeLocationReplica> getStringLocationReplicas(Context context) {
        Set<StringLikeLocationReplica> s = AnalysisUtil.createConcurrentSet();
        s.add(EscapedStringLocationReplica.make());
        return s;
    }
}
