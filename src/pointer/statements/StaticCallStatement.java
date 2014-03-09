package pointer.statements;

import java.util.LinkedList;
import java.util.List;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.LocalNode;
import pointer.graph.PointsToGraph;
import pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * Points-to statement for a static method call
 */
public class StaticCallStatement extends CallStatement {

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
     *            Node for the assignee if any (i.e. v in v = foo())
     * @param exceptionNode
     *            Node representing the exception thrown by this call (if any)
     */
    public StaticCallStatement(CallSiteReference callSite, IR ir, MethodReference callee, List<LocalNode> actuals,
            LocalNode resultNode, LocalNode exceptionNode) {
        this.callSite = callSite;
        this.ir = ir;
        this.callee = callee;
        this.actuals = actuals;
        this.resultNode = resultNode;
        this.exceptionNode = exceptionNode;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        List<ReferenceVariableReplica> actualReps = new LinkedList<>();
        for (LocalNode actual : actuals) {
            if (actuals == null) {
                actualReps.add(null);
                continue;
            }
            actualReps.add(new ReferenceVariableReplica(context, actual));
        }

        ReferenceVariableReplica resultRep = new ReferenceVariableReplica(context, resultNode);
        ReferenceVariableReplica exceptionRep = new ReferenceVariableReplica(context, exceptionNode);

        return processCall(callSite, ir, context, callee, null, actualReps, resultRep, exceptionRep, haf, g, registrar);
    }

    @Override
    public TypeReference getExpectedType() {
        return callSite.getDeclaredTarget().getReturnType();
    }

    @Override
    public IR getCode() {
        return ir;
    }
}
