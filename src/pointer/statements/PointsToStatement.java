package pointer.statements;

import pointer.PointsToGraph;
import pointer.analyses.HeapAbstractionFactory;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

/**
 * Defines how to process points to graph information for a particular statement
 */
public interface PointsToStatement {

    /**
     * Process this statement, modifying the points to graph if necessary
     * 
     * @param context
     *            current analysis context
     * @param haf
     *            factory for creating new analysis contexts
     * @param g
     *            points to graph (may be modified)
     * @param registrar
     *            Points to statement registrar
     * @return true if the points to graph was modified
     */
    boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar);

    @Override
    public boolean equals(Object obj);

    @Override
    public int hashCode();

    /**
     * Type of the statement this represents
     * 
     * @return type of this statement
     */
    public TypeReference getExpectedType();
}
