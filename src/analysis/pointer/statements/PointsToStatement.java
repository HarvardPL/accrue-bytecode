package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Defines how to process points-to graph information for a particular statement
 */
public abstract class PointsToStatement {

    /**
     * method this statement was created in
     */
    private final IMethod m;

    /**
     * Statement derived from an expression in <code>m</code> defining how points-to results change as a result of this
     * statement.
     * 
     * @param m
     *            method this statement was created in
     */
    public PointsToStatement(IMethod m) {
        this.m = m;
    }

    /**
     * Method this statement was created in
     * 
     * @return resolved method
     */
    protected IMethod getMethod() {
        return m;
    }

    /**
     * Process this statement, modifying the points-to graph if necessary
     * 
     * @param context
     *            current analysis context
     * @param haf
     *            factory for creating new analysis contexts
     * @param g
     *            points-to graph (may be modified)
     * @param registrar
     *            Points-to statement registrar
     * @return true if the points-to graph was modified
     */
    public abstract boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                    StatementRegistrar registrar);

    @Override
    public final int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public final boolean equals(Object obj) {
        return this == obj;
    }

    @Override
    public abstract String toString();
}
