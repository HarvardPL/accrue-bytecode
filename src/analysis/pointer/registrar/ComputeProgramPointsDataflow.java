package analysis.pointer.registrar;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.IteratorSet;
import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeFactory;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeType;
import analysis.dataflow.util.Unit;
import analysis.pointer.statements.ProgramPoint;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAComparisonInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAConversionInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSAMonitorInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Data-flow that computes program points
 */
public class ComputeProgramPointsDataflow extends InstructionDispatchDataFlow<ProgramPoint> {

    private final CGNode currentNode;
    private final IR ir;
    private final Map<OrderedPair<Set<ProgramPoint>, ISSABasicBlock>, ProgramPoint> confluenceMemo;
    private final Map<ISSABasicBlock, ProgramPoint> mostRecentConfluence;
    private final PrettyPrinter pp;

    public ComputeProgramPointsDataflow(CGNode currentNode) {
        super(true);
        this.currentNode = currentNode;
        this.ir = currentNode.getIR();
        this.pp = new PrettyPrinter(ir);


        // data structures to pass to the data-flow that adds the edges
        this.confluenceMemo = new LinkedHashMap<>();
        this.mostRecentConfluence = new LinkedHashMap<>();
    }

    /**
     * Perform the dataflow
     */
    protected void dataflow() {
        dataflow(ir);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flow(Set<ProgramPoint> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        Set<ProgramPoint> flowInput = inItems;
        if (current.isEntryBlock()) {
            flowInput = Collections.singleton(PDGNodeFactory.findOrCreateProcedureSummary(currentNode)
                                            .getEntryContext());
        }
        // call confluence to make sure the results make it into the memo to be restored at post-dominators
        confluence(flowInput, current);
        return super.flow(flowInput, cfg, current);
    }

    @Override
    protected void post(IR ir) {
        // TODO Compile the results in some usable format
    }

    @Override
    protected void postBasicBlock(ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock justProcessed,
                                  Map<ISSABasicBlock, ProgramPoint> outItems) {
        super.postBasicBlock(cfg, justProcessed, outItems);

        // TODO check invariants here!
    }

    /**
     * If this node is an immediate post dominator, then return the PC node of the appropriate post dominated node.
     * Returns null otherwise.
     *
     * @param bb
     *            Basic block the post dominator for
     *
     * @return The PC node of the post dominated node, null if this node is not a post-dominator
     */
    private PDGNode handlePostDominators(ISSABasicBlock bb) {
        if (bb.equals(ir.getControlFlowGraph().exit())) {
            // Do not restore for exit block since we don't actually use that PC
            // anywhere
            return null;
        }

        if (getNumPreds(bb, ir.getControlFlowGraph()) <= 1) {
            // Don't restore if this is not a merge point in the control flow graph, any intermediate nodes are created
            // for clarity and do not change the precision.
            return null;
        }

        // This is a merge point that is not the method exit block

        // The iterator from the dominators is immutable and we need to remove
        // elements so we will create a set iterator and use that
        Set<ISSABasicBlock> doms = IteratorSet.make(dominators.dominators(bb));
        // Every node dominates itself, but it doesn't make sense to restore from a PC from the same block
        doms.remove(bb);

        // Need to find if this bb is a post-dominator of any nodes that dominate it
        Iterator<ISSABasicBlock> iter = doms.iterator();
        Set<ISSABasicBlock> postdominated = new LinkedHashSet<>();
        while (iter.hasNext()) {
            ISSABasicBlock dom = iter.next();
            Iterator<ISSABasicBlock> postdoms = postDominators.dominators(dom);
            while (postdoms.hasNext()) {
                ISSABasicBlock pd = postdoms.next();
                if (pd.equals(bb)) {
                    postdominated.add(dom);
                    break;
                }
            }
        }

        if (postdominated.size() > 1) {
            Iterator<ISSABasicBlock> iter2 = postdominated.iterator();
            while (iter2.hasNext()) {
                // If there is more than one post-dominated node try to find the
                // one that dominates the others
                ISSABasicBlock pd = iter2.next();
                Set<ISSABasicBlock> pddoms = IteratorSet.make(dominators.dominators(pd));
                pddoms.remove(pd);
                pddoms.retainAll(postdominated);
                if (!pddoms.isEmpty()) {
                    // one of the dominators is also post-dominated, so we can drop this one
                    iter2.remove();
                }
            }
        }
        if (postdominated.size() == 1) {
            // there is exactly one basic block, bb, such that the current basic
            // block is the immediate post-dominator of bb and bb is not
            // dominated by another basic block bb2 such that the current basic
            // block is the immediate post-dominator of bb2.

            // Restore the PC!
            ISSABasicBlock postDominated = postdominated.iterator().next();

            assert !postDominated.equals(bb);
            AnalysisRecord<ProgramPoint> rec = getAnalysisRecord(postDominated);
            if (rec != null) {
                ProgramPoint postDomContext = mostRecentConfluence.get(postDominated);
                assert postDomContext != null;
                return postDomContext.getPCNode();
            }
            // Have not analyzed the post-dom yet, must be a back edge, will
            // restore after it has been analyzed
            return null;
        }
        // we can't restore the PC, since there is no unique PC to restore it to.
        return null;
    }

    /**
     * Do not call this method except to merge upon entering a basic block. Use
     * {@link ComputeProgramPointsDataflow#mergeContexts()} instead.
     * <p>
     * Facts should be non-empty
     * <p>
     * {@inheritDoc}
     */
    protected ProgramPoint confluence(Set<ProgramPoint> facts, ISSABasicBlock bb) {
        if (bb.isExitBlock()) {
            // No need to merge contexts for the exit block. The summary nodes will be used instead.
            return null;
        }
        assert !facts.isEmpty() : "Empty facts in confluence entering BB" + bb.getNumber() + " IN "
                                        + PrettyPrinter.cgNodeString(currentNode);
        if (confluenceMemo.containsKey(new OrderedPair<>(facts, bb))) {
            return confluenceMemo.get(new OrderedPair<>(facts, bb));
        }

        ProgramPoint c;
        if (facts.size() == 1) {
            c = facts.iterator().next();
        } else {
            c = mergeContexts("confluence", facts.toArray(new ProgramPoint[facts.size()]));
        }

        PDGNode restorePC = handlePostDominators(bb);
        if (restorePC != null) {
            // restore the PC of a post dominator
            c = new ProgramPoint(c.getReturnNode(), c.getExceptionNode(), restorePC);
        }

        assert c != null;
        confluenceMemo.put(new OrderedPair<>(facts, bb), c);
        mostRecentConfluence.put(bb, c);
        return c;
    }

    @Override
    protected ProgramPoint flowBinaryOp(SSABinaryOpInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ProgramPoint flowComparison(SSAComparisonInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ProgramPoint flowConversion(SSAConversionInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ProgramPoint flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        ProgramPoint in = confluence(previousItems, current);
        return new ProgramPoint(null, null, in.getPCNode());
    }

    @Override
    protected ProgramPoint flowGetStatic(SSAGetInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ProgramPoint flowInstanceOf(SSAInstanceofInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ProgramPoint flowPhi(SSAPhiInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ProgramPoint flowPutStatic(SSAPutInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ProgramPoint flowUnaryNegation(SSAUnaryOpInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowArrayLength(SSAArrayLengthInstruction i,
                                                                Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        ProgramPoint in = confluence(previousItems, current);
        String desc = pp.valString(i.getArrayRef()) + " == null";
        Map<ExitType, ProgramPoint> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException,
                                                                      in,
                                        desc, current);

        ProgramPoint npe = afterEx.get(ExitType.EXCEPTIONAL);
        ProgramPoint normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowArrayLoad(SSAArrayLoadInstruction i,
                                                              Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {

        // Possibly throw NPE
        ProgramPoint in = confluence(previousItems, current);
        String desc = pp.valString(i.getArrayRef()) + " == null";
        Map<ExitType, ProgramPoint> afterNPE = handlePossibleException(TypeReference.JavaLangNullPointerException,
                                                                       in,
                                        desc, current);

        ProgramPoint npe = afterNPE.get(ExitType.EXCEPTIONAL);
        ProgramPoint normal = afterNPE.get(ExitType.NORMAL);

        // If no NPE is thrown then this may throw an
        // ArrayIndexOutOfBoundsException
        String isOOB = pp.valString(i.getIndex()) + " >= " + pp.valString(i.getArrayRef()) + ".length";
        Map<ExitType, ProgramPoint> afterEx = handlePossibleException(
                                        TypeReference.JavaLangArrayIndexOutOfBoundsException, normal, isOOB, current);

        ProgramPoint aioob = afterEx.get(ExitType.EXCEPTIONAL);
        normal = afterEx.get(ExitType.NORMAL);

        Map<ISSABasicBlock, ProgramPoint> out = new LinkedHashMap<>();
        PreciseExceptionResults pe = interProc.getPreciseExceptionResults();

        for (ISSABasicBlock succ : getExceptionalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                Set<TypeReference> exes = pe.getExceptions(current, succ, currentNode);
                if (exes.contains(TypeReference.JavaLangArrayIndexOutOfBoundsException)
                                                && exes.contains(TypeReference.JavaLangNullPointerException)) {
                    // Either NPE or AIOOB need to merge
                    out.put(succ, mergeContexts(i, npe, aioob));
                } else if (exes.contains(TypeReference.JavaLangArrayIndexOutOfBoundsException)) {
                    // Only AIOOB
                    out.put(succ, aioob);
                } else if (exes.contains(TypeReference.JavaLangNullPointerException)) {
                    // Only NPE
                    out.put(succ, npe);
                }
            }
        }

        for (ISSABasicBlock succ : getNormalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                out.put(succ, normal);
            }
        }

        return out;
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowArrayStore(SSAArrayStoreInstruction i,
                                                               Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Map from type of exception to context on edges where it is thrown
        Map<TypeReference, ProgramPoint> exContexts = new LinkedHashMap<>();

        // Possibly throw NPE
        ProgramPoint in = confluence(previousItems, current);
        String desc = pp.valString(i.getArrayRef()) + " == null";
        Map<ExitType, ProgramPoint> afterNPE = handlePossibleException(TypeReference.JavaLangNullPointerException,
                                                                       in,
                                        desc, current);

        if (afterNPE.get(ExitType.EXCEPTIONAL) != null) {
            // The null pointer exception could occur
            exContexts.put(TypeReference.JavaLangNullPointerException, afterNPE.get(ExitType.EXCEPTIONAL));
        }
        ProgramPoint normal = afterNPE.get(ExitType.NORMAL);

        // If no NPE is thrown then this may throw an
        // ArrayIndexOutOfBoundsException
        String isOOB = pp.valString(i.getIndex()) + " >= " + pp.valString(i.getArrayRef()) + ".length";
        Map<ExitType, ProgramPoint> afterAIOOB = handlePossibleException(
                                        TypeReference.JavaLangArrayIndexOutOfBoundsException, normal, isOOB, current);
        if (afterAIOOB.get(ExitType.EXCEPTIONAL) != null) {
            // The IOOB exception could occur
            exContexts.put(TypeReference.JavaLangArrayIndexOutOfBoundsException, afterAIOOB.get(ExitType.EXCEPTIONAL));
        }
        normal = afterAIOOB.get(ExitType.NORMAL);

        // If no ArrayIndexOutOfBoundsException is thrown then this may throw an ArrayStoreException
        String arrayStoreDesc = "!" + pp.valString(i.getValue()) + " instanceof " + pp.valString(i.getArrayRef())
                                        + ".elementType";
        Map<ExitType, ProgramPoint> afterEx = handlePossibleException(TypeReference.JavaLangArrayStoreException,
                                                                      normal,
                                        arrayStoreDesc, current);
        if (afterEx.get(ExitType.EXCEPTIONAL) != null) {
            // The array store exception could occur
            exContexts.put(TypeReference.JavaLangArrayStoreException, afterEx.get(ExitType.EXCEPTIONAL));
        }
        normal = afterEx.get(ExitType.NORMAL);

        Map<ISSABasicBlock, ProgramPoint> out = new LinkedHashMap<>();
        PreciseExceptionResults pe = interProc.getPreciseExceptionResults();

        for (ISSABasicBlock succ : getExceptionalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                // Note that exContexts must be non-empty here or the succ would be unreachable
                assert !exContexts.isEmpty();
                Set<TypeReference> exes = pe.getExceptions(current, succ, currentNode);
                ProgramPoint[] toMerge = new ProgramPoint[exes.size()];
                int j = 0;
                for (TypeReference exType : exes) {
                    toMerge[j] = exContexts.get(exType);
                    j++;
                }
                out.put(succ, mergeContexts(new OrderedPair<>(i, exes), toMerge));
            }
        }

        for (ISSABasicBlock succ : getNormalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                out.put(succ, normal);
            }
        }

        return out;
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                                                          Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        ProgramPoint in = confluence(previousItems, current);
        String desc = pp.valString(i.getUse(1)) + " == 0";
        Map<ExitType, ProgramPoint> afterEx = handlePossibleException(TypeReference.JavaLangArithmeticException,
                                                                      in,
                                        desc, current);

        ProgramPoint ex = afterEx.get(ExitType.EXCEPTIONAL);
        ProgramPoint normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, ex, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowCheckCast(SSACheckCastInstruction i,
                                                              Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Possibly throw ClassCastException
        ProgramPoint in = confluence(previousItems, current);
        String desc = "!" + pp.valString(i.getVal()) + " instanceof "
                                        + PrettyPrinter.typeString(i.getDeclaredResultTypes()[0]);
        Map<ExitType, ProgramPoint> afterEx = handlePossibleException(TypeReference.JavaLangClassCastException,
                                                                      in,
                                                                      desc,
                                        current);

        ProgramPoint classCast = afterEx.get(ExitType.EXCEPTIONAL);
        ProgramPoint normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, classCast, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                                                      Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        String cond = pp.valString(i.getUse(0)) + " " + PrettyPrinter.conditionalOperatorString(i.getOperator()) + " "
                                        + pp.valString(i.getUse(1));
        PDGNode truePC = PDGNodeFactory.findOrCreateOther(cond, PDGNodeType.BOOLEAN_TRUE_PC, currentNode, i);
        PDGNode falsePC = PDGNodeFactory.findOrCreateOther("!(" + cond + ")", PDGNodeType.BOOLEAN_FALSE_PC,
                                        currentNode, i);

        Map<ISSABasicBlock, ProgramPoint> out = new LinkedHashMap<>();

        ISSABasicBlock trueSucc = getTrueSuccessor(current, cfg);
        out.put(trueSucc, new ProgramPoint(null, null, truePC));

        ISSABasicBlock falseSucc = getFalseSuccessor(current, cfg);
        out.put(falseSucc, new ProgramPoint(null, null, falsePC));

        return out;
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowGetField(SSAGetInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        ProgramPoint in = confluence(previousItems, current);

        String desc = pp.valString(i.getRef()) + " == null";
        Map<ExitType, ProgramPoint> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException,
                                                                      in,
                                        desc, current);

        ProgramPoint npe = afterEx.get(ExitType.EXCEPTIONAL);
        ProgramPoint normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowInvokeInterface(SSAInvokeInstruction i,
                                                                    Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvokeVirtual(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowInvokeSpecial(SSAInvokeInstruction i,
                                                                  Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvokeVirtual(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowInvokeStatic(SSAInvokeInstruction i,
                                                                 Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowInvokeVirtual(SSAInvokeInstruction i,
                                                                  Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    /**
     * Compute exit PDG nodes for a procedure invocation
     *
     * @param i
     *            invoke instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            basic block containing the invocation
     * @return exit PDG nodes for each of the output edges
     */
    private Map<ISSABasicBlock, ProgramPoint> flowInvoke(SSAInvokeInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        ProgramPoint in = confluence(previousItems, current);

        ProgramPoint npe = null;
        if (!i.isStatic()) {
            // Could throw NPE
            String desc = pp.valString(i.getReceiver()) + " == null";
            Map<ExitType, ProgramPoint> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException,
                                                                          in,
                                            desc, current);

            npe = afterEx.get(ExitType.EXCEPTIONAL);

            // TODO if no NPE throw WrongMethodTypeException
        }

        boolean canCalleeThrowNPE = false;
        for (CGNode callee : interProc.getCallGraph().getPossibleTargets(currentNode, i.getCallSite())) {
            // Trigger the analysis of the callee
            interProc.getResults(currentNode, callee, Unit.VALUE);

            if (interProc.getPreciseExceptionResults().canProcedureThrowException(
                                            TypeReference.JavaLangNullPointerException, callee)) {
                canCalleeThrowNPE = true;
                break;
            }
        }

        // Node to representing the join of the caller PC before the call and
        // the PC after the call
        String normalDesc = "NORMAL EXIT-PC-JOIN after " + PrettyPrinter.methodString(i.getDeclaredTarget());
        PDGNode normalExitPC = PDGNodeFactory.findOrCreateOther(normalDesc, PDGNodeType.EXIT_PC_JOIN, currentNode,
                                        new OrderedPair<>(i, ExitType.NORMAL));

        String exDesc = "EX EXIT-PC-JOIN after " + PrettyPrinter.methodString(i.getDeclaredTarget());
        PDGNode exExitPC = PDGNodeFactory.findOrCreateOther(exDesc, PDGNodeType.EXIT_PC_JOIN, currentNode,
                                        new OrderedPair<>(i, ExitType.EXCEPTIONAL));

        PDGNode exValue = PDGNodeFactory.findOrCreateLocal(i.getException(), currentNode, pp);
        ProgramPoint exContext = new ProgramPoint(null, exValue, exExitPC);
        calleeExceptionContexts.put(i, exContext);

        ProgramPoint npeExitContext = npe;
        if (npe != null && canCalleeThrowNPE) {
            // The receiver could be null AND the callee may throw an NPE. Need
            // to merge the contexts to use on NPE exit edges.
            npeExitContext = mergeContexts(new OrderedPair<>(i, "NPE_MERGE"), npe, exContext);
        }

        PreciseExceptionResults pe = interProc.getPreciseExceptionResults();
        Map<ISSABasicBlock, ProgramPoint> out = new LinkedHashMap<>();
        for (ISSABasicBlock succ : getExceptionalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                boolean hasNPE = pe.getExceptions(current, succ, currentNode).contains(
                                                TypeReference.JavaLangNullPointerException);
                if (hasNPE && npe != null) {
                    // This edge has an NPE and the receiver may be null
                    out.put(succ, npeExitContext);
                } else {
                    out.put(succ, exContext);
                }
            }
        }

        for (ISSABasicBlock succ : getNormalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                out.put(succ, new ProgramPoint(null, null, normalExitPC));
            }
        }

        return out;
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowGoto(SSAGotoInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowLoadMetadata(SSALoadMetadataInstruction i,
                                                                 Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO load metadata can throw a ClassNotFoundException, but this could be known statically if all the class
        // files were known (closed world).
        ProgramPoint in = confluence(previousItems, current);
        String desc = PrettyPrinter.typeString(i.getType()) + " not found";
        Map<ExitType, ProgramPoint> afterEx = handlePossibleException(TypeReference.JavaLangClassNotFoundException,
                                                                      in,
                                        desc, current);

        ProgramPoint cnf = afterEx.get(ExitType.EXCEPTIONAL);
        ProgramPoint normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, cnf, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowMonitor(SSAMonitorInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        ProgramPoint in = confluence(previousItems, current);
        String desc = pp.valString(i.getRef()) + " == null";
        Map<ExitType, ProgramPoint> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException,
                                                                      in,
                                        desc, current);

        ProgramPoint npe = afterEx.get(ExitType.EXCEPTIONAL);
        ProgramPoint normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowNewArray(SSANewInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        ProgramPoint in = confluence(previousItems, current);
        Map<ExitType, ProgramPoint> afterEx = handlePossibleException(TypeReference.JavaLangNegativeArraySizeException,
                                        in, "size < 0", current);

        ProgramPoint negArraySize = afterEx.get(ExitType.EXCEPTIONAL);
        ProgramPoint normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, negArraySize, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowNewObject(SSANewInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowPutField(SSAPutInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Possibly throw NPE
        ProgramPoint in = confluence(previousItems, current);
        String desc = pp.valString(i.getRef()) + " == null";
        Map<ExitType, ProgramPoint> afterNPE = handlePossibleException(TypeReference.JavaLangNullPointerException,
                                                                       in,
                                        desc, current);

        ProgramPoint npe = afterNPE.get(ExitType.EXCEPTIONAL);
        ProgramPoint normal = afterNPE.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowReturn(SSAReturnInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        ProgramPoint in = confluence(previousItems, current);
        PDGNode returnValue = i.getResult() > 0 ? PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp) : null;
        return factToMap(new ProgramPoint(returnValue, null, in.getPCNode()), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowSwitch(SSASwitchInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode newPC = PDGNodeFactory.findOrCreateOther("switch-PC in " + PrettyPrinter.methodString(ir.getMethod()),
                                        PDGNodeType.PC_OTHER, currentNode, i);
        return factToMap(new ProgramPoint(null, null, newPC), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowThrow(SSAThrowInstruction i, Set<ProgramPoint> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Can throw NPE if the exception is null
        ProgramPoint in = confluence(previousItems, current);
        String desc = pp.valString(i.getException()) + " == null";
        Map<ExitType, ProgramPoint> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException,
                                                                    in,
                                                                    desc,
                                                                    current);
        ProgramPoint normal = afterEx.get(ExitType.NORMAL);
        ProgramPoint npe = afterEx.get(ExitType.EXCEPTIONAL);
        PDGNode throwExpr = getNodeForThrowExpression(i, current, currentNode, pp);

        ProgramPoint afterThrow = new ProgramPoint(null, throwExpr, normal.getPCNode());

        ProgramPoint exitContext;
        if (npe != null) {
            // Merge the context for the npe with the one for the thrown exception
            exitContext = mergeContexts(new OrderedPair<>(i, "exception merge"), afterThrow, npe);
        }
        else {
            // The exception cannot be null and thus never throws an NPE
            exitContext = afterThrow;
        }


        return factsToMapWithExceptions(null, exitContext, current, cfg);
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        return interProc.getReachabilityResults().isUnreachable(source, target, currentNode);
    }

    /**
     * Determine whether a exception of the given type can be thrown and create nodes and edges in the PDG to capture
     * the dependencies when an exception is thrown by the JVM (e.g. a {@link NullPointerException}).
     *
     * @param exType type of exception being thrown
     * @param beforeException context (including the PC ndoe) immediately before the exception is thrown
     * @param reasonForException description of the condition that causes the exception. The condition being true should
     *            result in the exception. (e.g. o == null for an NPE, index > a.length for an
     *            ArrayIndexOutOfBoundsException).
     * @param bb basic block that could throw the exception
     * @return Two PDG contexts one for when the exception is not thrown and one for when it is thrown. If the exception
     *         cannot be thrown then the exceptional context will be null.
     */
    private Map<ExitType, ProgramPoint> handlePossibleException(TypeReference exType, ProgramPoint beforeException,
                                                              String reasonForException, ISSABasicBlock bb) {
        SSAInstruction i = getLastInstruction(bb);
        if (interProc.getPreciseExceptionResults().canThrowException(exType, bb, currentNode)) {
            Map<ExitType, ProgramPoint> out = new LinkedHashMap<>();
            PDGNode truePC = PDGNodeFactory.findOrCreateOther(reasonForException,
                                                              PDGNodeType.BOOLEAN_TRUE_PC,
                                                              currentNode,
                                                              new OrderedPair<>(i, exType));

            PDGNode falsePC = PDGNodeFactory.findOrCreateOther("!(" + reasonForException + ")",
                                                               PDGNodeType.BOOLEAN_FALSE_PC,
                                                               currentNode,
                                                               new OrderedPair<>(i, exType));
            PDGNode exceptionValue = PDGNodeFactory.findOrCreateGeneratedException(exType, currentNode, i);

            ProgramPoint ex = new ProgramPoint(null, exceptionValue, truePC);
            ProgramPoint normal = new ProgramPoint(null, null, falsePC);

            recordExceptionContexts(exType, ex, normal, bb);
            out.put(ExitType.NORMAL, normal);
            out.put(ExitType.EXCEPTIONAL, ex);
            return out;
        }
        return Collections.singletonMap(ExitType.NORMAL, beforeException);
    }

    /**
     * Record the contexts computed for the false and true branches of an (implicit) exception throw
     *
     * @param exType
     *            type of exception being thrown
     * @param ex
     *            context on the branch where the exception is thrown
     * @param normal
     *            context on the branch where the exception is NOT thrown
     * @param bb
     *            basic block that throws the exception
     */
    private void recordExceptionContexts(TypeReference exType, ProgramPoint ex, ProgramPoint normal, ISSABasicBlock bb) {
        Map<TypeReference, ProgramPoint> trueExes = trueExceptionContexts.get(bb);
        if (trueExes == null) {
            trueExes = new HashMap<>();
            trueExceptionContexts.put(bb, trueExes);
        }
        trueExes.put(exType, ex);

        Map<TypeReference, ProgramPoint> falseExes = falseExceptionContexts.get(bb);
        if (falseExes == null) {
            falseExes = new HashMap<>();
            falseExceptionContexts.put(bb, falseExes);
        }
        falseExes.put(exType, normal);
    }

    /**
     * Merge contexts and add appropriate edges to the PDG.
     *
     * @param disambuationKey
     *            key used to distinguish nodes (in addition to the call graph node and node type)
     * @param contexts
     *            non-null contexts to merge (array cannot be empty)
     * @return merged context
     */
    private ProgramPoint mergeContexts(Object disambiguationKey, ProgramPoint... contexts) {
        assert contexts.length > 0 : "empty context array in mergeContexts " + "\n\tIN "
                                        + PrettyPrinter.cgNodeString(currentNode) + "\nKEY: " + disambiguationKey;
        assert !(disambiguationKey instanceof ProgramPoint) : "Missing disambiguation key.";
        if (contexts.length == 1) {
            return contexts[0];
        }

        Set<PDGNode> exceptions = new LinkedHashSet<>();
        Set<PDGNode> returns = new LinkedHashSet<>();
        Set<PDGNode> pcs = new LinkedHashSet<>();
        for (ProgramPoint c : contexts) {
            assert c != null : "null context in mergeContexts " + "\n\tIN " + PrettyPrinter.cgNodeString(currentNode)
                                            + "\nKEY: " + disambiguationKey;
            if (c.getExceptionNode() != null) {
                exceptions.add(c.getExceptionNode());
            }
            if (c.getReturnNode() != null) {
                returns.add(c.getPCNode());
            }
            pcs.add(c.getPCNode());
        }

        PDGNode newEx = mergeIfNecessary(exceptions, "EX MERGE", PDGNodeType.EXCEPTION_MERGE, disambiguationKey);
        PDGNode newRet = mergeIfNecessary(returns, "RETURN MERGE", PDGNodeType.OTHER_EXPRESSION, disambiguationKey);
        PDGNode newPC = mergeIfNecessary(pcs, "PC MERGE", PDGNodeType.PC_MERGE, disambiguationKey);
        return new ProgramPoint(newRet, newEx, newPC);
    }

    /**
     * Create a new merge node if the input set has size bigger than 1
     *
     * @param nodesToMerge
     *            nodes to be merged if necessary
     * @param mergeNodeDesc
     *            description to be put into the new merge node
     * @param mergeNodeType
     *            type of the new merge node
     * @param disambiguationKey
     *            key to disambiguate the new merge node
     * @return new merge node if any, if the set contains only one node then that node is returned, null if the set of
     *         nodes is empty
     */
    private PDGNode mergeIfNecessary(Set<PDGNode> nodesToMerge, String mergeNodeDesc, PDGNodeType mergeNodeType,
                                    Object disambiguationKey) {
        PDGNode result;
        if (nodesToMerge.size() > 1) {
            result = PDGNodeFactory.findOrCreateOther(mergeNodeDesc, mergeNodeType, currentNode, disambiguationKey);
            mergeNodes.put(result, nodesToMerge);
        } else if (nodesToMerge.size() == 1) {
            return nodesToMerge.iterator().next();
        } else {
            return null;
        }

        return result;
    }

    /**
     * Get the input context for the each instruction, this is unsound until after the analysis completes
     *
     * @return Map from instruction to input context
     */
    protected Map<SSAInstruction, ProgramPoint> getInstructionInput() {
        Map<SSAInstruction, ProgramPoint> res = new LinkedHashMap<>();
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            for (SSAInstruction i : bb) {
                AnalysisRecord<ProgramPoint> rec = getAnalysisRecord(i);
                if (rec != null) {
                    res.put(i, confluence(rec.getInput(), bb));
                }
            }
        }
        return res;
    }

    /**
     * Get the input contexts for the each basic block, this is unsound until after the analysis completes
     *
     * @return Map from basic block to input contexts
     */
    protected Map<ISSABasicBlock, Set<ProgramPoint>> getInputContexts() {
        Map<ISSABasicBlock, Set<ProgramPoint>> res = new LinkedHashMap<>();
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            AnalysisRecord<ProgramPoint> rec = getAnalysisRecord(bb);
            if (rec != null) {
                res.put(bb, rec.getInput());
            }
        }
        return res;
    }

    /**
     * Get a map from basic block to ProgramPoints for successor blocks
     *
     * @return map from basic block to output items
     */
    private Map<ISSABasicBlock, Map<ISSABasicBlock, ProgramPoint>> getOutputContexts() {
        Map<ISSABasicBlock, Map<ISSABasicBlock, ProgramPoint>> res = new LinkedHashMap<>();
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            AnalysisRecord<ProgramPoint> rec = getAnalysisRecord(bb);
            if (rec != null) {
                res.put(bb, rec.getOutput());
            }
        }
        return res;
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowEmptyBlock(Set<ProgramPoint> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return factToMap(confluence(inItems, current), current, cfg);
    }

    /**
     * Get the node for the actual throw expression in a given throw instruction
     *
     * @return node for the throw (e.g. "throw foo")
     */
    protected static PDGNode getNodeForThrowExpression(SSAThrowInstruction i, ISSABasicBlock bb, CGNode node,
                                                       PrettyPrinter pp) {
        return PDGNodeFactory.findOrCreateOther(pp.instructionString(i),
                                                PDGNodeType.OTHER_EXPRESSION,
                                                node,
                                                bb.getNumber());
    }
}
