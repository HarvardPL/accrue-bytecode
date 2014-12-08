package analysis.pointer.statements;

import java.util.List;
import java.util.Set;

import types.TypeRepository;
import util.OrderedPair;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

/**
 * Defines how to process points-to graph information for a particular statement
 */
public abstract class PointsToStatement {

    /**
     * Program point that created this statement.
     */
    private ProgramPoint pp;

    /**
     * Statement derived from an expression in <code>m</code> defining how points-to results change as a result of this
     * statement.
     *
     * @param pp program point this statement was created at
     */
    public PointsToStatement(ProgramPoint pp) {
        this.pp = pp;
        assert pp != null;

    }

    public ProgramPoint programPoint() {
        return this.pp;
    }

    /**
     * Method this statement was created in
     *
     * @return resolved method
     */
    public IMethod getMethod() {
        return this.pp.containingProcedure();
    }

    /**
     * Process this statement, modifying the points-to graph if necessary
     *
     * @param context current analysis context
     * @param haf factory for creating new analysis contexts
     * @param g points-to graph (may be modified)
     * @param delta Changes to the graph relevant to this statement since the last time this stmt was processed. Maybe
     *            null (e.g., if it is the first time the statement is processed, and may be used by the processing to
     *            improve the performance of processing).
     * @param registrar Points-to statement registrar
     * @param originator TODO
     * @param originator The SaC that caused this processing, i.e. the pair of this and context.
     * @return Changes to the graph as a result of processing this statement. Must be non-null.
     */
    public abstract GraphDelta process(Context context, RecencyHeapAbstractionFactory haf, PointsToGraph g,
                                       GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator);

    /**
     * Does this statement kill a PointsToGraphNode? Null is returned if this statement definitely cannot kill a
     * PointsToGraphNode. If this statement might kill a PointsToGraphNode, then a non-null value is returned. The first
     * element of the pair is false if not enough information is available yet to determine whether a PointsToGraphNode
     * is killed. If the first element is true, then enough information is available to determine whether a
     * PointsToGraphNode is killed. the second element may be a PointsToGraphNode (if it really does kill a node), and
     * null if it does not. If this method returns (true, null), then all subsequent calls to the method will always
     * return (true, null), even if more points to edges are added to the graph.
     *
     * @param context
     * @param g
     * @return
     */
    @SuppressWarnings({ "static-method", "unused" })
    public OrderedPair<Boolean, PointsToGraphNode> killsNode(Context context, PointsToGraph g) {
        return null;
    }

    /**
     * Return the field that this statement may kill.
     *
     * @return
     */
    @SuppressWarnings("static-method")
    public FieldReference getMaybeKilledField() {
        return null;
    }

    /**
     * The InstanceKeyRecency that this statement allocates, if any.
     *
     * @param context
     * @param g
     * @return
     */
    @SuppressWarnings({ "static-method", "unused" })
    public InstanceKeyRecency justAllocated(Context context, PointsToGraph g) {
        return null;
    }

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
     * @param left assignee
     * @param right assigned
     * @return true if right can safely be assigned to the left
     */
    protected final boolean checkTypes(ReferenceVariableReplica left, ReferenceVariableReplica right) {
        if (right.getExpectedType() == TypeReference.Null) {
            // null can be assigned to any reference type
            return !left.getExpectedType().isPrimitiveType() || left.getExpectedType() == TypeReference.Null;
        }
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
        IClass c1 = cha.lookupClass(left.getExpectedType());
        assert c1 != null;
        IClass c2 = cha.lookupClass(right.getExpectedType());
        assert c2 != null;
        boolean check = cha.isAssignableFrom(c1, c2);

        if (check) {
            return true;
        }

        if (c1.isInterface() && cha.isRootClass(c2)) {
            // c2 may be the merge of two different types that both implement c1 and the assignment is safe OK
            // Unfortunately we've lost the information about which interfaces c2 implements at this point. It would be
            // nice if the type inference kept this information.
            if (PointsToAnalysis.outputLevel > 0) {
                System.err.println("TYPE-CHECK-FAILURE: " + this + "\n\t" + left + " = " + right + " is invalid");
                System.err.println("\tassigned type: " + PrettyPrinter.typeString(left.getExpectedType())
                        + " is an interface and the assignee is java.lang.Object. ");
                System.err.println("\tBut since the type inference does not track interfaces the actual value may still implement the interface.");
            }
            return true;
        }

        System.err.println("TYPE-CHECK-FAILURE: " + this + "\n\t" + left + " = " + right + " is invalid");
        System.err.println("\t" + PrettyPrinter.typeString(left.getExpectedType()) + " = "
                + PrettyPrinter.typeString(right.getExpectedType()) + " does not type check");
        if (PointsToAnalysis.outputLevel >= 1) {
            CFGWriter.writeToFile(getMethod());
            TypeRepository.print(getMethod());
        }

        return check;
    }

    /**
     * Check whether the given set is empty and print an error message if we are in debug mode (PointsToAnalysis.DEBUG =
     * true) and the output level is high enough.
     *
     * @param pointsToSet set to check
     * @param r replicate the points to set is for
     * @param description description of the replica the points to set came from
     * @param callee callee method
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

    /**
     * Replace a variable use with a different variable. What the number corresponds to is defined by the implementation
     * of {@link PointsToStatement#getUses()}.
     *
     * @param useNumber use number of the variable to be replaced
     * @param newVariable reference variable to replace the use
     */
    public abstract void replaceUse(int useNumber, ReferenceVariable newVariable);

    /**
     * Get all variables used by this points-to statement. The order is arbitrary but the index is guaranteed to be the
     * same as the use number in {@link PointsToStatement#replaceUse(int, ReferenceVariable)}.
     *
     * @return list of variable uses
     */
    public abstract List<ReferenceVariable> getUses();

    /**
     * Get local variable defined by this statement, if any
     *
     * @return local variable assigned into, null if there no such variable
     */
    public abstract List<ReferenceVariable> getDefs();

    /**
     * Get the objects that invoking killed(Context context, PointsToGraph g) will "read". One PointsToStatement depends
     * on another if the first "reads" an object that the other "writes". The objects are typically
     * ReferenceVariableReplicas, but may use other objects (e.g., FieldReferences and IMethods) to express dependencies
     * between statements.
     */
    @SuppressWarnings({ "static-method", "unused" })
    public ReferenceVariableReplica getReadDependencyForKillField(Context ctxt, HeapAbstractionFactory haf) {
        return null;
    }

    /**
     * Is it possible that processing this statement will modify the flow-sensitive portion of the points to graph? This
     * includes anything that may change what a flow-sensitive variable points to, or if it changes the succesor
     * relation of program points (i.e., statements that change the call graph).
     */
    public abstract boolean mayChangeOrUseFlowSensPointsToGraph();

    public void setProgramPoint(ProgramPoint pp) {
        this.pp = pp;
    }
}
