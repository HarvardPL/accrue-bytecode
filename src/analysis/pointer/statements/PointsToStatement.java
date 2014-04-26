package analysis.pointer.statements;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariable;
import analysis.pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Defines how to process points-to graph information for a particular statement
 */
public abstract class PointsToStatement {
    /**
     * Code for the method the points-to statement came from
     */
    private final IR ir;
    /**
     * Instruction that generated this points-to statement
     */
    private final SSAInstruction i;
    /**
     * Basic block this points-to statement was generated in
     */
    private ISSABasicBlock bb = null;

    /**
     * Create a new points-to statement
     * 
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    public PointsToStatement(IR ir, SSAInstruction i) {
        this.ir = ir;
        this.i = i;
    }

    /**
     * Process this statement, modifying the points-to graph if necessary
     * 
     * @param context
     *            current analysis context
     * @param haf
     *            factory for creating new analysis contexts
     * @param g
     *            points-to graph (may be modified)
     * @param registrar
     *            Points-to statement registrar
     * @return true if the points-to graph was modified
     */
    public abstract boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g,
            StatementRegistrar registrar);

    /**
     * Get the code for the method this points-to statement was created in
     * 
     * @return intermediate representation code for the method
     */
    public final IR getCode() {
        return ir;
    }

    /**
     * Get the instruction that generated this points-to statement
     * 
     * @return SSA instruction
     */
    public final SSAInstruction getInstruction() {
        return i;
    }

    /**
     * Get the containing basic block
     * 
     * @return basic block this statement was generated in
     */
    public final ISSABasicBlock getBasicBlock() {
        if (bb == null) {
            bb = ir.getBasicBlockForInstruction(i);
        }
        return bb;
    }

    /**
     * Check whether exceptions are caught or re-thrown, and modify the
     * points-to graph accordingly. Does not include exceptions thrown by
     * callees if this is a call statement.
     * 
     * @param currentContext
     *            current code context
     * @param g
     *            points-to graph (may be modified)
     * @param registrar
     *            points-to statement registrar
     * @return true if the points-to graph changed
     */
    protected final boolean checkAllThrown(Context currentContext, PointsToGraph g, StatementRegistrar registrar) {
        boolean changed = false;

        for (TypeReference tr : PreciseExceptionResults.implicitExceptions(i)) {
            ReferenceVariable exNode = registrar.getOrCreateImplicitExceptionNode(tr, getInstruction(), getCode());
            ReferenceVariableReplica e = new ReferenceVariableReplica(currentContext, exNode);
            changed |= checkThrown(tr, e, currentContext, g, registrar);
        }
        return changed;
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
            PointsToGraph g, StatementRegistrar registrar) {
        // Find successor catch blocks
        List<CatchBlock> catchBlocks = getSuccessorCatchBlocks(getBasicBlock(), registrar, currentContext);

        IClassHierarchy cha = g.getClassHierarchy();
        IClass thrown = cha.lookupClass(currentExType);
        Set<IClass> alreadyCaught = new LinkedHashSet<IClass>();
        boolean isRethrown = false;
        boolean changed = false;

        // See if there is a catch block that catches this exception
        for (CatchBlock cb : catchBlocks) {
            while (cb.caughtTypes.hasNext()) {
                TypeReference caughtType = cb.caughtTypes.next();
                IClass caught = cha.lookupClass(caughtType);
                if (cha.isSubclassOf(thrown, caught)) {
                    return g.addEdges(cb.formalNode, g.getPointsToSetFiltered(e, caughtType));
                } else if (cha.isSubclassOf(caught, thrown)) {
                    // The catch type is a subtype of the exception being thrown
                    // so it could be caught (due to imprecision (due to
                    // imprecision for exceptions thrown by native calls))

                    // TODO keep track of imprecise exception types
                    
                    alreadyCaught.add(caught);
                    changed |= g.addEdges(cb.formalNode, g.getPointsToSetFiltered(e, caughtType, alreadyCaught));
                }
            }
        }

        // There may not be a catch block so this exception may be re-thrown
        Set<TypeReference> throwTypes = TypeRepository.getThrowTypes(getCode().getMethod());
        for (TypeReference exType : throwTypes) {
            IClass exClass = cha.lookupClass(exType);
            if (cha.isSubclassOf(exClass, thrown)) {
                // may fall under this throw type.
                // exceptions are often not precisely typed
                // TODO keep track of when they are not precise
                // (e.g. implicit exceptions are not precise)
                isRethrown = true;
                break;
            }
            if (cha.isSubclassOf(thrown, exClass)) {
                // does fall under this throw type.
                isRethrown = true;
                break;
            }
        }

        if (!isRethrown) {
            throw new RuntimeException("Exception of type " + PrettyPrinter.parseType(currentExType)
                    + " may not be handled or rethrown.");
        }

        // add edge if this exception can be rethrown
        MethodSummaryNodes callerSummary = registrar.getSummaryNodes(getCode().getMethod());
        ReferenceVariableReplica thrownExRep = new ReferenceVariableReplica(currentContext,
                callerSummary.getException());
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
     * @param registrar
     *            points-to statement registrar
     * @param context
     *            context the catch blocks occur in
     * @return List of catch blocks in reachable order (i.e. the first element
     *         of the list is the first reached)
     */
    protected final List<CatchBlock> getSuccessorCatchBlocks(ISSABasicBlock fromBlock, StatementRegistrar registrar,
            Context context) {

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
            ReferenceVariable formalNode = registrar.getOrCreateLocal(catchIns.getException(), getCode());
            ReferenceVariableReplica formalRep = new ReferenceVariableReplica(context, formalNode);
            CatchBlock cb = new CatchBlock(types, formalRep);
            catchBlocks.add(cb);
        }
        return catchBlocks;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((i == null) ? 0 : i.hashCode());
        result = prime * result + ((ir == null) ? 0 : ir.hashCode());
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
        PointsToStatement other = (PointsToStatement) obj;
        if (i == null) {
            if (other.i != null)
                return false;
        } else if (!i.equals(other.i))
            return false;
        if (ir == null) {
            if (other.ir != null)
                return false;
        } else if (!ir.equals(other.ir))
            return false;
        return true;
    }
}
