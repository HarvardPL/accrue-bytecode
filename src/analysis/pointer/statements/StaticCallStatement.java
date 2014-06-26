package analysis.pointer.statements;

import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
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
    protected StaticCallStatement(CallSiteReference callSite, IMethod caller, IMethod callee, ReferenceVariable result,
                                    List<ReferenceVariable> actuals, ReferenceVariable exception,
                                    MethodSummaryNodes calleeSummary) {
        super(callSite, caller, result, actuals, exception);
        this.callee = callee;
        this.calleeSummary = calleeSummary;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
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
}
