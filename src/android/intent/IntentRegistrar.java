package android.intent;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import android.intent.model.AbstractComponentName;
import android.intent.model.AbstractIntent;
import android.intent.model.AbstractURI;

public class IntentRegistrar {

    private final Map<Integer, AbstractIntent> intentMap = new LinkedHashMap<>();
    private final Map<Integer, AbstractComponentName> componentMap = new LinkedHashMap<>();
    private final Map<Integer, AbstractURI> uriMap = new LinkedHashMap<>();
    private Set<Integer> readVariables = new LinkedHashSet<>();
    private Set<Integer> changedVariables = new LinkedHashSet<>();

    public AbstractComponentName getComponentName(int valueNumber) {
        readVariables.add(valueNumber);
        AbstractComponentName cn = componentMap.get(valueNumber);
        if (cn == null) {
            return AbstractComponentName.NONE;
        }
        return cn;
    }

    public boolean setComponentName(int valueNumber, AbstractComponentName cn) {
        AbstractComponentName old = componentMap.put(valueNumber, cn);
        if (old != null && !old.equals(cn)) {
            changedVariables.add(valueNumber);
        }
        return old != null;
    }

    public AbstractURI getURI(int valueNumber) {
        readVariables.add(valueNumber);
        AbstractURI uri = uriMap.get(valueNumber);
        if (uri == null) {
            return AbstractURI.NONE;
        }
        return uri;
    }

    public boolean setURI(int valueNumber, AbstractURI uri) {
        AbstractURI old = uriMap.put(valueNumber, uri);
        if (old != null && !old.equals(uri)) {
            changedVariables.add(valueNumber);
        }
        return old != null;
    }

    public AbstractIntent getIntent(int valueNumber) {
        readVariables.add(valueNumber);
        AbstractIntent intent = intentMap.get(valueNumber);
        if (intent == null) {
            return AbstractIntent.NONE;
        }
        return intent;
    }

    public boolean setIntent(int valueNumber, AbstractIntent intent) {
        AbstractIntent old = intentMap.put(valueNumber, intent);
        if (old != null && !old.equals(intent)) {
            changedVariables.add(valueNumber);
        }
        return old != null;
    }

    public Set<Integer> getAndClearReadVariables() {
        Set<Integer> s = readVariables;
        readVariables = new LinkedHashSet<>();
        return s;
    }

    public Set<Integer> getAndClearChangedVariables() {
        Set<Integer> s = changedVariables;
        changedVariables = new LinkedHashSet<>();
        return s;
    }

    /**
     * Only call this after running the analysis
     *
     * @return
     */
    public Map<Integer, AbstractIntent> getAnalysisResults() {
        return intentMap;
    }

}
