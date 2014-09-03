package android.intent.statements;

import java.util.Map;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractURI;

import com.ibm.wala.classLoader.IMethod;

public class UriNormalizeSchemeStatement extends IntentStatement {

    private int def;
    private int receiver;

    public UriNormalizeSchemeStatement(int def, int receiver, IMethod m) {
        super(m);
        this.def = def;
        this.receiver = receiver;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractURI prev = registrar.getURI(def);
        AbstractURI old = registrar.getURI(receiver);
        return registrar.setURI(def, AbstractURI.join(prev, old));
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
        UriNormalizeSchemeStatement other = (UriNormalizeSchemeStatement) obj;
        if (def != other.def) {
            return false;
        }
        if (receiver != other.receiver) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + def;
        result = prime * result + receiver;
        return result;
    }

    @Override
    public String toString() {
        return "UriNormalizeSchemeStatement [def=" + def + ", receiver=" + receiver + "]";
    }

}
