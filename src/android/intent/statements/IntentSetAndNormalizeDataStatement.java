package android.intent.statements;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractIntent;
import android.intent.model.AbstractURI;
import android.net.Uri;

import com.ibm.wala.classLoader.IMethod;

public class IntentSetAndNormalizeDataStatement extends IntentStatement {

    private final int intentValueNumber;
    private final int dataValueNumber;

    public IntentSetAndNormalizeDataStatement(int receiver, int dataValueNumber, IMethod m) {
        super(m);
        this.intentValueNumber = receiver;
        this.dataValueNumber = dataValueNumber;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractIntent prev = registrar.getIntent(intentValueNumber);
        if (prev == null) {
            prev = AbstractIntent.NONE;
        }
        AbstractURI data = registrar.getURI(dataValueNumber);
        if (data != AbstractURI.ANY) {
            Set<Uri> normalized = new LinkedHashSet<>();
            for (Uri uri : data.getPossibleValues()) {
                normalized.add(uri.normalizeScheme());
            }
            data = AbstractURI.create(normalized);
        }
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
        IntentSetAndNormalizeDataStatement other = (IntentSetAndNormalizeDataStatement) obj;
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
        return "IntentSetAndNormalizeDataStatement [intentValueNumber=" + intentValueNumber + ", dataValueNumber="
                + dataValueNumber + "]";
    }

}
