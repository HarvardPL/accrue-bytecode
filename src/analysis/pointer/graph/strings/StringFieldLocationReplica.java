package analysis.pointer.graph.strings;

import analysis.pointer.graph.ObjectField;

public class StringFieldLocationReplica implements StringLikeLocationReplica {
    private final ObjectField f;

    public static StringLikeLocationReplica make(ObjectField f) {
        return new StringFieldLocationReplica(f);
    }

    private StringFieldLocationReplica(ObjectField f) {
        this.f = f;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((f == null) ? 0 : f.hashCode());
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
        if (!(obj instanceof StringFieldLocationReplica)) {
            return false;
        }
        StringFieldLocationReplica other = (StringFieldLocationReplica) obj;
        if (f == null) {
            if (other.f != null) {
                return false;
            }
        }
        else if (!f.equals(other.f)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "StringFieldLocationReplica [f=" + f + "]";
    }

}
