package android.intent.statements;

import java.util.Map;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;

import com.ibm.wala.classLoader.IMethod;

public abstract class IntentStatement {

    private final IMethod m;

    public IntentStatement(IMethod m) {
        this.m = m;
    }

    public IMethod getMethod() {
        return m;
    }

    /**
     * Process the constraint represented by this statement.
     *
     * @param registrar registrar containing information relevent to the Intent analysis, may be modified
     * @param stringResults TODO
     *
     * @return true if the registrar was modified by this operation
     */
    public abstract boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults);

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public abstract int hashCode();

    @Override
    public abstract String toString();
}
