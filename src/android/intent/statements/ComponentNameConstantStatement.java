package android.intent.statements;

import java.util.Map;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractComponentName;

import com.ibm.wala.classLoader.IMethod;

/**
 * An assignment of a specific AbstractComponentName to a ComponentName object with a given value number
 */
public class ComponentNameConstantStatement extends IntentStatement {

    private final AbstractComponentName constant;
    private final int valueNumber;

    public ComponentNameConstantStatement(int valueNumber, AbstractComponentName constant, IMethod m) {
        super(m);
        this.valueNumber = valueNumber;
        this.constant = constant;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractComponentName cn = registrar.getComponentName(valueNumber);
        return registrar.setComponentName(valueNumber, AbstractComponentName.join(cn, constant));
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
        ComponentNameConstantStatement other = (ComponentNameConstantStatement) obj;
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
