package analysis.pointer.graph.strings;

import analysis.dataflow.flowsensitizer.StringBuilderFlowSensitiveObject;

import com.ibm.wala.ipa.callgraph.Context;

public class StringBuilderLocationReplica implements StringLikeLocationReplica {
    private final Context context;
    private final StringBuilderFlowSensitiveObject o;

    public static StringLikeLocationReplica make(Context context, StringBuilderFlowSensitiveObject o) {
        return new StringBuilderLocationReplica(context, o);
    }

    private StringBuilderLocationReplica(Context context, StringBuilderFlowSensitiveObject o) {
        this.context = context;
        this.o = o;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((context == null) ? 0 : context.hashCode());
        result = prime * result + ((o == null) ? 0 : o.hashCode());
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
        if (!(obj instanceof StringBuilderLocationReplica)) {
            return false;
        }
        StringBuilderLocationReplica other = (StringBuilderLocationReplica) obj;
        if (context == null) {
            if (other.context != null) {
                return false;
            }
        }
        else if (!context.equals(other.context)) {
            return false;
        }
        if (o == null) {
            if (other.o != null) {
                return false;
            }
        }
        else if (!o.equals(other.o)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "StringBuilderLocationReplica [context=" + context + ", o=" + o + "]";
    }
}
