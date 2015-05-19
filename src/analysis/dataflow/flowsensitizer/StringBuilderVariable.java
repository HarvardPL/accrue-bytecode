package analysis.dataflow.flowsensitizer;

import java.util.HashSet;
import java.util.Set;

import util.OrderedPair;
import analysis.AnalysisUtil;
import analysis.pointer.graph.strings.StringBuilderLocationReplica;
import analysis.pointer.graph.strings.StringLikeLocationReplica;
import analysis.pointer.registrar.strings.StringLikeVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

public class StringBuilderVariable implements StringLikeVariable {
    private final Set<StringBuilderFlowSensitiveObject> objectSet;

    public static StringBuilderVariable make(IMethod method, Set<OrderedPair<Integer, Integer>> pairs) {
        Set<StringBuilderFlowSensitiveObject> os = new HashSet<>(pairs.size());
        for (OrderedPair<Integer, Integer> pair : pairs) {
            os.add(StringBuilderFlowSensitiveObject.make(method, pair.fst(), pair.snd()));
        }
        return new StringBuilderVariable(os);
    }

    private StringBuilderVariable(Set<StringBuilderFlowSensitiveObject> objectSet) {
        this.objectSet = objectSet;
    }

    @Override
    public Set<StringLikeLocationReplica> getStringLocationReplicas(Context context) {
        Set<StringLikeLocationReplica> s = AnalysisUtil.createConcurrentSet();
        for (StringBuilderFlowSensitiveObject o : objectSet) {
            s.add(StringBuilderLocationReplica.make(context, o));
        }
        return s;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((objectSet == null) ? 0 : objectSet.hashCode());
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
        if (!(obj instanceof StringBuilderVariable)) {
            return false;
        }
        StringBuilderVariable other = (StringBuilderVariable) obj;
        if (objectSet == null) {
            if (other.objectSet != null) {
                return false;
            }
        }
        else if (!objectSet.equals(other.objectSet)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "StringBuilderVariable [objectSet=" + objectSet + "]";
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
