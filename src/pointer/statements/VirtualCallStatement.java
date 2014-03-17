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
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.MethodReference;

/**
 * Points-to statement for a call to a virtual method (either a class or
 * interface method).
 */
public class VirtualCallStatement extends CallStatement {

    /**
     * Called method
     */
    private final MethodReference callee;

    /**
     * Class hierarchy
     */
    private final IClassHierarchy cha;
    /**
     * Reference variable for the receiver of the call
     */
    private final LocalNode receiver;

    /**
     * Points-to statement for a virtual method invocation.
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
     * @param cha
     *            Class hierarchy
     */
    public VirtualCallStatement(CallSiteReference callSite, IR ir, MethodReference callee, LocalNode receiver,
            List<LocalNode> actuals, LocalNode resultNode, LocalNode exceptionNode, IClassHierarchy cha) {
        super(callSite, ir, actuals, resultNode, exceptionNode);
        this.callee = callee;
        this.cha = cha;
        this.receiver = receiver;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        ReferenceVariableReplica receiverRep = getReplica(context, receiver);

        boolean changed = false;
        for (InstanceKey recHeapContext : g.getPointsToSet(receiverRep)) {
            // find the callee.
            // The receiver is recHeapContext, and we want to find a method that matches selector
            // callee.getSelector() in class recHeapContext.getConcreteType() or a superclass.
            IMethod resolvedCallee = cha.resolveMethod(recHeapContext.getConcreteType(), callee.getSelector());
            
            // If we wanted to be very robust, check to make sure that resolvedCallee overrides
            // the IMethod returned by ch.resolveMethod(callee).
            
            Context calleeContext = haf.merge(getCallSite(), getCode(), recHeapContext, context);
            changed |= processCall(context, recHeapContext, resolvedCallee,
                    calleeContext, g, registrar);
        }

        return changed;
    }
    
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (getResultNode() != null) {
            s.append(getResultNode().toString() + " = ");
        } 
        s.append("invokevirtual " + PrettyPrinter.parseMethod(getCallSite().getDeclaredTarget()));
        
        return s.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((callee == null) ? 0 : callee.hashCode());
        result = prime * result + ((cha == null) ? 0 : cha.hashCode());
        result = prime * result + ((receiver == null) ? 0 : receiver.hashCode());
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
        VirtualCallStatement other = (VirtualCallStatement) obj;
        if (callee == null) {
            if (other.callee != null)
                return false;
        } else if (!callee.equals(other.callee))
            return false;
        if (cha == null) {
            if (other.cha != null)
                return false;
        } else if (!cha.equals(other.cha))
            return false;
        if (receiver == null) {
            if (other.receiver != null)
                return false;
        } else if (!receiver.equals(other.receiver))
            return false;
        return true;
    }
}
