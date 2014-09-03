package android.intent.statements;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import analysis.string.AbstractString;
import android.content.Intent;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractIntent;

import com.ibm.wala.classLoader.IMethod;

public class IntentSetAndNormalizeTypeStatement extends IntentStatement {

    private final int intentValueNumber;
    private final int typeValueNumber;

    public IntentSetAndNormalizeTypeStatement(int receiver, int typeValueNumber, IMethod m) {
        super(m);
        this.intentValueNumber = receiver;
        this.typeValueNumber = typeValueNumber;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractIntent prev = registrar.getIntent(intentValueNumber);
        if (prev == null) {
            prev = AbstractIntent.NONE;
        }
        AbstractString type = stringResults.get(typeValueNumber);
        if (type != AbstractString.ANY) {
            Set<String> normalized = new LinkedHashSet<>();
            for (String string : type.getPossibleValues()) {
                normalized.add(Intent.normalizeMimeType(string));
            }
            type = AbstractString.create(normalized);
        }
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
        IntentSetAndNormalizeTypeStatement other = (IntentSetAndNormalizeTypeStatement) obj;
        if (typeValueNumber != other.typeValueNumber) {
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
        result = prime * result + typeValueNumber;
        result = prime * result + intentValueNumber;
        return result;
    }

    @Override
    public String toString() {
        return "IntentSetAndNormalizeTypeStatement [intentValueNumber=" + intentValueNumber + ", typeValueNumber="
                + typeValueNumber + "]";
    }

}
