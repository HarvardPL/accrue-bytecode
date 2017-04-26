package analysis.string.statements;

import analysis.string.StringVariableMap;


public abstract class StringStatement {

    public abstract boolean process(StringVariableMap map);

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public abstract int hashCode();

    @Override
    public abstract String toString();
}
