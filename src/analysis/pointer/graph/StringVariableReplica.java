package analysis.pointer.graph;

import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

public class StringVariableReplica implements PointsToGraphNode {

    private final Context context;
    private final StringVariable local;

    public StringVariableReplica(Context context, StringVariable local) {
        this.context = context;
        this.local = local;
    }

    @Override
    public TypeReference getExpectedType() {
        return TypeReference.JavaLangString;
    }

    @Override
    public int hashCode() {
        // XXX: implement
        throw new RuntimeException("unimplemented");
    }

    @Override
    public boolean equals(Object obj) {
        // XXX: implement
        throw new RuntimeException("unimplemented");
    }

    @Override
    public String toString() {
        // XXX: implement
        throw new RuntimeException("unimplemented");
    }

}
