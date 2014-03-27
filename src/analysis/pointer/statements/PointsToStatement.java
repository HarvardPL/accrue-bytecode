package analysis.pointer.statements;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.LocalNode;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Defines how to process points-to graph information for a particular statement
 */
public abstract class PointsToStatement {
    /**
     * Code for the method the points-to statement came from
     */
    private final IR ir;

    public PointsToStatement(IR ir) {
        this.ir = ir;
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
     * Check if an exception of type <code>et</code> is caught or rethrown, and
     * modify the points-to graph accordingly
     * 
     * @param et
     *            type of the exception
     * @param exceptionRep
     *            exception value
     * @param g
     *            points-to graph (may be modified)
     * @param callerThrows
     *            points-to graph nodes for exceptions thrown by the containing
     *            method
     * @param catchBlocks
     *            reachable catch block listed in reachability order
     * @return true if the points-to graph changed
     */
    protected static boolean checkThrown(TypeReference et, ReferenceVariableReplica exceptionRep, PointsToGraph g,
            Map<TypeReference, ReferenceVariableReplica> callerThrows, List<CatchBlock> catchBlocks) {
        IClassHierarchy cha = g.getClassHierarchy();
        IClass thrown = cha.lookupClass(et);
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
                    alreadyCaught.add(caught);
                    changed |= g.addEdges(cb.formalNode,
                            g.getPointsToSetFiltered(exceptionRep, caughtType, alreadyCaught));
                }
            }
        }

        // There may not be a catch block so this exception may be re-thrown
        for (TypeReference exType : callerThrows.keySet()) {
            IClass exClass = cha.lookupClass(exType);
            if (cha.isSubclassOf(exClass, thrown) || cha.isSubclassOf(thrown, exClass)) {
                // may fall under this throwtype.
                ReferenceVariableReplica rethrown = callerThrows.get(exType);
                changed |= g.addEdges(rethrown, g.getPointsToSetFiltered(exceptionRep, exType, alreadyCaught));
            }
            if (cha.isSubclassOf(thrown, exClass)
                    || callerThrows.get(exType).toString().contains("com.ibm.wala.FakeRootClass.fakeRootMethod()")) {
                isRethrown = true;
            }
        }

        if (!isRethrown) {
            throw new RuntimeException("Exception of type " + PrettyPrinter.parseType(et)
                    + " may not be handled or rethrown.");
        }
        return changed;
    }

    /**
     * Information about a catch block
     */
    protected static class CatchBlock {
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
    protected List<CatchBlock> getSuccessorCatchBlocks(ISSABasicBlock fromBlock, StatementRegistrar registrar,
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
                break;
            }
            Iterator<TypeReference> types = bb.getCaughtExceptionTypes();
            // The catch instruction is the first instruction in the basic block
            SSAGetCaughtExceptionInstruction catchIns = (SSAGetCaughtExceptionInstruction) bb.iterator().next();
            LocalNode formalNode = registrar.getLocal(catchIns.getException(), getCode());
            ReferenceVariableReplica formalRep = new ReferenceVariableReplica(context, formalNode);
            CatchBlock cb = new CatchBlock(types, formalRep);
            catchBlocks.add(cb);
        }
        return catchBlocks;
    }

    /**
     * Get replicas for all the exceptions for the current method in the given
     * context
     * 
     * @param context
     *            context to get the replicas in
     * @param registrar
     *            points-to graph statement registrar
     * @return Map from exception type to reference variable replica
     */
    protected Map<TypeReference, ReferenceVariableReplica> getExceptionReplicas(Context context,
            StatementRegistrar registrar) {
        Map<TypeReference, ReferenceVariableReplica> callerThrows = new LinkedHashMap<>();
        Map<TypeReference, LocalNode> callerNodes = registrar.getSummaryNodes(getCode().getMethod()).getExceptions();
        for (TypeReference type : callerNodes.keySet()) {
            ReferenceVariableReplica callerExRep = new ReferenceVariableReplica(context, callerNodes.get(type));
            callerThrows.put(type, callerExRep);
        }
        return callerThrows;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
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
        if (ir != other.ir)
            return false;
        return true;
    }
}
