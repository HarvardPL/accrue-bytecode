package analysis.string.statements;

import analysis.string.AbstractString;
import analysis.string.StringVariableFactory.StringVariable;
import analysis.string.StringVariableMap;

public class CopyStringStatement extends StringStatement {

    private final StringVariable left;
    private final StringVariable right;

    public CopyStringStatement(StringVariable left, StringVariable right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean process(StringVariableMap map) {
        AbstractString rightVal = map.get(right);
        if (rightVal == null) {
            // Waiting for results for the right side set to bottom
            rightVal = AbstractString.NONE;
        }
        return map.put(left, AbstractString.join(rightVal, map.get(left)));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((left == null) ? 0 : left.hashCode());
        result = prime * result + ((right == null) ? 0 : right.hashCode());
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
        CopyStringStatement other = (CopyStringStatement) obj;
        if (left == null) {
            if (other.left != null) {
                return false;
            }
        }
        else if (!left.equals(other.left)) {
            return false;
        }
        if (right == null) {
            if (other.right != null) {
                return false;
            }
        }
        else if (!right.equals(other.right)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return left + " = " + right;
    }
}
