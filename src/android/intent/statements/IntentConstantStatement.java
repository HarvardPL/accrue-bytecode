package android.intent.statements;

import java.util.Map;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractIntent;

import com.ibm.wala.classLoader.IMethod;

/**
 * An assignment of a specific AbstractIntent to an Intent object with a given value number
 */
public class IntentConstantStatement extends IntentStatement {

    private final int valueNumber;
    private final AbstractIntent constant;

    public IntentConstantStatement(int valueNumber, AbstractIntent constant, IMethod m) {
        super(m);
        this.valueNumber = valueNumber;
        this.constant = constant;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractIntent prev = registrar.getIntent(valueNumber);
        return registrar.setIntent(valueNumber, AbstractIntent.join(prev, constant));
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
        IntentConstantStatement other = (IntentConstantStatement) obj;
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
