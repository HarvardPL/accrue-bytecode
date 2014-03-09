package pointer.statements;

import java.util.List;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.LocalNode;
import pointer.graph.PointsToGraph;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * Statement for a special invoke statement.
 */
public class SpecialCallStatement extends CallStatement {

    /**
     * Delegate virtual call statement 
     * TODO should something different happen for special invoke?
     */
    private final VirtualCallStatement virtCall;

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
     *            Node for the assignee if any (i.e. v in v = foo())
     * @param exceptionNode
     *            Node representing the exception thrown by this call (if any)
     */
    public SpecialCallStatement(CallSiteReference callSite, IR ir, MethodReference callee, LocalNode receiver,
            List<LocalNode> actuals, LocalNode resultNode, LocalNode exceptionNode) {
        virtCall = new VirtualCallStatement(callSite, ir, callee, receiver, actuals, resultNode, exceptionNode);
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        return virtCall.process(context, haf, g, registrar);
    }

    @Override
    public TypeReference getExpectedType() {
        return virtCall.getExpectedType();
    }

    @Override
    public IR getCode() {
        return virtCall.getCode();
    }
}
