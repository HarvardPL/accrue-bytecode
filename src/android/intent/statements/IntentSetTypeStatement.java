package android.intent.statements;

import java.util.Map;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractIntent;

import com.ibm.wala.classLoader.IMethod;

public class IntentSetTypeStatement extends IntentStatement {

    private final int intentValueNumber;
    private final int typeValueNumber;

    public IntentSetTypeStatement(int intentValueNumber, int typeValueNumber, IMethod m) {
        super(m);
        this.intentValueNumber = intentValueNumber;
        this.typeValueNumber = typeValueNumber;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractIntent prev = registrar.getIntent(intentValueNumber);
        AbstractString type = stringResults.get(typeValueNumber);
        return registrar.setIntent(intentValueNumber, prev.joinType(type));
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
        IntentSetTypeStatement other = (IntentSetTypeStatement) obj;
        if (intentValueNumber != other.intentValueNumber) {
            return false;
        }
        if (typeValueNumber != other.typeValueNumber) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + intentValueNumber;
        result = prime * result + typeValueNumber;
        return result;
    }

    @Override
    public String toString() {
        return "IntentSetTypeStatement [intentValueNumber=" + intentValueNumber + ", typeValueNumber="
                + typeValueNumber + "]";
    }

}
