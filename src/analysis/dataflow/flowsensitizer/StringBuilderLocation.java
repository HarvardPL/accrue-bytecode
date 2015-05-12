package analysis.dataflow.flowsensitizer;

import java.util.Set;

import analysis.pointer.graph.strings.StringLikeLocationReplica;

import com.ibm.wala.ipa.callgraph.Context;

public interface StringBuilderLocation {

    Set<StringLikeLocationReplica> getStringLocationReplicas(Context context);

}
