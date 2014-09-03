package android.intent.statements;

import java.util.Map;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractIntent;

import com.ibm.wala.classLoader.IMethod;

/**
 * Copy one URI object to another
 */
public class UriCopyStatement extends IntentStatement {

    private final int left;
    private final int right;

    public UriCopyStatement(int left, int right, IMethod m) {
        super(m);
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractIntent prev = registrar.getIntent(left);
        AbstractIntent assigned = registrar.getIntent(right);
        return registrar.setIntent(left, AbstractIntent.join(prev, assigned));
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
        UriCopyStatement other = (UriCopyStatement) obj;
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
