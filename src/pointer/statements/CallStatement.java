package pointer.statements;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import pointer.graph.LocalNode;
import pointer.graph.MethodSummaryNodes;
import pointer.graph.PointsToGraph;
import pointer.graph.ReferenceVariableReplica;
import util.PrettyPrinter;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Points-to statement for a call to a method
 */
public abstract class CallStatement implements PointsToStatement {

    /**
     * Call site
     */
    private final CallSiteReference callSite;
    /**
     * Caller code
     */
    private final IR callerIR;
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
     *            Node representing the exception thrown by this call (if any)
     */
    public CallStatement(CallSiteReference callSite, IR callerIR, List<LocalNode> actuals, LocalNode resultNode, LocalNode exceptionNode) {
        this.callSite = callSite;
        this.callerIR = callerIR;
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
            System.err.println("Pointer Analysis not handling native methods yet: " + resolvedCallee);
            return false;          
        }
        
        boolean changed = false;

        // Record the call
        changed |= g.addCall(callSite, callerContext, resolvedCallee, calleeContext);

        MethodSummaryNodes summary = registrar.getSummaryNodes(resolvedCallee);

        // add edge from "this" in the callee to the receiver
        // if this is a static call then the receiver will be null
        if (receiver != null) {
            ReferenceVariableReplica thisRep = new ReferenceVariableReplica(calleeContext, summary.getThisNode());
            changed |= g.addEdge(thisRep, receiver);
        }

        // add edges from the formal arguments to the actual arguments
        List<ReferenceVariableReplica> actualReps = getReplicas(callerContext, actuals);
        List<LocalNode> formals = summary.getFormals();
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
            assert (summary.getReturnNode() != null);
            resultRep = getReplica(callerContext, getResultNode());
            ReferenceVariableReplica returnValueFormal = new ReferenceVariableReplica(calleeContext,
                    summary.getReturnNode());

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
        
        // Find successor catch blocks in the CFG
        SSACFG cfg = getCode().getControlFlowGraph();
        List<ISSABasicBlock> catchBasicBlocks = cfg.getExceptionalSuccessors(bbs[0]);
        
        // Find exceptions caught by each successor block
        List<CatchBlock> catchBlocks = new LinkedList<>();
        for (ISSABasicBlock bb : catchBasicBlocks) {
            Iterator<TypeReference> types = bb.getCaughtExceptionTypes();
            // The catch instruction is the first instruction in the basic block
            SSAGetCaughtExceptionInstruction catchIns = (SSAGetCaughtExceptionInstruction) bb.iterator().next();
            LocalNode formalNode = registrar.getLocal(catchIns.getException(), getCode());
            ReferenceVariableReplica formalRep = new ReferenceVariableReplica(callerContext, formalNode);
            CatchBlock cb = new CatchBlock(types, formalRep);
            catchBlocks.add(cb);
        }
        
        ReferenceVariableReplica exceptionRep = getReplica(callerContext, exceptionNode);
        boolean containsRTE = false;
        boolean containsError = false;
        for (TypeReference exType : summary.getExceptions().keySet()) {
            // Add edges from the exception in the callee to the exception in the caller
            // TODO different nodes for different exception types in the caller
            ReferenceVariableReplica calleeEx = new ReferenceVariableReplica(calleeContext, summary.getExceptions().get(exType));
            changed |= g.addEdges(exceptionRep, g.getPointsToSetFiltered(calleeEx, exType));
            if (exType == TypeReference.JavaLangRuntimeException) {
                containsRTE = true;
            }
            if (exType == TypeReference.JavaLangError) {
                containsError = true;
            }
            changed |= checkThrown(exType, exceptionRep, calleeContext, g, summary, catchBlocks);
        }
        
        if (!containsRTE) {
            changed |= checkThrown(TypeReference.JavaLangRuntimeException, exceptionRep, calleeContext, g, summary, catchBlocks);
        }
        if (g.trackImplicitErrors() && !containsError) {
            changed |= checkThrown(TypeReference.JavaLangError, exceptionRep, calleeContext, g, summary, catchBlocks);
        }

