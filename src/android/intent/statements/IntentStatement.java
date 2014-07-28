package android.intent.statements;

import android.intent.IntentRegistrar;

public abstract class IntentStatement {
    /**
     * Process the constraint represented by this statement.
     *
     * @param registrar registrar containing information relevent to the Intent analysis, may be modified
     *
     * @return true if the registrar was modified by this operation
     */
    public abstract boolean process(IntentRegistrar registrar);

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public abstract int hashCode();

    @Override
    public abstract String toString();
}
