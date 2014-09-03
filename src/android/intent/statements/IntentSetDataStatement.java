package android.intent.statements;

import java.util.Map;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractIntent;
import android.intent.model.AbstractURI;

import com.ibm.wala.classLoader.IMethod;

public class IntentSetDataStatement extends IntentStatement {

    private final int intentValueNumber;
    private final int dataValueNumber;

    public IntentSetDataStatement(int intentValueNumber, int dataValueNumber, IMethod m) {
        super(m);
        this.intentValueNumber = intentValueNumber;
        this.dataValueNumber = dataValueNumber;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractIntent prev = registrar.getIntent(intentValueNumber);
        AbstractURI data = registrar.getURI(dataValueNumber);
        return registrar.setIntent(intentValueNumber, prev.joinData(data));
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
        IntentSetDataStatement other = (IntentSetDataStatement) obj;
        if (dataValueNumber != other.dataValueNumber) {
            return false;
        }
        if (intentValueNumber != other.intentValueNumber) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + dataValueNumber;
        result = prime * result + intentValueNumber;
        return result;
    }

    @Override
    public String toString() {
        return "IntentSetDataStatement [intentValueNumber=" + intentValueNumber + ", dataValueNumber="
                + dataValueNumber + "]";
    }

}
