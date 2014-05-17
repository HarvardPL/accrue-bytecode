package analysis.pointer.statements;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.statements.ReferenceVariableFactory.ReferenceVariable;

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
import com.ibm.wala.ssa.SSAInvokeInstruction;
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
    private final List<ReferenceVariable> actuals;
    /**
     * Node for the assignee if any (i.e. v in v = foo())
     */
    private final ReferenceVariable resultNode;
    /**
     * Node representing the exception thrown by this call (if any)
     */
    private final ReferenceVariable exceptionNode;
    /**
     * Cache used to lookup the IR for the callee
     */
    private final WalaAnalysisUtil util;

    /**
     * Points-to statement for a special method invocation.
     * 
     * @param callSite
     *            Method call site
     * @param callee
     *            Method being called
     * @param actuals
     *            Actual arguments to the call
     * @param resultNode
     *            Node for the assignee if any (i.e. v in v = foo()), null if
     *            there is none or if it is a primitive
     * @param exceptionNode
     *            Node in the caller representing the exception thrown by this
     *            call (if any) also exceptions implicitly thrown by this
     *            statement
     * @param ir
     *            IR for the caller method
     * @param i
     *            Instruction that generated this points-to statement
     */
    public CallStatement(CallSiteReference callSite, List<ReferenceVariable> actuals, ReferenceVariable resultNode,
                                    ReferenceVariable exceptionNode, IR callerIR, SSAInvokeInstruction i,
                                    WalaAnalysisUtil util) {
        super(callerIR, i);
        this.callSite = callSite;
        this.actuals = actuals;
        this.resultNode = resultNode;
        this.exceptionNode = exceptionNode;
        this.util = util;
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

        boolean changed = false;
        if (DEBUG && PointsToAnalysis.outputLevel >= 6) {
            System.err.println((resolvedCallee.isNative() ? "NATIVE CALL: " : "CALL: ")
                                            + PrettyPrinter.methodString(resolvedCallee) + " from "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }

        // Record the call in the call graph
        assert getCode().getMethod() != null;
        assert resolvedCallee != null;
        changed |= g.addCall(callSite, getCode().getMethod(), callerContext, resolvedCallee, calleeContext);

        MethodSummaryNodes calleeSummary = registrar.getSummaryNodes(resolvedCallee);

        // ///////////////// Exceptions //////////////////

        ReferenceVariableReplica callerEx = new ReferenceVariableReplica(callerContext, exceptionNode);

        ReferenceVariableReplica calleeEx = new ReferenceVariableReplica(calleeContext, calleeSummary.getException());

        if (DEBUG && g.getPointsToSet(calleeEx).isEmpty() && PointsToAnalysis.outputLevel >= 6) {
            System.out.println("EXCEPTION IN CALL: " + calleeEx + "\n\t" + PrettyPrinter.methodString(resolvedCallee)
                                            + " from " + PrettyPrinter.methodString(getCode().getMethod()));
        }
        changed |= g.addEdges(callerEx, g.getPointsToSet(calleeEx));

        for (TypeReference exType : TypeRepository.getThrowTypes(resolvedCallee)) {
            // check if the exception is caught or re-thrown by this procedure
            changed |= checkThrown(exType, callerEx, callerContext, g, registrar, resolvedCallee);
        }

        // ////////////////// Return //////////////////

        // Add edge from the return formal to the result
        // If the result Node is null then either this is void return, there is
        // no assignment after the call, or the return type is not a reference
        ReferenceVariableReplica resultRep = null;
        if (resultNode != null) {
            resultRep = getReplica(callerContext, getResultNode());

            ReferenceVariableReplica returnValueFormal = new ReferenceVariableReplica(calleeContext,
                                            calleeSummary.getReturnNode());

            if (DEBUG && g.getPointsToSet(returnValueFormal).isEmpty()) {
                System.out.println("CALL RETURN: " + returnValueFormal + "\n\t"
                                                + PrettyPrinter.methodString(resolvedCallee) + " from "
                                                + PrettyPrinter.methodString(getCode().getMethod()));
            }
            changed |= g.addEdges(resultRep, g.getPointsToSet(returnValueFormal));
        }

        if (resolvedCallee.isNative()) {
            // Native methods don't have "this" and formal parameters in the
            // callee to add edges from
            // XXX signatures!
            return changed;
        }

        IR calleeIR = util.getIR(resolvedCallee);

        // ////////////////// Receiver //////////////////

        // add edge from "this" in the callee to the receiver
        // if this is a static call then the receiver will be null
        if (!resolvedCallee.isStatic()) {
            ReferenceVariable thisVar = ReferenceVariableFactory.getOrCreateLocal(calleeIR.getParameter(0), calleeIR);
            ReferenceVariableReplica thisRep = new ReferenceVariableReplica(calleeContext, thisVar);
            changed |= g.addEdge(thisRep, receiver);
        }

        // ////////////////// Formals //////////////////

        // The first formal for a virtual call is the receiver which is handled
        // specially above
        int firstFormal = resolvedCallee.isStatic() ? 0 : 1;

        // add edges from local variables (formals) in the callee to the
        // actual arguments
        List<ReferenceVariableReplica> actualReps = getReplicas(callerContext, actuals);
        for (int i = firstFormal; i < actuals.size(); i++) {
            ReferenceVariableReplica actual = actualReps.get(i);
            if (actual == null) {
                // Not a reference type or null actual
                continue;
            }
            ReferenceVariable formalParamVar = ReferenceVariableFactory.getOrCreateFormalParameter(i, calleeIR);
            ReferenceVariableReplica formalRep = new ReferenceVariableReplica(calleeContext, formalParamVar);

            assert util.getClassHierarchy().isAssignableFrom(
                                            util.getClassHierarchy().lookupClass(formalRep.getExpectedType()),
                                            util.getClassHierarchy().lookupClass(actual.getExpectedType())) : PrettyPrinter
                                            .typeString(formalRep.getExpectedType())
                                            + " := "
                                            + PrettyPrinter.typeString(actual.getExpectedType()) + " FAILS";

            Set<InstanceKey> actualHeapContexts = g.getPointsToSetFiltered(actual, formalRep.getExpectedType());

            if (DEBUG && !actual.getExpectedType().isPrimitiveType() && actualHeapContexts.isEmpty()) {
                System.err.println("ACTUAL: " + actual + "\n\t" + PrettyPrinter.methodString(resolvedCallee) + " from "
                                                + PrettyPrinter.methodString(getCode().getMethod()) + " filtered on "
                                                + PrettyPrinter.typeString(formalRep.getExpectedType()));
            }
            changed |= g.addEdges(formalRep, actualHeapContexts);
        }

        return changed;
    }

    /**
     * Get reference variable replicas in the given context for each element of
     * the list of Reference variables
     * 
     * @param context
     *            Context the replicas will be created in
     * @param actuals
     *            reference variables to get replicas for
     * 
     * @return list of replicas for the given reference variables
     */
    protected List<ReferenceVariableReplica> getReplicas(Context context, List<ReferenceVariable> actuals) {
        List<ReferenceVariableReplica> actualReps = new LinkedList<>();
        for (ReferenceVariable actual : actuals) {
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
    protected static ReferenceVariableReplica getReplica(Context context, ReferenceVariable r) {
        return new ReferenceVariableReplica(context, r);
    }

    /**
     * @return the callSite
     */
    protected CallSiteReference getCallSite() {
        return callSite;
    }

    /**
     * Result of the call if any, null if void or primitive return or if the
     * return result is not assigned
     * 
     * @return return result node (in the caller)
     */
    public ReferenceVariable getResultNode() {
        return resultNode;
    }

    /**
     * Check if an exception of type <code>currentExType</code> is caught or
     * re-thrown, and modify the points-to graph accordingly
     * 
     * @param currentExType
     *            type of the exception
     * @param e
     *            exception points-to graph node
     * @param currentContext
     *            current code context
     * @param g
     *            points-to graph (may be modified)
     * @param registrar
     *            points-to statement registrar
     * @return true if the points-to graph changed
     */
    protected final boolean checkThrown(TypeReference currentExType, PointsToGraphNode e, Context currentContext,
                                    PointsToGraph g, StatementRegistrar registrar, IMethod resolvedCallee) {
        // Find successor catch blocks
        List<CatchBlock> catchBlocks = getSuccessorCatchBlocks(getBasicBlock(), currentContext);

        IClassHierarchy cha = g.getClassHierarchy();
        IClass thrown = cha.lookupClass(currentExType);
        @SuppressWarnings("unused")
        Set<IClass> alreadyCaught = new LinkedHashSet<IClass>();
        boolean isRethrown = false;
        boolean changed = false;

        // See if there is a catch block that catches this exception
        for (CatchBlock cb : catchBlocks) {
            while (cb.caughtTypes.hasNext()) {
                TypeReference caughtType = cb.caughtTypes.next();
                IClass caught = cha.lookupClass(caughtType);
                if (cha.isAssignableFrom(thrown, caught)) {
                    if (DEBUG && g.getPointsToSetFiltered(e, caughtType).isEmpty() && PointsToAnalysis.outputLevel >= 6) {
                        System.out.println("EXCEPTION (check thrown): " + e + "\n\t"
                                                        + PrettyPrinter.methodString(resolvedCallee) + " from "
                                                        + PrettyPrinter.methodString(getCode().getMethod())
                                                        + " filtered on " + PrettyPrinter.typeString(caughtType));
                    }
                    return g.addEdges(cb.formalNode, g.getPointsToSetFiltered(e, caughtType));
                } else if (cha.isAssignableFrom(caught, thrown)) {
                    // The catch type is a subtype of the exception being thrown
                    // so it could be caught (due to imprecision (due to
                    // imprecision for exceptions thrown by native calls))

                    // TODO keep track of imprecise exception types

                    alreadyCaught.add(caught);

                    if (DEBUG && g.getPointsToSetFiltered(e, caughtType, alreadyCaught).isEmpty()
                                                    && PointsToAnalysis.outputLevel >= 6) {
                        System.err.println("UNCAUGHT EXCEPTION: " + e + "\n\t"
                                                        + PrettyPrinter.instructionString(getInstruction(), getCode())
                                                        + " in " + PrettyPrinter.methodString(getCode().getMethod())
                                                        + " caught type: " + PrettyPrinter.typeString(caughtType)
                                                        + "\n\tAlready caught: " + alreadyCaught);
                    }

                    changed |= g.addEdges(cb.formalNode, g.getPointsToSetFiltered(e, caughtType, alreadyCaught));
                }
            }
        }

        // There may not be a catch block so this exception may be re-thrown
        Set<TypeReference> throwTypes = TypeRepository.getThrowTypes(getCode().getMethod());
        for (TypeReference exType : throwTypes) {
            IClass exClass = cha.lookupClass(exType);
            if (cha.isAssignableFrom(exClass, thrown)) {
                // may fall under this throw type.
                // exceptions are often not precisely typed
                // TODO keep track of when they are not precise
                // (e.g. implicit exceptions are not precise)
                isRethrown = true;
                break;
            }
            if (cha.isAssignableFrom(thrown, exClass)) {
                // does fall under this throw type.
                isRethrown = true;
                break;
            }
        }

        if (!isRethrown) {
            throw new RuntimeException("Exception of type " + PrettyPrinter.typeString(currentExType)
                                            + " may not be handled or rethrown.");
        }

        if (cha.isAssignableFrom(thrown, cha.lookupClass(TypeReference.JavaLangError))) {
            // TODO Don't propagate errors
            return changed;
        }

        // add edge if this exception can be rethrown
        MethodSummaryNodes callerSummary = registrar.getSummaryNodes(getCode().getMethod());
        ReferenceVariableReplica thrownExRep = new ReferenceVariableReplica(currentContext,
                                        callerSummary.getException());

        if (DEBUG && g.getPointsToSetFiltered(e, currentExType, alreadyCaught).isEmpty()
                                        && PointsToAnalysis.outputLevel >= 7) {
            System.err.println("EXCEPTION SUMMARY (check thrown): " + e + "\n\t"
                                            + PrettyPrinter.methodString(resolvedCallee) + " from "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }
        changed |= g.addEdges(thrownExRep, g.getPointsToSetFiltered(e, currentExType, alreadyCaught));
        return changed;
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

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((caughtTypes == null) ? 0 : caughtTypes.hashCode());
            result = prime * result + ((formalNode == null) ? 0 : formalNode.hashCode());
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
            CatchBlock other = (CatchBlock) obj;
            if (caughtTypes == null) {
                if (other.caughtTypes != null)
                    return false;
            } else if (!caughtTypes.equals(other.caughtTypes))
                return false;
            if (formalNode == null) {
                if (other.formalNode != null)
                    return false;
            } else if (!formalNode.equals(other.formalNode))
                return false;
            return true;
        }
    }

    /**
     * Find catch blocks that are successors of a given block
     * 
     * @param fromBlock
     *            block to get catch block successors of
     * @param context
     *            context the catch blocks occur in
     * @return List of catch blocks in reachable order (i.e. the first element
     *         of the list is the first reached)
     */
    protected final List<CatchBlock> getSuccessorCatchBlocks(ISSABasicBlock fromBlock, Context context) {

        // Find successor catch blocks in the CFG
        SSACFG cfg = getCode().getControlFlowGraph();
        List<ISSABasicBlock> catchBasicBlocks = cfg.getExceptionalSuccessors(fromBlock);

        // Find exceptions caught by each successor block
        List<CatchBlock> catchBlocks = new LinkedList<>();
        for (ISSABasicBlock bb : catchBasicBlocks) {
            if (bb.isExitBlock()) {
                // the exit block considered a catch block, but we handle that
                // differently in checkThrown by adding edges into summary
                // exit nodes
                continue;
            }
            Iterator<TypeReference> types = bb.getCaughtExceptionTypes();
            // The catch instruction is the first instruction in the basic block
            SSAGetCaughtExceptionInstruction catchIns = (SSAGetCaughtExceptionInstruction) bb.iterator().next();
            ReferenceVariable formalNode = ReferenceVariableFactory
                                            .getOrCreateLocal(catchIns.getException(), getCode());
            ReferenceVariableReplica formalRep = new ReferenceVariableReplica(context, formalNode);
            CatchBlock cb = new CatchBlock(types, formalRep);
            catchBlocks.add(cb);
        }
        return catchBlocks;
    }
}
