package android.intent.statements;

import java.util.Map;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractURI;

import com.ibm.wala.classLoader.IMethod;

/**
 * An assignment of a specific AbstractURI to an URI object with a given value number
 */
public class UriConstantStatement extends IntentStatement {

    private final int valueNumber;
    private final AbstractURI constant;

    public UriConstantStatement(int valueNumber, AbstractURI constant, IMethod m) {
        super(m);
        this.valueNumber = valueNumber;
        this.constant = constant;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractURI prev = registrar.getURI(valueNumber);
        return registrar.setURI(valueNumber, AbstractURI.join(prev, constant));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((constant == null) ? 0 : constant.hashCode());
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
        UriConstantStatement other = (UriConstantStatement) obj;
        if (constant == null) {
            if (other.constant != null) {
                return false;
            }
        }
        else if (!constant.equals(other.constant)) {
            return false;
        }
        if (valueNumber != other.valueNumber) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return valueNumber + " = " + constant;
    }
}
