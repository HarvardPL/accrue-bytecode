package android.intent.statements;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractURI;

/**
 * Initialization of a URI object
 */
public class UriInitStatement extends IntentStatement {
    private final AbstractString uriString;
    private final int valueNumber;

    /**
     *
     * @param valueNumber value numberof the URI object being initialized
     * @param uriString abstract string representing the URI
     */
    public UriInitStatement(int valueNumber, AbstractString uriString) {
        this.valueNumber = valueNumber;
        this.uriString = uriString;
    }

    @Override
    public boolean process(IntentRegistrar registrar) {
        AbstractURI previous = registrar.getURI(valueNumber);
        AbstractString newUriString;
        if (previous != null) {
            newUriString = AbstractString.join(previous.getUriString(), uriString);
        }
        else {
            newUriString = uriString;
        }
        return registrar.setURI(new AbstractURI(newUriString));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((uriString == null) ? 0 : uriString.hashCode());
        result = prime * result + valueNumber;
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
        UriInitStatement other = (UriInitStatement) obj;
        if (uriString == null) {
            if (other.uriString != null) {
                return false;
            }
        }
        else if (!uriString.equals(other.uriString)) {
            return false;
        }
        if (valueNumber != other.valueNumber) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "URI.<init> { val:" + valueNumber + ", uri:" + uriString + " }";
    }

}
