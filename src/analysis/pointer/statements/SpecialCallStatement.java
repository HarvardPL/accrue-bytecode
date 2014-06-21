package analysis.pointer.statements;

import java.util.List;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * Statement for a special invoke statement.
 */
public class SpecialCallStatement extends CallStatement {

    /**
     * Method being called
     */
    private final IMethod callee;
    /**
     * Receiver of the call
     */
    private final ReferenceVariable receiver;
    /**
     * summary nodes for formals and exits of the callee
     */
    private final MethodSummaryNodes calleeSummary;

    /**
     * Points-to statement for a special method invocation.
     * 
     * @param callSite
     *            Method call site
     * @param caller
     *            caller method
     * @param callee
     *            Method being called
     * @param result
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param receiver
     *            Receiver of the call
     * @param actuals
     *            Actual arguments to the call
     * @param exception
     *            Node representing the exception thrown by the callee and implicit exceptions
     * @param calleeSummary
     *            summary nodes for formals and exits of the callee
     */
    protected SpecialCallStatement(CallSiteReference callSite, IMethod caller, IMethod callee,
                                    ReferenceVariable result, ReferenceVariable receiver,
                                    List<ReferenceVariable> actuals, ReferenceVariable exception,
                                    MethodSummaryNodes calleeSummary) {
        super(callSite, caller, result, actuals, exception);
        this.callee = callee;
        this.receiver = receiver;
        this.calleeSummary = calleeSummary;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                    StatementRegistrar registrar) {
        ReferenceVariableReplica receiverRep = new ReferenceVariableReplica(context, receiver);
        GraphDelta changed = new GraphDelta();

        if (delta == null) {
            // no delta, do the simple processing
            Set<InstanceKey> s = g.getPointsToSet(receiverRep);
            assert checkForNonEmpty(s, receiverRep, "SPECIAL RECEIVER");

            for (InstanceKey recHeapCtxt : s) {
                changed = changed.combine(processCall(context, recHeapCtxt, callee, g, delta, haf, calleeSummary));
            }
        }
        else {
            // delta is not null. Let's be smart.
            // First, see if the receiver has changed.
            Set<InstanceKey> s = delta.getPointsToSet(receiverRep);
            if (!s.isEmpty()) {
                // the receiver changed, so process the call *without* a delta, i.e., to force everything
                // to propagate
                for (InstanceKey recHeapCtxt : s) {
                    changed = changed.combine(processCall(context, recHeapCtxt, callee, g, null, haf, calleeSummary));
                }
            }

            // we'll be a little lazy here, and just do the deltas for every callee...
            for (InstanceKey recHeapCtxt : g.getPointsToSet(receiverRep)) {
                changed = changed.combine(processCall(context, recHeapCtxt, callee, g, delta, haf, calleeSummary));
            }

        }
        return changed;

    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (getResult() != null) {
            s.append(getResult().toString() + " = ");
        }
        s.append("invokespecial " + PrettyPrinter.methodString(callee));

        return s.toString();
    }
}
