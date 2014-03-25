package analysis.pointer.statements;

import java.util.List;

import util.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.LocalNode;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;

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
     * @param ir
     *            IR for the caller method
     * @param callee
     *            Method being called
     * @param receiver
     *            Receiver of the call
     * @param actuals
     *            Actual arguments to the call
     * @param resultNode
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param exceptionNode
     *            Node representing the exception thrown by this call (if any)
     */
    public StaticCallStatement(CallSiteReference callSite, IR ir, IMethod callee, List<LocalNode> actuals,
            LocalNode resultNode, LocalNode exceptionNode) {
        super(callSite, ir, actuals, resultNode, exceptionNode);
        this.callee = callee;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        Context calleeContext = haf.merge(getCallSite(), getCode(), null, context);
        return processCall(context, null, callee, calleeContext, g, registrar);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (getResultNode() != null) {
            s.append(getResultNode().toString() + " = ");
        }
        s.append("invokestatic " + PrettyPrinter.parseMethod(callee.getReference()));

        return s.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((callee == null) ? 0 : callee.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        StaticCallStatement other = (StaticCallStatement) obj;
        if (callee == null) {
            if (other.callee != null)
                return false;
        } else if (!callee.equals(other.callee))
            return false;
        return true;
    }
}
