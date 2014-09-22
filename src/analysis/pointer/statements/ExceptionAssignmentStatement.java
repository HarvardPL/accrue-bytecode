package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
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
     * Is right from a method summary?
     */
    private final boolean isToMethodSummaryVariable;

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
    protected ExceptionAssignmentStatement(ReferenceVariable thrown,
                                           ReferenceVariable caught,
 Set<IClass> notType,
                                           ProgramPoint pp,
                                           boolean isToMethodSummaryVariable) {
        super(pp);
        assert notType != null;
        this.isToMethodSummaryVariable = isToMethodSummaryVariable;
        this.thrown = thrown;
        this.caught = caught;
        if (caught.getExpectedType().equals(TypeReference.JavaLangThrowable)) {
            if (notType.isEmpty()) {
                this.filter = null;
            }
            else {
                this.filter = TypeFilter.create((IClass) null, notType);
            }
        }
        else {
            this.filter = TypeFilter.create(caught.getExpectedType(), notType);
        }

    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf,
                              PointsToGraph g, GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, this.caught);
        PointsToGraphNode r;
        if (this.thrown.isSingleton()) {
            // This was a generated exception and the flag was set in StatementRegistrar so that only one reference
            // variable is created for each generated exception type
            r = new ReferenceVariableReplica(haf.initialContext(), this.thrown);
        }
        else {
            r = new ReferenceVariableReplica(context, this.thrown);
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
        return this.caught + " = ("
                + PrettyPrinter.typeString(this.caught.getExpectedType()) + ") "
                + this.thrown + " NOT "
                + (this.filter == null ? "empty" : this.filter.notTypes);
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0;
        this.thrown = newVariable;
    }

    @Override
    public ReferenceVariable getDef() {
        // Not really a local variable definition as it violates SSA invariant if there is more than one exception that
        // reaches this catch block
        return null;
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
    public Collection<?> getReadDependencies(Context ctxt,
                                             HeapAbstractionFactory haf) {
        ReferenceVariableReplica r;
        if (this.thrown.isSingleton()) {
            // This was a generated exception and the flag was set in StatementRegistrar so that only one reference
            // variable is created for each generated exception type
            r = new ReferenceVariableReplica(haf.initialContext(), this.thrown);
        }
        else {
            r = new ReferenceVariableReplica(ctxt, this.thrown);
        }
        return Collections.singleton(r);
    }

    @Override
    public Collection<?> getWriteDependencies(Context ctxt,
                                              HeapAbstractionFactory haf) {
        ReferenceVariableReplica r = new ReferenceVariableReplica(ctxt,
                                                                  this.caught);
        if (this.isToMethodSummaryVariable && !this.getMethod().isStatic()) {
            List<Object> defs = new ArrayList<>(3);
            defs.add(r);
            defs.add(this.caught);
            if (!this.getMethod().isPrivate()) {
                // Add in a special object for the exceptional return, so that virtual call statements can
                // have a read dependency on it...
                defs.add(new OrderedPair<>(this.getMethod().getSelector(),
                        "ex-return"));
            }
            return defs;
        }
        else {
            return Collections.singleton(r);
        }
    }

}
