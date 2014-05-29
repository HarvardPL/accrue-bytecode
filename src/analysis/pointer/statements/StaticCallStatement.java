package analysis.pointer.statements;

import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
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
     * Points-to statement for a static method invocation.
     * 
     * @param callSite
     *            Method call site
     * @param callee
     *            Method being called
     * @param resultNode
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param actuals
     *            Actual arguments to the call
     * @param exceptionNode
     *            Node representing the exception thrown by this call (if any)
     * @param receiver
     *            Receiver of the call
     * @param callerIR
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    protected StaticCallStatement(CallSiteReference callSite, IMethod callee, IMethod caller,
                                    ReferenceVariable resultNode, List<ReferenceVariable> actuals, ReferenceVariable exceptionNode) {
        super(callSite, actuals, resultNode, exceptionNode, callerIR, i, rvFactory);
        this.callee = callee;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        return processCall(context, null, callee, g, registrar, haf);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (getResultNode() != null) {
            s.append(getResultNode().toString() + " = ");
        }
        s.append("invokestatic " + PrettyPrinter.methodString(callee));

        return s.toString();
    }
}
