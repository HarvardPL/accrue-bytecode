package android.intent.statements;

import java.util.Map;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractIntent;

import com.ibm.wala.classLoader.IMethod;

public class IntentSetPackageStatement extends IntentStatement {

    private final int intentValueNumber;
    private final int packageValueNumber;

    public IntentSetPackageStatement(int intentValueNumber, int packageValueNumber, IMethod m) {
        super(m);
        this.intentValueNumber = intentValueNumber;
        this.packageValueNumber = packageValueNumber;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractIntent prev = registrar.getIntent(intentValueNumber);
        AbstractString packageName = stringResults.get(packageValueNumber);
        return registrar.setIntent(intentValueNumber, prev.joinPackageName(packageName));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + intentValueNumber;
        result = prime * result + packageValueNumber;
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
        IntentSetPackageStatement other = (IntentSetPackageStatement) obj;
        if (intentValueNumber != other.intentValueNumber) {
            return false;
        }
        if (packageValueNumber != other.packageValueNumber) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "IntentSetPackageStatement [intentValueNumber=" + intentValueNumber + ", packageValueNumber="
                + packageValueNumber + "]";
    }
}
