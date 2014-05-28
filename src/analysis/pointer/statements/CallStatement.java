package analysis.pointer.statements;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import signatures.Signatures;
import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.ReferenceVariableFactory;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

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
     * Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
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
     * factory for managing the creation of reference variables for local variables and static fields
     */
    private final ReferenceVariableFactory rvFactory;

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
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param exceptionNode
     *            Node in the caller representing the exception thrown by this call (if any) also exceptions implicitly
     *            thrown by this statement
     * @param ir
     *            IR for the caller method
     * @param i
     *            Instruction that generated this points-to statement
     * @param rvFactory
     *            factory for managing the creation of reference variables for local variables and static fields
     */
    protected CallStatement(CallSiteReference callSite, List<ReferenceVariable> actuals, ReferenceVariable resultNode,
                                    ReferenceVariable exceptionNode, IR callerIR, SSAInvokeInstruction i,
                                    WalaAnalysisUtil util, ReferenceVariableFactory rvFactory) {
        super(callerIR, i);
        this.callSite = callSite;
        this.actuals = actuals;
        this.resultNode = resultNode;
        this.exceptionNode = exceptionNode;
        this.util = util;
        this.rvFactory = rvFactory;
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
     * @param g
     *            points-to graph
     * @param registrar
     *            registrar for points-to statements
     * @param haf
     *            abstraction factory used for creating new context from existing
     * @return true if the points-to graph has changed
     */
    protected boolean processCall(Context callerContext, InstanceKey receiver, IMethod resolvedCallee, PointsToGraph g,
                                    StatementRegistrar registrar, HeapAbstractionFactory haf) {

        Context calleeContext = haf.merge(getCallSiteLabel(), receiver, callerContext);
        boolean changed = false;
        if (DEBUG && PointsToAnalysis.outputLevel >= 6) {
            System.err.println((resolvedCallee.isNative() ? "NATIVE CALL: " : "CALL: ")
                                            + PrettyPrinter.methodString(resolvedCallee) + " from "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }

        // Record the call in the call graph
        assert getCode().getMethod() != null : "Null method in IR for "
                                        + PrettyPrinter.instructionString(getInstruction(), getCode());
        assert resolvedCallee != null : "null callee for "
                                        + PrettyPrinter.instructionString(getInstruction(), getCode());

        changed |= g.addCall(callSite, getCode().getMethod(), callerContext, resolvedCallee, calleeContext);

        MethodSummaryNodes calleeSummary = registrar.findOrCreateMethodSummary(resolvedCallee, rvFactory);

        // ///////////////// Exceptions //////////////////

        ReferenceVariableReplica callerEx = new ReferenceVariableReplica(callerContext, exceptionNode);

        assert calleeSummary != null;
        ReferenceVariableReplica calleeEx = new ReferenceVariableReplica(calleeContext, calleeSummary.getException());

        if (DEBUG && g.getPointsToSet(calleeEx).isEmpty() && PointsToAnalysis.outputLevel >= 6) {
            System.out.println("EXCEPTION IN CALL: " + calleeEx + "\n\t" + PrettyPrinter.methodString(resolvedCallee)
                                            + " from " + PrettyPrinter.methodString(getCode().getMethod()));
        }
        changed |= g.addEdges(callerEx, g.getPointsToSet(calleeEx));

        // check if the exception is caught or re-thrown by this procedure
        changed |= checkThrown(callerEx, callerContext, g, registrar, resolvedCallee);

        // ////////////////// Return //////////////////

        // Add edge from the return formal to the result
        // If the result Node is null then either this is void return, there is
        // no assignment after the call, or the return type is not a reference
        if (resultNode != null) {
            ReferenceVariableReplica resultRep = getReplica(callerContext, resultNode);
            ReferenceVariableReplica returnValueFormal = new ReferenceVariableReplica(calleeContext,
                                            calleeSummary.getReturnNode());

            if (DEBUG && g.getPointsToSet(returnValueFormal).isEmpty()) {
                System.out.println("CALL RETURN: " + returnValueFormal + "\n\t"
                                                + PrettyPrinter.methodString(resolvedCallee) + " from "
                                                + PrettyPrinter.methodString(getCode().getMethod()));
            }
            changed |= g.addEdges(resultRep, g.getPointsToSet(returnValueFormal));
        }

        // ////////////////// Native with no signature //////////////////

        if (resolvedCallee.isNative() && !Signatures.hasSignature(resolvedCallee, util)) {
            // Native methods without signatures don't have "this" and formal parameters in the callee to add edges from

            // Generic signature by creating an edge from a generated "allocation" to the return and exception summary
            // nodes
            TypeReference retType = resolvedCallee.getReturnType();
            if (!retType.isPrimitiveType()) {
                ReferenceVariableReplica returnValueFormal = new ReferenceVariableReplica(calleeContext,
                                                calleeSummary.getReturnNode());

                AllocSiteNode normalRetAlloc = registrar.getReturnNodeForNative(resolvedCallee, util);
                // TODO could use caller context here (and below) for more precision, but possible size blow-up
                // The semantics would also be weird as the caller allocating the return value is a bit strange
                InstanceKey k = haf.record(normalRetAlloc, calleeContext);
                changed = g.addEdge(returnValueFormal, k);
            }

            // Synthetic allocations for thrown exceptions
            for (TypeReference exType : TypeRepository.getThrowTypes(resolvedCallee)) {

                AllocSiteNode exRetAlloc = registrar.getExceptionNodeForNative(resolvedCallee, exType, util);
                InstanceKey exKey = haf.record(exRetAlloc, calleeContext);
                changed = g.addEdge(calleeEx, exKey);
            }

            return changed;
        }

        IR calleeIR = util.getIR(resolvedCallee);

        // ////////////////// Receiver //////////////////

        // add edge from "this" in the callee to the receiver
        // if this is a static call then the receiver will be null
        if (!resolvedCallee.isStatic()) {
            ReferenceVariable thisVar = rvFactory.getOrCreateFormalParameter(0, calleeIR);
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
            ReferenceVariable formalParamVar = rvFactory.getOrCreateFormalParameter(i, calleeIR);
            ReferenceVariableReplica formalRep = new ReferenceVariableReplica(calleeContext, formalParamVar);

            assert util.getClassHierarchy().isAssignableFrom(
                                            util.getClassHierarchy().lookupClass(formalRep.getExpectedType()),
                                            util.getClassHierarchy().lookupClass(actual.getExpectedType()));
            // if (!util.getClassHierarchy().isAssignableFrom(
            // util.getClassHierarchy().lookupClass(formalRep.getExpectedType()),
            // util.getClassHierarchy().lookupClass(actual.getExpectedType()))) {
            // System.err.println("CALLING: " + PrettyPrinter.methodString(resolvedCallee) + " FROM "
            // + PrettyPrinter.methodString(getCode().getMethod()));
            // System.err.println("formal-" + i + "(" + PrettyPrinter.typeString(formalRep.getExpectedType())
            // + ") FROM " + actual.toString() + "("
            // + PrettyPrinter.typeString(actual.getExpectedType()) + ") FAILS");
            // TypeRepository.printTypes(getCode());
            // CFGWriter.writeToFile(getCode());
            // TypeInference.make(getCode(), true);
            // System.err.println("WTF");
            // }

            // Set<InstanceKey> actualHeapContexts = g.getPointsToSetFiltered(actual, formalRep.getExpectedType());
            Set<InstanceKey> actualHeapContexts = g.getPointsToSet(actual);

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
     * Get the label for the call site
     * 
     * @return label for the site of this method call
     */
    protected CallSiteLabel getCallSiteLabel() {
        return new CallSiteLabel(getCode().getMethod(), callSite);
    }

    /**
     * Get reference variable replicas in the given context for each element of the list of Reference variables
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
     * Get reference variable replica in the given context for a reference variable
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
     * Result of the call if any, null if void or primitive return or if the return result is not assigned
     * 
     * @return return result node (in the caller)
     */
    public ReferenceVariable getResultNode() {
        return resultNode;
    }

    /**
     * Check if an exception of type <code>currentExType</code> is caught or re-thrown, and modify the points-to graph
     * accordingly
     * 
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
    private final boolean checkThrown(PointsToGraphNode e, Context currentContext,
                                    PointsToGraph g, StatementRegistrar registrar, IMethod resolvedCallee) {
        // Find successor catch blocks
        List<CatchBlock> catchBlocks = getSuccessorCatchBlocks(getBasicBlock(), currentContext);

        IClassHierarchy cha = g.getClassHierarchy();
        @SuppressWarnings("unused")
        Set<IClass> alreadyCaught = new LinkedHashSet<IClass>();
        boolean changed = false;

        // See if there is a catch block that catches this exception
        for (CatchBlock cb : catchBlocks) {
            while (cb.caughtTypes.hasNext()) {
                TypeReference caughtType = cb.caughtTypes.next();
                IClass caught = cha.lookupClass(caughtType);

                changed |= g.addEdges(cb.formalNode, g.getPointsToSetFiltered(e, caughtType, alreadyCaught));
                alreadyCaught.add(caught);
            }
        }

        // The exception may not be caught by the catch blocks
        // But don't propagate error types.
        alreadyCaught.add(util.getErrorClass());

        // ANDREW: I don't know what this clone not supported thing is?
        // if (!isRethrown && currentExType != util.getCloneNotSupportedExceptionClass()) {
        // System.err.println("Exception of type " + PrettyPrinter.typeString(currentExType)
        // + " may not be handled or rethrown. When calling: "
        // + PrettyPrinter.methodString(resolvedCallee) + " from "
        // + PrettyPrinter.methodString(getCode().getMethod()));
        // // Assume it can be rethrown anyway, this is showing up for an anonymous inner class init when it calls
        // // super.<init>
        // // void java.util.jar.JarInputStream.<init>(java.io.InputStream) from
        // // void sun.jkernel.Bundle$2.<init>(sun.jkernel.Bundle, java.io.InputStream)
        // // TODO not sure why the inner class does not inherit the outer's declared exceptions
        // }

        // add edge if this exception can be rethrown
        MethodSummaryNodes callerSummary = registrar.findOrCreateMethodSummary(getCode().getMethod(), rvFactory);
        ReferenceVariableReplica thrownExRep = new ReferenceVariableReplica(currentContext,
                                        callerSummary.getException());

        changed |= g.addEdges(thrownExRep, g.getPointsToSetFiltered(e, util.getThrowableClass().getReference(),
                                        alreadyCaught));

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
         *            Points-to graph node for the formal argument to the catch block
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
     * @return List of catch blocks in reachable order (i.e. the first element of the list is the first reached)
     */
    private final List<CatchBlock> getSuccessorCatchBlocks(ISSABasicBlock fromBlock, Context context) {

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
            ReferenceVariable formalNode = rvFactory.getOrCreateCaughtEx(catchIns, getCode());
            ReferenceVariableReplica formalRep = new ReferenceVariableReplica(context, formalNode);
            CatchBlock cb = new CatchBlock(types, formalRep);
            catchBlocks.add(cb);
        }
        return catchBlocks;
    }
}
