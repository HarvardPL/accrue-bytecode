package analysis.string.statements;

import analysis.string.AbstractString;
import analysis.string.StringVariableFactory.StringVariable;
import analysis.string.StringVariableMap;

/**
 * Allocation of a string literal
 */
public class ConstantStringStatement extends StringStatement {

    private final AbstractString literal;
    private final StringVariable variable;

    public ConstantStringStatement(StringVariable var, AbstractString literal) {
        this.variable = var;
        this.literal = literal;
    }

    @Override
    public boolean process(StringVariableMap map) {
        AbstractString prev = map.get(variable);
        assert prev == null || prev.equals(literal) : "Two different values for the same literal variable: " + variable
                + ": " + prev + " and " + literal;
        if (prev == null) {
            map.put(variable, literal);
            return true;
        }

        return false;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((literal == null) ? 0 : literal.hashCode());
        result = prime * result + ((variable == null) ? 0 : variable.hashCode());
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
        ConstantStringStatement other = (ConstantStringStatement) obj;
        if (literal == null) {
            if (other.literal != null) {
                return false;
            }
        }
        else if (!literal.equals(other.literal)) {
            return false;
        }
        if (variable == null) {
            if (other.variable != null) {
                return false;
            }
        }
        else if (!variable.equals(other.variable)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return variable + " = " + literal;
    }
}
