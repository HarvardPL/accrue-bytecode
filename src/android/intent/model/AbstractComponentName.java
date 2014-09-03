package android.intent.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import android.content.ComponentName;

public class AbstractComponentName {
    public static final AbstractComponentName ANY = new AbstractComponentName();
    public static final AbstractComponentName NONE = new AbstractComponentName(Collections.<ComponentName> emptySet());
    private final Set<ComponentName> components;

    /**
     * Create an abstract ComponentName object that represents zero or more concrete (runtime) objects
     */
    private AbstractComponentName(Set<ComponentName> components) {
        assert components != null;
        this.components = components;
    }

    private AbstractComponentName() {
        this.components = null;
    }

    public static AbstractComponentName create(Set<ComponentName> components) {
        if (components == null) {
            return ANY;
        }
        if (components.isEmpty()) {
            return NONE;
        }
        return new AbstractComponentName(components);
    }

    public static AbstractComponentName join(AbstractComponentName cn1, AbstractComponentName cn2) {
        if (cn1 == null) {
            return cn2;
        }
        if (cn2 == null) {
            return cn1;
        }
        if (cn1 == AbstractComponentName.ANY || cn2 == AbstractComponentName.ANY) {
            return AbstractComponentName.ANY;
        }
        if (cn1.equals(cn2)) {
            return cn1;
        }
        Set<ComponentName> newSet = new LinkedHashSet<>();
        newSet.addAll(cn1.getPossibleValues());
        newSet.addAll(cn2.getPossibleValues());
        return new AbstractComponentName(newSet);
    }

    public Set<ComponentName> getPossibleValues() {
        return components;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((components == null) ? 0 : components.hashCode());
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
        AbstractComponentName other = (AbstractComponentName) obj;
        if (components == null) {
            if (other.components != null) {
                return false;
            }
        }
        else if (!components.equals(other.components)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return components.toString();
    }
}
