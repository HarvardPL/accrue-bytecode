package analysis.dataflow.flowsensitizer;

import java.util.Collection;

public class FlowSensitizedVariableMapFactory<T> {

    public static <T> FlowSensitizedVariableMapFactory<T> make() {
        return new FlowSensitizedVariableMapFactory<>();
    }

    public FlowSensitizedVariableMap<T> makeEmpty() {
        return FlowSensitizedVariableMap.makeEmpty();
    }

    public FlowSensitizedVariableMap<T> joinCollection(Collection<FlowSensitizedVariableMap<T>> s) {
        return FlowSensitizedVariableMap.joinCollection(s);
    }

}
