package analysis.pointer.graph.strings;

import analysis.pointer.registrar.strings.StringLikeVariable;

import com.ibm.wala.ipa.callgraph.Context;

public class StringLocationReplica implements StringLikeLocationReplica {
    private final Context context;
    private final StringLikeVariable v;

    public static StringLocationReplica make(Context context, StringLikeVariable v) {
        return new StringLocationReplica(context, v);
    }

    private StringLocationReplica(Context context, StringLikeVariable v) {
        this.context = context;
        this.v = v;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((context == null) ? 0 : context.hashCode());
        result = prime * result + ((v == null) ? 0 : v.hashCode());
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
        if (!(obj instanceof StringLocationReplica)) {
            return false;
        }
        StringLocationReplica other = (StringLocationReplica) obj;
        if (context == null) {
            if (other.context != null) {
                return false;
            }
        }
        else if (!context.equals(other.context)) {
            return false;
        }
        if (v == null) {
            if (other.v != null) {
                return false;
            }
        }
        else if (!v.equals(other.v)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "StringLocationReplica [context=" + context + ", v=" + v + "]";
    }

}
