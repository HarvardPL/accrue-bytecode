package analysis.pointer.graph;

import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

public class StringVariableReplica implements PointsToGraphNode, StringSolutionVariable {

    private final Context context;
    private final StringVariable local;

    public StringVariableReplica(Context context, StringVariable local) {
        assert local != null && context != null;
        this.context = context;
        this.local = local;
    }

    @Override
    public TypeReference getExpectedType() {
        return TypeReference.JavaLangString;
    }

    /*
     * AUTOGENERATED STUFF
     *
     * Be sure to regenerate these (using Eclipse) if you change the number of fields in this class
     *
     * DEFINTIELY DONT CHANGE ANYTHING
     */

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((context == null) ? 0 : context.hashCode());
        result = prime * result + ((local == null) ? 0 : local.hashCode());
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
        if (!(obj instanceof StringVariableReplica)) {
            return false;
        }
        StringVariableReplica other = (StringVariableReplica) obj;
        if (context == null) {
            if (other.context != null) {
                return false;
            }
        }
        else if (!context.equals(other.context)) {
            return false;
        }
        if (local == null) {
            if (other.local != null) {
                return false;
            }
        }
        else if (!local.equals(other.local)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "(SVR " + context + " " + local + ")";
    }

}
