package pointer.statements;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pointer.graph.LocalNode;
import pointer.graph.MethodSummaryNodes;
import pointer.graph.PointsToGraph;
import pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.types.TypeReference;

/**
 * Points-to statement for a call to a method
 */
public abstract class CallStatement extends PointsToStatement {

    /**
     * Call site
     */
    private final CallSiteReference callSite;
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
     * Points-to statement for a special method invocation.
     * 
     * @param callSite
     *            Method call site
     * @param ir
     *            IR for the caller method
     * @param callee
     *            Method being called
     * @param actuals
     *            Actual arguments to the call
     * @param resultNode
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param exceptionNode
     *            Node in the caller representing the exception thrown by this call (if any)
     */
    public CallStatement(CallSiteReference callSite, IR callerIR, List<LocalNode> actuals, LocalNode resultNode, LocalNode exceptionNode) {
        super(callerIR);
        this.callSite = callSite;
        this.actuals = actuals;
        this.resultNode = resultNode;
        this.exceptionNode = exceptionNode;
    }

    /**
     * Process a call for a particular receiver and resolved method
     * 
     * @param callerContext
     *            Calling context for the caller
     * @param receiver
     *            Heap context for the receiver
     * @param resolvedCallee
     *            Actual method being called
     * @param calleeContext
     *            Calling context for the callee
     * @param g
     *            points-to graph
     * @param registrar
     *            registrar for points-to statements
     * @return true if the points-to graph has changed
     */
    protected boolean processCall(Context callerContext, InstanceKey receiver, IMethod resolvedCallee,
            Context calleeContext, PointsToGraph g, StatementRegistrar registrar) {   
        if (resolvedCallee.isNative()) {
            // TODO Pointer Analysis not handling native methods yet
            return false;          
        }
        
        boolean changed = false;

        // Record the call
        changed |= g.addCall(getCode().getMethod(), callerContext, resolvedCallee, calleeContext);

        MethodSummaryNodes calleeSummary = registrar.getSummaryNodes(resolvedCallee);

        // add edge from "this" in the callee to the receiver
        // if this is a static call then the receiver will be null
        if (receiver != null) {
            ReferenceVariableReplica thisRep = new ReferenceVariableReplica(calleeContext, calleeSummary.getThisNode());
            changed |= g.addEdge(thisRep, receiver);
        }

        // add edges from the formal arguments to the actual arguments
        List<ReferenceVariableReplica> actualReps = getReplicas(callerContext, actuals);
        List<LocalNode> formals = calleeSummary.getFormals();
        for (int i = 0; i < actuals.size(); i++) {
            ReferenceVariableReplica actual = actualReps.get(i);
            LocalNode formal = formals.get(i);
            if (actual == null || formal == null) {
                // Not a reference type or null actual
                continue;
            }

            ReferenceVariableReplica formalRep = new ReferenceVariableReplica(calleeContext, formal);

            Set<InstanceKey> actualHeapContexts = g.getPointsToSetFiltered(actual, formal.getExpectedType());
            changed |= g.addEdges(formalRep, actualHeapContexts);
        }

        // Add edge from the return formal to the result
        // If the result Node is null then either this is void return, there is
        // no assignment after the call, or the return type is not a reference
        ReferenceVariableReplica resultRep = null;
        if (resultNode != null) {
            assert (calleeSummary.getReturnNode() != null);
            resultRep = getReplica(callerContext, getResultNode());
            ReferenceVariableReplica returnValueFormal = new ReferenceVariableReplica(calleeContext,
                    calleeSummary.getReturnNode());

            changed |= g
                    .addEdges(resultRep, g.getPointsToSetFiltered(returnValueFormal, resultRep.getExpectedType()));
        }
        
        /////////////////// Exceptions //////////////////
        
        // Get the basic block for the call 
        // TODO why is this an array?
        ISSABasicBlock[] bbs = getCode().getBasicBlocksForCall(getCallSite());
        if (bbs.length > 1) {
            throw new RuntimeException("More than one basic block for a call");
        }
        
        // Find successor catch blocks
        List<CatchBlock> catchBlocks = getSuccessorCatchBlocks(bbs[0], registrar, callerContext);
        
        // Gather all the exceptions thrown by the caller in the current
        // (caller) context
        Map<TypeReference, ReferenceVariableReplica> callerThrows = getExceptionReplicas(callerContext, registrar);
        
        // TODO could have one replica per type for precision in the caller
        ReferenceVariableReplica exceptionRep = getReplica(callerContext, exceptionNode);
        for (TypeReference exType : calleeSummary.getExceptions().keySet()) {
            // Add edges from the exception in the callee to the exception in the caller
            ReferenceVariableReplica calleeEx = new ReferenceVariableReplica(calleeContext, calleeSummary.getExceptions().get(exType));
            changed |= g.addEdges(exceptionRep, g.getPointsToSetFiltered(calleeEx, exType));
            // check if the exception is caught or rethrown by this procedure
            changed |= checkThrown(exType, exceptionRep, g, callerThrows, catchBlocks);
        }
        return changed;
    }

    /**
     * Get reference variable replicas in the given context for each element of
     * the list of Reference variables
     * @param context
     *            Context the replicas will be created in
     * @param actuals
     *            reference variables to get replicas for
     * 
     * @return list of replicas for the given reference variables
     */
    protected List<ReferenceVariableReplica> getReplicas(Context context, List<LocalNode> actuals) {
        List<ReferenceVariableReplica> actualReps = new LinkedList<>();
        for (LocalNode actual : actuals) {
            if (actual == null) {
                // not a reference type
                actualReps.add(null);
                continue;
            }
            actualReps.add(new ReferenceVariableReplica(context, actual));
        }
        return actualReps;
    }

    /**
     * Get reference variable replica in the given context for a reference
     * variable
     * 
     * @param context
     *            Context the replica will be created in
     * @param r
     *            reference variable to get replica for
     * @return replica for the given reference variable
     */
    protected ReferenceVariableReplica getReplica(Context context, LocalNode r) {
        return new ReferenceVariableReplica(context, r);
    }

    /**
     * @return the callSite
     */
    protected CallSiteReference getCallSite() {
        return callSite;
    }

    /**
     * Result of the call if any, null if void or primitive return or if the return result is not assigned
     * 
     * @return return result node (in the caller)
     */
    public LocalNode getResultNode() {
        return resultNode;
    }
}
