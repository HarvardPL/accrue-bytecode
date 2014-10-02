package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Statement for a special invoke statement.
 */
public class SpecialCallStatement extends CallStatement {

    /**
     * Method being called
     */
    private final IMethod callee;
    /**
     * Receiver of the call
     */
    private ReferenceVariable receiver;
    /**
     * summary nodes for formals and exits of the callee
     */
    private final MethodSummaryNodes calleeSummary;

    /**
     * Points-to statement for a special method invocation.
     *
     * @param callSite
     *            Method call site
     * @param caller
     *            caller method
     * @param callee
     *            Method being called
     * @param result
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param receiver
     *            Receiver of the call
     * @param actuals
     *            Actual arguments to the call
     * @param exception
     *            Node representing the exception thrown by the callee and implicit exceptions
     * @param calleeSummary
     *            summary nodes for formals and exits of the callee
     */
    protected SpecialCallStatement(CallSiteProgramPoint callerPP,
                                   IMethod callee, ReferenceVariable result,
                                   ReferenceVariable receiver, List<ReferenceVariable> actuals,
                                   ReferenceVariable exception, MethodSummaryNodes calleeSummary) {
        super(callerPP, result, actuals, exception);
        this.callee = callee;
        this.receiver = receiver;
        this.calleeSummary = calleeSummary;
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf,
                              PointsToGraph g, GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        ReferenceVariableReplica receiverRep =
 new ReferenceVariableReplica(context, this.receiver, haf);
        GraphDelta changed = new GraphDelta(g);

        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());

        Iterator<InstanceKeyRecency> iter = delta == null ? g.pointsToIterator(receiverRep, pre, originator)
                : delta.pointsToIterator(receiverRep, pre);
        while (iter.hasNext()) {
            InstanceKeyRecency recHeapCtxt = iter.next();
            changed = changed.combine(this.processCall(context, recHeapCtxt, this.callee, g, haf, this.calleeSummary));
        }
        return changed;

    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (this.getResult() != null) {
            s.append(this.getResult().toString() + " = ");
        }
        s.append("invokespecial " + PrettyPrinter.methodString(this.callee));

        s.append(" -- ");
        s.append(this.receiver);
        s.append(".");
        s.append(this.callee.getName());
        s.append("(");
        List<ReferenceVariable> actuals = this.getActuals();
        if (this.getActuals().size() > 1) {
            s.append(actuals.get(1));
        }
        for (int j = 2; j < actuals.size(); j++) {
            s.append(", ");
            s.append(actuals.get(j));
        }
        s.append(")");

        return s.toString();
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber <= this.getActuals().size() && useNumber >= 0;
        if (useNumber == 0) {
            this.receiver = newVariable;
            return;
        }
        this.replaceActual(useNumber - 1, newVariable);
    }

    /**
     * Variable for receiver followed by variables for arguments in order
     *
     * {@inheritDoc}
     */
    @Override
    public List<ReferenceVariable> getUses() {
        List<ReferenceVariable> uses = new ArrayList<>(this.getActuals().size() + 1);
        uses.add(this.receiver);
        uses.addAll(this.getActuals());
        return uses;
    }

    @Override
    public ReferenceVariable getDef() {
        return this.getResult();
    }

    @Override
    public Collection<?> getReadDependencies(Context ctxt, HeapAbstractionFactory haf) {
        List<Object> uses = new ArrayList<>(this.getActuals()
                .size() + 3);
        uses.add(new ReferenceVariableReplica(ctxt, this.receiver, haf));
        for (ReferenceVariable use : this.getActuals()) {
            if (use != null) {
                ReferenceVariableReplica n = new ReferenceVariableReplica(ctxt, use, haf);
                uses.add(n);
            }
        }

        // Add the exception variable. Can't be more precise here unfortunately...
        uses.add(this.calleeSummary.getException());

        return uses;
    }

    @Override
    public Collection<?> getWriteDependencies(Context ctxt, HeapAbstractionFactory haf) {
        List<Object> defs = new ArrayList<>(3);

        if (this.getResult() != null) {
            defs.add(new ReferenceVariableReplica(ctxt, this.getResult(), haf));
        }
        if (this.getException() != null) {
            defs.add(new ReferenceVariableReplica(ctxt, this.getException(), haf));
        }
        // add the IMethod of the callee so that we get run before
        // the local-to-local's of the callee's method summaries
        defs.add(this.callee);
        return defs;
    }

    /**
     * Get the resolved method being called
     *
     * @return method being called
     */
    protected IMethod getResolvedCallee() {
        return callee;
    }
}
