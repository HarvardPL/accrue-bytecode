package android.intent.statements;

import java.util.Map;
import java.util.Set;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractIntent;

import com.ibm.wala.classLoader.IMethod;

public class IntentMergeStatement extends IntentStatement {

    private int mergedValueNumber;
    private Set<Integer> toMerge;

    public IntentMergeStatement(int mergedValueNumber, Set<Integer> toMerge, IMethod m) {
        super(m);
        this.mergedValueNumber = mergedValueNumber;
        this.toMerge = toMerge;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractIntent intent = registrar.getIntent(mergedValueNumber);
        for (int val : toMerge) {
            if (intent == AbstractIntent.ANY) {
                break;
            }
            intent = registrar.getIntent(val);
        }
        return registrar.setIntent(mergedValueNumber, intent);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + mergedValueNumber;
        result = prime * result + ((toMerge == null) ? 0 : toMerge.hashCode());
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
        IntentMergeStatement other = (IntentMergeStatement) obj;
        if (mergedValueNumber != other.mergedValueNumber) {
            return false;
        }
        if (toMerge == null) {
            if (other.toMerge != null) {
                return false;
            }
        }
        else if (!toMerge.equals(other.toMerge)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "MergeIntentStatement [mergedValueNumber=" + mergedValueNumber + ", toMerge=" + toMerge + "]";
    }
}