        return changed;
    }

    private boolean checkThrown(TypeReference e, ReferenceVariableReplica exceptionRep, Context calleeContext, PointsToGraph g, MethodSummaryNodes summary,
            List<CatchBlock> catchBlocks) {
        IClassHierarchy cha = g.getClassHierarchy();
        IClass thrown = cha.lookupClass(e);
        Set<IClass> alreadyCaught = new LinkedHashSet<IClass>();
        boolean isRethrown = false;
        boolean changed = false;
        
        // See if there is a catch block that catches this exception
        for (CatchBlock cb : catchBlocks) {
            while (cb.caughtTypes.hasNext()) {
                TypeReference caughtType = cb.caughtTypes.next();
                IClass caught = cha.lookupClass(caughtType);
                if (cha.isSubclassOf(thrown, caught)) {
                    return g.addEdges(cb.formalNode, g.getPointsToSetFiltered(exceptionRep, caughtType));
                } else if (cha.isSubclassOf(caught, thrown)) {
                    // The catch type is a subtype of the exception being thrown
                    // so it could be caught
                    // TODO make exceptions more precise in a later pass
                    alreadyCaught.add(caught);
                    changed |= g.addEdges(cb.formalNode, g.getPointsToSetFiltered(exceptionRep, caughtType, alreadyCaught));
                }
            }
        }
        
        // There may not be a catch block so this exception may be re-thrown
        for (TypeReference exType : summary.getExceptions().keySet()) {
            IClass exClass = cha.lookupClass(exType);
            if (cha.isSubclassOf(exClass, thrown) || cha.isSubclassOf(thrown, exClass)) {
                // may fall under this throwtype.
                LocalNode ln = summary.getExceptions().get(exType);
                ReferenceVariableReplica rethrown = new ReferenceVariableReplica(calleeContext, ln);
                changed |= g.addEdges(rethrown, g.getPointsToSetFiltered(exceptionRep, exType, alreadyCaught));
            }
            if (cha.isSubclassOf(thrown, exClass)) {
                isRethrown = true;
            }
        }

        if (!isRethrown) {
            throw new RuntimeException("Exception of type " + PrettyPrinter.parseType(e) + " may not be handled when calling " + summary);
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

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((actuals == null) ? 0 : actuals.hashCode());
        result = prime * result + ((callSite == null) ? 0 : callSite.hashCode());
        result = prime * result + ((callerIR == null) ? 0 : callerIR.hashCode());
        result = prime * result + ((exceptionNode == null) ? 0 : exceptionNode.hashCode());
        result = prime * result + ((resultNode == null) ? 0 : resultNode.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        CallStatement other = (CallStatement) obj;
        if (actuals == null) {
            if (other.actuals != null)
                return false;
        } else if (!actuals.equals(other.actuals))
            return false;
        if (callSite == null) {
            if (other.callSite != null)
                return false;
        } else if (!callSite.equals(other.callSite))
            return false;
        if (callerIR == null) {
            if (other.callerIR != null)
                return false;
        } else if (!callerIR.equals(other.callerIR))
            return false;
        if (exceptionNode == null) {
            if (other.exceptionNode != null)
                return false;
        } else if (!exceptionNode.equals(other.exceptionNode))
            return false;
        if (resultNode == null) {
            if (other.resultNode != null)
                return false;
        } else if (!resultNode.equals(other.resultNode))
            return false;
        return true;
    }

    /**
     * @return the callSite
     */
    protected CallSiteReference getCallSite() {
        return callSite;
    }

    @Override
    public IR getCode() {
        return callerIR;
    }

    /**
     * Result of the call if any, null if void or primitive return or if the return result is not assigned
     * 
     * @return return result node (in the caller)
     */
    public LocalNode getResultNode() {
        return resultNode;
    }
    
    /**
     * Information about a catch block
     */
    private static class CatchBlock {
        /**
         * Types of exceptions caught by this catch block
         */
        protected final Iterator<TypeReference> caughtTypes;
        /**
         * Points-to graph node for the formal argument to the catch block
         */
        protected final ReferenceVariableReplica formalNode;

        /**
         * Create a new catch block
         * 
         * @param caughtTypes
         *            iterator for types caught by this catch block
         * @param formalNode
         *            Points-to graph node for the formal argument to the catch
         *            block
         */
        public CatchBlock(Iterator<TypeReference> caughtTypes, ReferenceVariableReplica formalNode) {
            this.caughtTypes = caughtTypes;
            this.formalNode = formalNode;
        }
    }
}
