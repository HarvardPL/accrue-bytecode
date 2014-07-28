package android.intent.model;

import analysis.string.AbstractString;

/**
 * Model of a URI that is used to describe Data in an Android Intent
 * <p>
 * [scheme]://[userInfo]@[host]:[port][path]?[query]#[fragment]
 */
public class AbstractURI {
    private AbstractString uri;

    public AbstractURI(AbstractString uri) {
        this.uri = uri;
    }

    public AbstractString getUriString() {
        return uri;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((uri == null) ? 0 : uri.hashCode());
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
        if (uri == null) {
            if (other.uri != null) {
                return false;
            }
        }
        else if (!uri.equals(other.uri)) {
            return false;
        }
        return true;
    }
}
