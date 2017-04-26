package analysis.string.statements;

import java.util.Set;

import analysis.string.AbstractString;
import analysis.string.StringVariableFactory.StringVariable;
import analysis.string.StringVariableMap;

public class MergeStringStatement extends StringStatement {

    private StringVariable local;
    private Set<StringVariable> vars;

    /**
     * Assignment from a phi statement to a local variable
     *
     * @param local local variable that assigned the result of the phi
     * @param vars variables in the phi statement
     */
    public MergeStringStatement(StringVariable local, Set<StringVariable> vars) {
        this.local = local;
        this.vars = vars;
    }

    @Override
    public boolean process(StringVariableMap map) {
        AbstractString newVal = AbstractString.NONE;
        for (StringVariable sv : vars) {
            newVal = AbstractString.join(map.get(sv), newVal);
        }
        return map.put(local, newVal);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((local == null) ? 0 : local.hashCode());
        result = prime * result + ((vars == null) ? 0 : vars.hashCode());
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
        if (getClass() != obj.getClass()) {
            return false;
        }
        MergeStringStatement other = (MergeStringStatement) obj;
        if (local == null) {
            if (other.local != null) {
                return false;
            }
        }
        else if (!local.equals(other.local)) {
            return false;
        }
        if (vars == null) {
            if (other.vars != null) {
                return false;
            }
        }
        else if (!vars.equals(other.vars)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return local + " = merge" + vars;
    }
}
