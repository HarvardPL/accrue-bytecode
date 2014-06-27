package analysis.pointer.statements;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.TypeFilter;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

public class ExceptionAssignmentStatement extends PointsToStatement {

    private ReferenceVariable thrown;
    private final ReferenceVariable caught;
    private final TypeFilter filter;

    /**
     * Statement for the assignment from a thrown exception to a caught exception or the summary node for the
     * exceptional exit to a method
     * 
     * @param thrown
     *            reference variable for the exception being thrown
     * @param caught
     *            reference variable for the caught exception (or summary for the method exit)
     * @param notType
     *            types that the exception being caught cannot have since those types must have been caught by previous
     *            catch blocks
     * @param m
     *            method the exception is thrown in
     */
    protected ExceptionAssignmentStatement(ReferenceVariable thrown, ReferenceVariable caught, Set<IClass> notType,
                                    IMethod m) {
        super(m);
        assert notType != null;
        this.thrown = thrown;
        this.caught = caught;
        if (caught.getExpectedType().equals(TypeReference.JavaLangThrowable)) {
            this.filter = new TypeFilter((IClass) null, notType);
        }
        else {
            this.filter = new TypeFilter(caught.getExpectedType(), notType);
        }

    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                    StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, caught);
        PointsToGraphNode r;
        if (thrown.isSingleton()) {
            // This was a generated exception and the flag was set in StatementRegistrar so that only one reference
            // variable is created for each generated exception type
            r = new ReferenceVariableReplica(haf.initialContext(), thrown);
        } else {
            r = new ReferenceVariableReplica(context, thrown);
        }

        if (filter.isType == null && filter.notTypes.isEmpty()) {
            // If the type to filter on is null and the set of notTypes is empty then there is no need to filter
            return g.copyEdgesWithDelta(r, l, delta);
        }
        return g.copyFilteredEdgesWithDelta(r, filter, l, delta);
    }

    @Override
    public String toString() {
        return caught + " = (" + PrettyPrinter.typeString(caught.getExpectedType()) + ") " + thrown + " NOT "
                                        + filter.notTypes;
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0;
        thrown = newVariable;
    }

    @Override
    public ReferenceVariable getDef() {
        // Not really a local variable definition as it violates SSA invariant if there is more than one exception that
        // reaches this catch block
        return null;
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.singletonList(thrown);
    }

    public Set<IClass> getNotTypes() {
        return filter.notTypes;
    }

    /**
     * Get the exception being assigned to (either the catch formal or the procedure exit exception summary)
     * 
     * @return variable for exception being assigned to
     */
    public ReferenceVariable getCaughtException() {
        return caught;
    }
}
