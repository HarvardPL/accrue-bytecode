package analysis.pointer.statements;

import java.util.Iterator;
import java.util.List;

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
    protected SpecialCallStatement(CallSiteReference callSite, IMethod caller,
            IMethod callee, ReferenceVariable result,
            ReferenceVariable receiver, List<ReferenceVariable> actuals,
            ReferenceVariable exception, MethodSummaryNodes calleeSummary) {
        super(callSite, caller, result, actuals, exception);
        this.callee = callee;
        this.receiver = receiver;
        this.calleeSummary = calleeSummary;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf,
            PointsToGraph g, GraphDelta delta, StatementRegistrar registrar) {
        ReferenceVariableReplica receiverRep =
                new ReferenceVariableReplica(context, receiver);
        GraphDelta changed = new GraphDelta(g);

        Iterator<InstanceKey> iter =
                delta == null
                        ? g.pointsToIterator(receiverRep)
                        : delta.pointsToIterator(receiverRep);
        while (iter.hasNext()) {
            InstanceKey recHeapCtxt = iter.next();
            changed =
                    changed.combine(processCall(context,
                                                recHeapCtxt,
                                                callee,
                                                g,
                                                haf,
                                                calleeSummary));
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
