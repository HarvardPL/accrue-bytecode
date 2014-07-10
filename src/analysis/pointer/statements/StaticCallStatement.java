package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to statement for a static method call
 */
public class StaticCallStatement extends CallStatement {

    /**
     * Called method
     */
    private final IMethod callee;
    /**
     * summary nodes for formals and exits of the callee
     */
    private final MethodSummaryNodes calleeSummary;

    /**
     * Points-to statement for a static method invocation.
     * 
     * @param callSite
     *            Method call site
     * @param caller
     *            caller method
     * @param callee
     *            Method being called
     * @param result
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param actuals
     *            Actual arguments to the call
     * @param exception
     *            Node representing the exception thrown by this call (if any)
     * @param calleeSummary
     *            summary nodes for formals and exits of the callee
     * @param receiver
     *            Receiver of the call
     */
    protected StaticCallStatement(CallSiteReference callSite, IMethod caller,
            IMethod callee, ReferenceVariable result,
            List<ReferenceVariable> actuals, ReferenceVariable exception,
            MethodSummaryNodes calleeSummary) {
        super(callSite, caller, result, actuals, exception);
        this.callee = callee;
        this.calleeSummary = calleeSummary;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf,
            PointsToGraph g, GraphDelta delta, StatementRegistrar registrar) {
        return processCall(context, null, callee, g, haf, calleeSummary);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (getResult() != null) {
            s.append(getResult().toString() + " = ");
        }
        s.append("invokestatic " + PrettyPrinter.methodString(callee));

        s.append(" -- ");
        s.append(PrettyPrinter.typeString(callee.getDeclaringClass()));
        s.append(".");
        s.append(callee.getName());
        s.append("(");
        List<ReferenceVariable> actuals = getActuals();
        if (getActuals().size() > 0) {
            s.append(actuals.get(0));
        }
        for (int j = 1; j < actuals.size(); j++) {
            s.append(", ");
            s.append(actuals.get(j));
        }
        s.append(")");

        return s.toString();
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        replaceActual(useNumber, newVariable);
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return getActuals();
    }

    @Override
    public ReferenceVariable getDef() {
        return getResult();
    }

    @Override
    public Collection<?> getReadDependencies(Context ctxt, HeapAbstractionFactory haf) {
        List<ReferenceVariableReplica> uses =
                new ArrayList<>(Math.max(getActuals().size(), 1));

        for (ReferenceVariable use : getActuals()) {
            if (use != null) {
                ReferenceVariableReplica n =
                        new ReferenceVariableReplica(ctxt, use);
                uses.add(n);
            }
        }
        if (getActuals().isEmpty() && callee.getReturnType().isReferenceType()) {
            // there aren't actuals, and there is reference type for the return.
            // Say that we read the return of the callee.
            Context calleeContext = haf.merge(callSite, null, ctxt);
            ReferenceVariableReplica n =
                    new ReferenceVariableReplica(calleeContext,
                                                 calleeSummary.getReturn());
            uses.add(n);
        }
        return uses;
    }

    @Override
    public Collection<?> getWriteDependencis(Context ctxt, HeapAbstractionFactory haf) {
        List<ReferenceVariableReplica> defs =
                new ArrayList<>(2 + callee.getNumberOfParameters());

        if (getResult() != null) {
            defs.add(new ReferenceVariableReplica(ctxt, getResult()));
        }
        if (getException() != null) {
            defs.add(new ReferenceVariableReplica(ctxt, getException()));
        }
        // Write to the arguments of the callee.
        Context calleeContext = haf.merge(callSite, null, ctxt);
        for (int i = 0; i < callee.getNumberOfParameters(); i++) {
            ReferenceVariable rv = calleeSummary.getFormal(i);
            if (rv != null) {
                ReferenceVariableReplica n =
                        new ReferenceVariableReplica(calleeContext, rv);
                defs.add(n);
            }
        }
        return defs;
    }
}
