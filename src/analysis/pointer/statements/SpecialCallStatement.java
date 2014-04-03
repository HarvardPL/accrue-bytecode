package analysis.pointer.statements;

import java.util.List;

import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.ReferenceVariable;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInvokeInstruction;

/**
 * Statement for a special invoke statement.
 */
public class SpecialCallStatement extends CallStatement {

    /**
     * Reference variable for the assignee (if any)
     */
    private final ReferenceVariable resultNode;
    /**
     * Method being called
     */
    private final IMethod resolvedCallee;
    /**
     * Receiver of the call
     */
    private final ReferenceVariable receiver;

    /**
     * Points-to statement for a special method invocation.
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
     *            Node representing the exception thrown by the callee and
     *            implicit exceptions
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    public SpecialCallStatement(CallSiteReference callSite, IMethod resolvedCallee, ReferenceVariable receiver,
            List<ReferenceVariable> actuals, ReferenceVariable resultNode, ReferenceVariable exceptionNode, IR ir, SSAInvokeInstruction i) {
        super(callSite, actuals, resultNode, exceptionNode, ir, i);
        this.resultNode = resultNode;
        this.resolvedCallee = resolvedCallee;
        this.receiver = receiver;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        ReferenceVariableReplica receiverRep = getReplica(context, receiver);

        boolean changed = false;
        for (InstanceKey recHeapContext : g.getPointsToSet(receiverRep)) {
            Context calleeContext = haf.merge(getCallSite(), getCode(), recHeapContext, context);
            changed |= processCall(context, recHeapContext, resolvedCallee, calleeContext, g, registrar);
        }
        
        // Otherwise, if objectref is null, the invokespecial instruction throws
        // a NullPointerException.     
        changed |= checkAllThrown(context, g, registrar);
        
        if (WalaAnalysisUtil.INCLUDE_IMPLICIT_ERRORS) {
            // Otherwise, if no method matching the resolved name and descriptor
            // is selected, invokespecial throws an AbstractMethodError.

            // Otherwise, if the selected method is abstract, invokespecial
            // throws an AbstractMethodError.

            // Otherwise, if the selected method is native and the code that
            // implements the method cannot be bound, invokespecial throws an
            // UnsatisfiedLinkError.
            
            // TODO handle errors for special call
        }

        return changed;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (resultNode != null) {
            s.append(resultNode.toString() + " = ");
        }
        s.append("invokespecial " + PrettyPrinter.parseMethod(resolvedCallee.getReference()));

        return s.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((receiver == null) ? 0 : receiver.hashCode());
        result = prime * result + ((resolvedCallee == null) ? 0 : resolvedCallee.hashCode());
        result = prime * result + ((resultNode == null) ? 0 : resultNode.hashCode());
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
        SpecialCallStatement other = (SpecialCallStatement) obj;
        if (receiver == null) {
            if (other.receiver != null)
                return false;
        } else if (!receiver.equals(other.receiver))
            return false;
        if (resolvedCallee == null) {
            if (other.resolvedCallee != null)
                return false;
        } else if (!resolvedCallee.equals(other.resolvedCallee))
            return false;
        if (resultNode == null) {
            if (other.resultNode != null)
                return false;
        } else if (!resultNode.equals(other.resultNode))
            return false;
        return true;
    }
}
