package android.intent.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import android.net.Uri;

/**
 * Model of zero or more concrete Uri objects that are used to describe the Data in an Android Intent
 * <p>
 * [scheme]://[userInfo]@[host]:[port][path]?[query]#[fragment]
 */
public class AbstractURI {
    private final Set<Uri> uriSet;
    public static final AbstractURI ANY = new AbstractURI();
    public static final AbstractURI NONE = new AbstractURI(Collections.<Uri> emptySet());

    private AbstractURI() {
        this.uriSet = null;
    }

    private AbstractURI(Set<Uri> uriSet) {
        assert uriSet != null;
        this.uriSet = uriSet;
    }

    public static AbstractURI create(Set<Uri> uriSet) {
        if (uriSet == null) {
            return ANY;
        }
        if (uriSet.isEmpty()) {
            return NONE;
        }
        return new AbstractURI(uriSet);
    }

    public Set<Uri> getPossibleValues() {
        return uriSet;
    }

    public static AbstractURI join(AbstractURI uri1, AbstractURI uri2) {
        if (uri1 == null) {
            return uri2;
        }
        if (uri2 == null) {
            return uri1;
        }
        if (uri1 == ANY || uri2 == ANY) {
            return ANY;
        }
        if (uri1 == uri2) {
            return uri1;
        }
        Set<Uri> newSet = new LinkedHashSet<>(uri1.getPossibleValues());
        newSet.addAll(uri2.getPossibleValues());
        return new AbstractURI(newSet);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((uriSet == null) ? 0 : uriSet.hashCode());
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
        AbstractURI other = (AbstractURI) obj;
        if (uriSet == null) {
            if (other.uriSet != null) {
                return false;
            }
        }
        else if (!uriSet.equals(other.uriSet)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return uriSet.toString();
    }
}
