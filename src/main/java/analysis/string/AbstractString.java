package analysis.string;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Zero or more string literal values
 */
public class AbstractString {

    /**
     * String value that is opaque and could be any string literal (e.g. input from a user)
     */
    public static final AbstractString ANY = new AbstractString(null);
    private static final String ANY_STRING = "ANY";
    /**
     * Abstract string for an uninitialized string value that cannot refer to any string literals
     */
    public static final AbstractString NONE = new AbstractString(Collections.<String> emptySet());
    /**
     * Biggest size of the set of strings an abstract string can represent. Once this limit is conservatively assume the
     * string can be anything.
     */
    private static final int MAX_SIZE = 10;
    private final Set<String> possibleValues;

    private AbstractString(Set<String> possibleValues) {
        this.possibleValues = possibleValues;
    }

    public Set<String> getPossibleValues() {
        if (this == ANY) {
            throw new RuntimeException("Check whether this abstract string is ANY before getting the values");
        }
        return possibleValues;
    }

    public static AbstractString create(Set<String> possibleValues) {
        assert possibleValues != null : "Null possibleValues in AbstractString";
        if (possibleValues.size() >= MAX_SIZE) {
            return AbstractString.ANY;
        }
        return new AbstractString(possibleValues);
    }

    public static AbstractString create(String singleton) {
        assert singleton != null : "Null possibleValues in AbstractString";
        return new AbstractString(Collections.singleton(singleton));
    }

    public static AbstractString join(AbstractString s1, AbstractString s2) {
        assert s1 != null || s2 != null;
        if (s1 == ANY || s2 == ANY) {
            return ANY;
        }

        if (s1 == null || s1 == NONE) {
            return s2;
        }

        if (s2 == null || s2 == NONE) {
            return s1;
        }

        Set<String> newSet = new LinkedHashSet<>(s1.getPossibleValues());
        newSet.addAll(s2.getPossibleValues());
        if (newSet.size() >= MAX_SIZE) {
            return AbstractString.ANY;
        }
        return new AbstractString(newSet);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((possibleValues == null) ? 0 : possibleValues.hashCode());
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
        AbstractString other = (AbstractString) obj;
        if (possibleValues == null) {
            if (other.possibleValues != null) {
                return false;
            }
        }
        else if (!possibleValues.equals(other.possibleValues)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        if (this == ANY) {
            return ANY_STRING;
        }
        return possibleValues.toString();
    }
}
