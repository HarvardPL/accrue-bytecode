package pointer.statements;

import java.util.LinkedList;
import java.util.List;

import pointer.LocalNode;
import pointer.PointsToGraph;
import pointer.ReferenceVariableReplica;
import pointer.analyses.HeapAbstractionFactory;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * Points to statement for a call to a virtual method (either a class or
 * interface method).
 */
public class VirtualCallStatement extends CallStatement {

    /**
     * Method call site
     */
    private final CallSiteReference callSite;
    /**
     * IR for the caller method
     */
    private final IR ir;
    /**
     * Method being called
     */
    private final MethodReference callee;
    /**
     * Receiver of the call
     */
    private final LocalNode receiver;
    /**
     * Actual arguments to the call
     */
    private final List<LocalNode> actuals;
    /**
     * Node for the assignee if any (i.e. v in v = foo())
     */
    private final LocalNode resultNode;
    /**
     * Node representing the exception thrown by this call (if any)
     */
    private final LocalNode exceptionNode;

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
     *            Node for the assignee if any (i.e. v in v = foo())
     * @param exceptionNode
     *            Node representing the exception thrown by this call (if any)
     */
    public VirtualCallStatement(CallSiteReference callSite, IR ir, MethodReference callee, LocalNode receiver,
            List<LocalNode> actuals, LocalNode resultNode, LocalNode exceptionNode) {
        this.callSite = callSite;
        this.ir = ir;
        this.callee = callee;
        this.receiver = receiver;
        this.actuals = actuals;
        this.resultNode = resultNode;
        this.exceptionNode = exceptionNode;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {

        ReferenceVariableReplica resultRep = new ReferenceVariableReplica(context, resultNode);
        ReferenceVariableReplica exceptionRep = new ReferenceVariableReplica(context, exceptionNode);
        List<ReferenceVariableReplica> actualReps = new LinkedList<>();
        for (LocalNode actual : actuals) {
            if (actual == null) {
                // not a reference type
                actualReps.add(null);
            }
            ReferenceVariableReplica actualRep = new ReferenceVariableReplica(context, actual);
            actualReps.add(actualRep);
        }
        ReferenceVariableReplica receiverRep = new ReferenceVariableReplica(context, receiver);

        boolean changed = false;
        for (InstanceKey recHeapContext : g.getPointsToSet(receiverRep)) {
            changed |= processCall(callSite, ir, context, callee, recHeapContext, actualReps, resultRep, exceptionRep,
                    haf, g, registrar);
        }

        return changed;
    }

    @Override
    public TypeReference getExpectedType() {
        return resultNode.getExpectedType();
    }
}
