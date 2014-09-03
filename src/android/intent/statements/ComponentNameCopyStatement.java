package android.intent.statements;

import java.util.Map;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractComponentName;

import com.ibm.wala.classLoader.IMethod;

/**
 * Copy one ComponentName object to another
 */
public class ComponentNameCopyStatement extends IntentStatement {

    private final int left;
    private final int right;

    public ComponentNameCopyStatement(int left, int right, IMethod m) {
        super(m);
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractComponentName prev = registrar.getComponentName(left);
        AbstractComponentName assigned = registrar.getComponentName(right);
        return registrar.setComponentName(left, AbstractComponentName.join(prev, assigned));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + left;
        result = prime * result + right;
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
        ComponentNameCopyStatement other = (ComponentNameCopyStatement) obj;
        if (left != other.left) {
            return false;
        }
        if (right != other.right) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return left + " = " + right;
    }

}
