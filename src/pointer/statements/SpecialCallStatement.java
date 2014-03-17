package pointer.statements;

import java.util.List;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.LocalNode;
import pointer.graph.PointsToGraph;
import pointer.graph.ReferenceVariableReplica;
import util.PrettyPrinter;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;

/**
 * Statement for a special invoke statement.
 */
public class SpecialCallStatement extends CallStatement {

    /**
     * Reference variable for the assignee (if any)
     */
    private final LocalNode resultNode;
    /**
     * Method being called
     */
    private final IMethod resolvedCallee;
    /**
     * Receiver of the call
     */
    private final LocalNode receiver;

    /**
     * Points-to statement for a special method invocation.
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
     *            Node representing the exception thrown by this call
     */
    public SpecialCallStatement(CallSiteReference callSite, IR ir, IMethod resolvedCallee, LocalNode receiver,
            List<LocalNode> actuals, LocalNode resultNode, LocalNode exceptionNode) {
        super(callSite, ir, actuals, resultNode, exceptionNode);
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
