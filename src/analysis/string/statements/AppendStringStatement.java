package analysis.string.statements;

import java.util.LinkedHashSet;
import java.util.Set;

import analysis.string.AbstractString;
import analysis.string.StringVariableFactory.StringVariable;
import analysis.string.StringVariableMap;

/**
 * Statement that represents a call to StringBuilder.append
 */
public class AppendStringStatement extends StringStatement {

    private final StringVariable stringBuilderAfter;
    private final StringVariable stringBuilderBefore;
    private final StringVariable argument;

    /**
     * Statement that represents a call to StringBuilder.append
     *
     * @param stringBuilderAfter new variable that represents the string builder just after the call
     * @param stringBuilderBefore variable representing the string builder just before the call
     * @param argument argument to the call to append
     */
    public AppendStringStatement(StringVariable stringBuilderAfter, StringVariable stringBuilderBefore,
                                 StringVariable argument) {
        this.stringBuilderAfter = stringBuilderAfter;
        this.stringBuilderBefore = stringBuilderBefore;
        this.argument = argument;
    }

    @Override
    public boolean process(StringVariableMap map) {
        AbstractString before = map.get(stringBuilderBefore);
        AbstractString arg = map.get(argument);

        if (before == null || arg == null) {
            // Waiting for results for the string builder before the append and/or the argument
            return map.put(stringBuilderAfter, AbstractString.ANY);
        }

        AbstractString newVal;
        if (before == AbstractString.ANY || arg == AbstractString.ANY) {
            newVal = AbstractString.ANY;
        }
        else {
            // there is already a string in the string builder append the new value
            // TODO if this is a bottleneck could delay evaluation until this is needed by storing the sets separately
            Set<String> newStrings = new LinkedHashSet<>();
            for (String prefix : before.getPossibleValues()) {
                for (String suffix : arg.getPossibleValues()) {
                    newStrings.add(prefix + suffix);
                }
            }
            newVal = AbstractString.create(newStrings);
        }
        return map.put(stringBuilderAfter, newVal);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((argument == null) ? 0 : argument.hashCode());
        result = prime * result + ((stringBuilderAfter == null) ? 0 : stringBuilderAfter.hashCode());
        result = prime * result + ((stringBuilderBefore == null) ? 0 : stringBuilderBefore.hashCode());
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
        AppendStringStatement other = (AppendStringStatement) obj;
        if (argument == null) {
            if (other.argument != null) {
                return false;
            }
        }
        else if (!argument.equals(other.argument)) {
            return false;
        }
        if (stringBuilderAfter == null) {
            if (other.stringBuilderAfter != null) {
                return false;
            }
        }
        else if (!stringBuilderAfter.equals(other.stringBuilderAfter)) {
            return false;
        }
        if (stringBuilderBefore == null) {
            if (other.stringBuilderBefore != null) {
                return false;
            }
        }
        else if (!stringBuilderBefore.equals(other.stringBuilderBefore)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return stringBuilderAfter + " = " + stringBuilderBefore + " ++ " + argument;
    }
}
