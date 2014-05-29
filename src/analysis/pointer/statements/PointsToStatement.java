package analysis.pointer.statements;

import java.util.Set;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

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

    /**
     * Check whether the types in the assignment satisfy type-system requirements (i.e. ensure that left = right is a
     * valid assignment)
     * 
     * @param left
     *            assignee
     * @param right
     *            assigned
     * @return true if right can safely be assigned to the left
     */
    protected final static boolean checkTypes(ReferenceVariableReplica left, ReferenceVariableReplica right) {
        return AnalysisUtil.getClassHierarchy().isAssignableFrom(
                                        AnalysisUtil.getClassHierarchy().lookupClass(left.getExpectedType()),
                                        AnalysisUtil.getClassHierarchy().lookupClass(right.getExpectedType()));
    }

    /**
     * Check whether the given set is empty and print an error message if we are in debug mode (PointsToAnalysis.DEBUG =
     * true) and the output level is high enough.
     * 
     * @param pointsToSet
     *            set to check
     * @param r
     *            replicate the points to set is for
     * @param description
     *            description of the replica the points to set came from
     * @param callee
     *            callee method
     * @return false if the check fails and all the conditions required to perform the check hold
     */
    protected final boolean checkForNonEmpty(Set<InstanceKey> pointsToSet, PointsToGraphNode r, String description) {
        if (PointsToAnalysis.DEBUG && PointsToAnalysis.outputLevel >= 6 && pointsToSet.isEmpty()) {
            System.err.println("EMPTY: " + r + " in " + this + " " + description + " from "
                                            + PrettyPrinter.methodString(getMethod()));
            return false;
        }
        return true;
    }
}
