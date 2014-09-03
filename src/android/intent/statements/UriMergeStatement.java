package android.intent.statements;

import java.util.Map;
import java.util.Set;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractURI;

import com.ibm.wala.classLoader.IMethod;

public class UriMergeStatement extends IntentStatement {

    private int mergedValueNumber;
    private Set<Integer> toMerge;

    public UriMergeStatement(int mergedValueNumber, Set<Integer> toMerge, IMethod m) {
        super(m);
        this.mergedValueNumber = mergedValueNumber;
        this.toMerge = toMerge;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractURI data = registrar.getURI(mergedValueNumber);
        for (int val : toMerge) {
            if (data == AbstractURI.ANY) {
                break;
            }
            data = registrar.getURI(val);
        }
        return registrar.setURI(mergedValueNumber, data);
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
        UriMergeStatement other = (UriMergeStatement) obj;
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
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + mergedValueNumber;
        result = prime * result + ((toMerge == null) ? 0 : toMerge.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "MergeUriStatement [mergedValueNumber=" + mergedValueNumber + ", toMerge=" + toMerge + "]";
    }

}
