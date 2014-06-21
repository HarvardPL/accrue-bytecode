package analysis.pointer.statements;

import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * Points-to statement for a call to a method
 */
public abstract class CallStatement extends PointsToStatement {

    /**
     * Call site
     */
    private final CallSiteLabel callSite;
    /**
     * Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     */
    private final ReferenceVariable result;
    /**
     * Actual arguments to the call
     */
    private final List<ReferenceVariable> actuals;
    /**
     * Node representing the exception thrown by this call (if any)
     */
    private final ReferenceVariable exception;

    /**
     * Points-to statement for a special method invocation.
     * 
     * @param callSite
     *            Method call site
     * @param caller
     *            caller method
     * @param result
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param actuals
     *            Actual arguments to the call
     * @param exception
     *            Node in the caller representing the exception thrown by this call (if any) also exceptions implicitly
     *            thrown by this statement
     */
    protected CallStatement(CallSiteReference callSite, IMethod caller, ReferenceVariable result,
                                    List<ReferenceVariable> actuals, ReferenceVariable exception) {
        super(caller);
        this.callSite = new CallSiteLabel(caller, callSite);
        this.actuals = actuals;
        this.result = result;
        this.exception = exception;
    }

    /**
     * Process a call for a particular receiver and resolved method
     * 
     * @param callerContext
     *            Calling context for the caller
     * @param receiver
     *            Heap context for the receiver
     * @param callee
     *            Actual method being called
     * @param g
     *            points-to graph (may be modified)
     * @param haf
     *            abstraction factory used for creating new context from existing
     * @param calleeSummary
     *            summary nodes for formals and exits of the callee
     * @return true if the points-to graph has changed
     */
    protected final GraphDelta processCall(Context callerContext, InstanceKey receiver, IMethod callee,
                                    PointsToGraph g, GraphDelta delta,
                                    HeapAbstractionFactory haf, MethodSummaryNodes calleeSummary) {
        assert calleeSummary != null;
        assert callee != null;
        assert calleeSummary != null;
        // if (callee.toString().contains("run")
        // && (AnalysisUtil.getClassHierarchy().isAssignableFrom(
        // AnalysisUtil.privilegedActionClass,
        // receiver.getConcreteType()) || AnalysisUtil
        // .getClassHierarchy()
        // .isAssignableFrom(AnalysisUtil.privilegedExceptionActionClass,
        // receiver.getConcreteType()))) {
        // System.err.print("");
        // }
        Context calleeContext = haf.merge(callSite, receiver, callerContext);
        // if (callee.toString().contains("run")
        // && (AnalysisUtil.getClassHierarchy().isAssignableFrom(
        // AnalysisUtil.privilegedActionClass,
        // receiver.getConcreteType()) || AnalysisUtil
        // .getClassHierarchy()
        // .isAssignableFrom(AnalysisUtil.privilegedExceptionActionClass,
        // receiver.getConcreteType()))) {
        // System.err.println(calleeContext + " for " + PrettyPrinter.methodString(callee));
        // System.err.print("");
        // }
        GraphDelta changed = new GraphDelta();

        // Record the call in the call graph
        g.addCall(callSite.getReference(), getMethod(), callerContext, callee, calleeContext);
        // XXX!@! should make g.addCall return something?

        // ////////////////// Return //////////////////

        // Add edge from the return formal to the result
        // If the result Node is null then either this is void return, there is
        // no assignment after the call, or the return type is not a reference
        if (result != null) {
            ReferenceVariableReplica resultRep = new ReferenceVariableReplica(callerContext, result);
            ReferenceVariableReplica calleeReturn = new ReferenceVariableReplica(calleeContext,
                                            calleeSummary.getReturn());

            // Check whether the types match up appropriately
            assert checkTypes(resultRep, calleeReturn);

            // The assignee can point to anything the return summary node in the callee can point to
            GraphDelta retChange = g.copyEdgesWithDelta(calleeReturn, resultRep, delta);
            changed = changed.combine(retChange);
        }

        // ////////////////// Receiver //////////////////

        // add edge from "this" in the callee to the receiver
        // if this is a static call then the receiver will be null
        if (!callee.isStatic()) {
            ReferenceVariableReplica thisRep = new ReferenceVariableReplica(calleeContext, calleeSummary.getFormal(0));
            GraphDelta receiverChange = g.addEdge(thisRep, receiver);
            changed = changed.combine(receiverChange);
        }

        // ////////////////// Formal Arguments //////////////////

        // The first formal for a non-static call is the receiver which is handled specially above
        int firstFormal = callee.isStatic() ? 0 : 1;

        // add edges from local variables (formals) in the callee to the actual arguments
        for (int i = firstFormal; i < actuals.size(); i++) {
            ReferenceVariable actual = actuals.get(i);
            if (actual == null) {
                // Not a reference type or null actual
                continue;
            }
            ReferenceVariableReplica actualRep = new ReferenceVariableReplica(callerContext, actual);

            ReferenceVariableReplica formalRep = new ReferenceVariableReplica(calleeContext, calleeSummary.getFormal(i));

            // Check whether the types match up appropriately
            assert checkTypes(formalRep, actualRep);


            // Add edges from the points-to set for the actual argument to the formal argument
            GraphDelta d1 = g.copyEdgesWithDelta(actualRep, formalRep, delta);
            changed = changed.combine(d1);
        }

        // ///////////////// Exceptions //////////////////

        ReferenceVariableReplica callerEx = new ReferenceVariableReplica(callerContext, exception);
        ReferenceVariableReplica calleeEx = new ReferenceVariableReplica(calleeContext, calleeSummary.getException());

        // The exception in the caller can point to anything the summary node in the callee can point to
        GraphDelta exChange = g.copyEdgesWithDelta(calleeEx, callerEx, delta);
        changed = changed.combine(exChange);

        return changed;
    }

    /**
     * Result of the call if any, null if void or primitive return or if the return result is not assigned
     * 
     * @return return result node (in the caller)
     */
    protected final ReferenceVariable getResult() {
        return result;
    }

    /**
     * Actual arguments to the call (if the type is primitive or it is a null literal then the entry will be null)
     * 
     * @return list of actual parameters
     */
    protected final List<ReferenceVariable> getActuals() {
        return actuals;
    }
}
