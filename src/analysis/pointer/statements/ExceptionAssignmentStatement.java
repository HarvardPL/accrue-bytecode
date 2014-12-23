package analysis.pointer.statements;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.TypeFilter;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.classLoader.IClass;
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
     * @param thrown reference variable for the exception being thrown
     * @param caught reference variable for the caught exception (or summary for the method exit)
     * @param notType types that the exception being caught cannot have since those types must have been caught by
     *            previous catch blocks
     * @param pp program point the exception is thrown at
     */
    protected ExceptionAssignmentStatement(ReferenceVariable thrown, ReferenceVariable caught, Set<IClass> notType,
                                           ProgramPoint pp) {
        super(pp);
        assert notType != null;
        assert !thrown.isFlowSensitive();
        assert !caught.isFlowSensitive();
        this.thrown = thrown;
        this.caught = caught;
        if (caught.getExpectedType().equals(TypeReference.JavaLangThrowable)) {
            if (notType.isEmpty()) {
                this.filter = null;
            }
            else {
                this.filter = TypeFilter.create((IClass) null, notType, false); // don't allow null type, as no null value is ever thrown.
            }
        }
        else {
            this.filter = TypeFilter.create(caught.getExpectedType(), notType, false); // don't allow null type, as no null value is ever thrown.
        }

    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, this.caught, haf);
        PointsToGraphNode r;
        if (this.thrown.isSingleton()) {
            // This was a generated exception and the flag was set in StatementRegistrar so that only one reference
            // variable is created for each generated exception type
            r = new ReferenceVariableReplica(haf.initialContext(), this.thrown, haf);
        }
        else {
            r = new ReferenceVariableReplica(context, this.thrown, haf);
        }

        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());
        InterProgramPointReplica post = InterProgramPointReplica.create(context, this.programPoint().post());

        // don't need to use delta, as this just adds a subset edge
        if (this.filter == null) {
            return g.copyEdges(r, pre, l, post);
        }
        return g.copyFilteredEdges(r, this.filter, l);

    }

    @Override
    public String toString() {
        return this.caught + " = (" + PrettyPrinter.typeString(this.caught.getExpectedType()) + ") " + this.thrown
                + " NOT " + (this.filter == null ? "empty" : this.filter.notTypes);
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0;
        this.thrown = newVariable;
    }

    @Override
    public List<ReferenceVariable> getDefs() {
        // Not really a local variable definition as it violates SSA invariant if there is more than one exception that
        // reaches this catch block
        return Collections.emptyList();
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.singletonList(this.thrown);
    }

    public Set<IClass> getNotTypes() {
        if (this.filter == null || this.filter.notTypes == null) {
            return Collections.emptySet();
        }
        return this.filter.notTypes;
    }

    /**
     * Get the exception being assigned to (either the catch formal or the procedure exit exception summary)
     *
     * @return variable for exception being assigned to
     */
    public ReferenceVariable getCaughtException() {
        return this.caught;
    }

    @Override
    public boolean mayChangeOrUseFlowSensPointsToGraph() {
        assert !thrown.isFlowSensitive();
        assert !caught.isFlowSensitive();
        // neither variable is flow sensitive, but if the thrown variable has local scope, then
        // we may need to determine where it has been used
        return this.thrown.hasLocalScope();
    }

}
