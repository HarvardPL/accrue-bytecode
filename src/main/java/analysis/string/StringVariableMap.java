package analysis.string;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import analysis.string.StringVariableFactory.StringVariable;

public class StringVariableMap {

    private final Map<StringVariable, AbstractString> map = new LinkedHashMap<>();
    private Set<StringVariable> readVars = new HashSet<>();
    private Set<StringVariable> changedVars = new HashSet<>();

    public AbstractString get(StringVariable key) {
        assert key != null : "Null key";
        readVars.add(key);
        return map.get(key);
    }

    /**
     * Associate the given value with the given key. If this changes the map then return true.
     *
     * @param key key
     * @param value value to associate with the key
     * @return true if this operation changes the map
     */
    public boolean put(StringVariable key, AbstractString value) {
        assert value != null : "Putting null StringVariable into Map for " + key;
        AbstractString prev = map.get(key);
        boolean changed = !value.equals(prev);
        if (changed) {
            map.put(key, value);
            changedVars.add(key);
        }
        return changed;
    }

    public Map<StringVariable, AbstractString> getMap() {
        return map;
    }

    public Set<StringVariable> getAndClearChangedVariables() {
        Set<StringVariable> s = changedVars;
        changedVars = new HashSet<>();
        return s;
    }

    public Set<StringVariable> getAndClearReadVariables() {
        Set<StringVariable> s = readVars;
        readVars = new HashSet<>();
        return s;
    }

    @Override
    public String toString() {
        return map.toString();
    }
}
