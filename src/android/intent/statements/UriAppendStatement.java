package android.intent.statements;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractURI;
import android.net.Uri;

import com.ibm.wala.classLoader.IMethod;

public class UriAppendStatement extends IntentStatement {

    private final int newValueNumber;
    private final int oldValueNumber;
    private final int toAppend;

    public UriAppendStatement(int newValueNumber, int oldValueNumber, int toAppend, IMethod m) {
        super(m);
        this.newValueNumber = newValueNumber;
        this.oldValueNumber = oldValueNumber;
        this.toAppend = toAppend;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractURI old = registrar.getURI(oldValueNumber);
        AbstractString string = stringResults.get(toAppend);
        if (old == AbstractURI.ANY || string == AbstractString.ANY) {
            return registrar.setURI(newValueNumber, AbstractURI.ANY);
        }
        AbstractURI prev = registrar.getURI(newValueNumber);
        Set<Uri> newSet = new LinkedHashSet<>();
        for (Uri uri : old.getPossibleValues()) {
            for (String s : string.getPossibleValues()) {
                newSet.add(Uri.withAppendedPath(uri, s));
            }
        }
        return registrar.setURI(newValueNumber, AbstractURI.join(prev, AbstractURI.create(newSet)));
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
        UriAppendStatement other = (UriAppendStatement) obj;
        if (newValueNumber != other.newValueNumber) {
            return false;
        }
        if (oldValueNumber != other.oldValueNumber) {
            return false;
        }
        if (toAppend != other.toAppend) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + newValueNumber;
        result = prime * result + oldValueNumber;
        result = prime * result + toAppend;
        return result;
    }

    @Override
    public String toString() {
        return "UriAppendStatement [newValueNumber=" + newValueNumber + ", oldValueNumber=" + oldValueNumber
                + ", toAppend=" + toAppend + "]";
    }

}
