package analysis.pointer.statements;

import java.util.List;

import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.ReferenceVariable;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInvokeInstruction;

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
     * @param receiver
     *            Receiver of the call
     * @param actuals
     *            Actual arguments to the call
     * @param resultNode
     *            Node for the assignee if any (i.e. v in v = foo()), null if
     *            there is none or if it is a primitive
     * @param exceptionNode
     *            Node representing the exception thrown by this call (if any)
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    public StaticCallStatement(CallSiteReference callSite, IMethod callee, List<ReferenceVariable> actuals,
            ReferenceVariable resultNode, ReferenceVariable exceptionNode, IR ir, SSAInvokeInstruction i) {
        super(callSite, actuals, resultNode, exceptionNode, ir, i);
        this.callee = callee;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        Context calleeContext = haf.merge(getCallSite(), getCode(), null, context);

        if (WalaAnalysisUtil.INCLUDE_IMPLICIT_ERRORS) {
            // Otherwise, if execution of this invokestatic instruction causes
            // initialization of the referenced class, invokestatic may throw an
            // Error as detailed in 5.5.

            // Otherwise, if the resolved method is native and the code that
            // implements the method cannot be bound, invokestatic throws an
            // UnsatisfiedLinkError.

            // TODO handle errors for static call        
        }

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
