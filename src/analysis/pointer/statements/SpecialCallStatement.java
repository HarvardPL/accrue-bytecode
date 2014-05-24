package analysis.pointer.statements;

import java.util.List;

import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

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
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param exceptionNode
     *            Node representing the exception thrown by the callee and implicit exceptions
     * @param callerIR
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @param util
     *            Used to create the IR for the callee
     * @param rvFactory
     *            factory for managing the creation of reference variables for local variables and static fields
     */
    protected SpecialCallStatement(CallSiteReference callSite, IMethod resolvedCallee, ReferenceVariable receiver,
                                    List<ReferenceVariable> actuals, ReferenceVariable resultNode,
                                    ReferenceVariable exceptionNode, IR callerIR, SSAInvokeInstruction i,
                                    WalaAnalysisUtil util, ReferenceVariableFactory rvFactory) {
        super(callSite, actuals, resultNode, exceptionNode, callerIR, i, util, rvFactory);
        this.resultNode = resultNode;
        this.resolvedCallee = resolvedCallee;
        this.receiver = receiver;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        ReferenceVariableReplica receiverRep = getReplica(context, receiver);

        if (DEBUG && g.getPointsToSet(receiverRep).isEmpty()) {
            System.err.println("STATIC FIELD: " + receiverRep + "\n\t"
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }

        boolean changed = false;
        for (InstanceKey recHeapCtxt : g.getPointsToSet(receiverRep)) {
            Context calleeContext = haf.merge(getCallSiteLabel(), recHeapCtxt, context);
            changed |= processCall(context, recHeapCtxt, resolvedCallee, calleeContext, g, registrar);
        }
        return changed;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (resultNode != null) {
            s.append(resultNode.toString() + " = ");
        }
        s.append("invokespecial " + PrettyPrinter.methodString(resolvedCallee));

        return s.toString();
    }
}
