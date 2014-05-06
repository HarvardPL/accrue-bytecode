package analysis.dataflow.interprocedural.pdg;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.IteratorSet;
import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeFactory;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeType;
import analysis.dataflow.interprocedural.pdg.graph.node.ProcedureSummaryNodes;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACFG;
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
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.dominators.Dominators;
import com.ibm.wala.util.graph.impl.GraphInverter;

/**
 * Data-flow that builds up the set of nodes in a program dependence graph.
 */
public class PDGNodeDataflow extends InstructionDispatchDataFlow<PDGContext> {

    private final CGNode currentNode;
    private final PDGInterproceduralDataFlow interProc;
    private final ProcedureSummaryNodes summary;
    private final IR ir;
    private final WalaAnalysisUtil util;
    private final Dominators<ISSABasicBlock> postDominators;
    private final Dominators<ISSABasicBlock> dominators;
    private final Map<PDGNode, Set<PDGNode>> mergeNodes;
    private final Map<SSAInvokeInstruction, PDGContext> callSiteExceptionContexts;

    public PDGNodeDataflow(CGNode currentNode, PDGInterproceduralDataFlow interProc, ProcedureSummaryNodes summary,
                                    WalaAnalysisUtil util) {
        super(true);
        this.currentNode = currentNode;
        this.interProc = interProc;
        this.summary = summary;
        this.ir = currentNode.getIR();
        this.util = util;
        // compute post dominators
        SSACFG cfg = ir.getControlFlowGraph();
        Graph<ISSABasicBlock> reverseCFG = GraphInverter.invert(cfg);
        postDominators = Dominators.make(reverseCFG, cfg.exit());
        dominators = Dominators.make(cfg, cfg.entry());
        callSiteExceptionContexts = new LinkedHashMap<>();
        mergeNodes = new LinkedHashMap<>();
    }

    /**
     * Perform the dataflow
     */
    protected void dataflow() {
        dataflow(ir);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flow(Set<PDGContext> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        if (current.isEntryBlock()) {
            inItems = Collections.singleton(summary.getEntryContext());
        }
        return super.flow(inItems, cfg, current);
    }

    @Override
    protected void post(IR ir) {
        // Turn on the edges and run once more. This allows the set of nodes to
        // reach a fixed point before adding edges.
        if (getOutputLevel() >= 4) {
            System.err.println("Adding edges for " + PrettyPrinter.parseCGNode(currentNode));
        }
        PDGEdgeDataflow edgeDF = new PDGEdgeDataflow(currentNode, interProc, summary, util, mergeNodes,
                                        callSiteExceptionContexts, getInputContexts(), getOutputContexts(),
                                        getInstructionInput());
        edgeDF.dataflow();
    }

    @Override
    protected void postBasicBlock(Set<PDGContext> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock justProcessed, Map<ISSABasicBlock, PDGContext> outItems) {
        super.postBasicBlock(inItems, cfg, justProcessed, outItems);

        // Check the context, make sure that only exit and catch successors have
        // stuff in the return and exception places respectively
        for (ISSABasicBlock succ : getExceptionalSuccs(justProcessed, cfg)) {
            if (!isUnreachable(justProcessed, succ)) {
                PDGContext out = outItems.get(succ);
                assert out.getExceptionNode() != null : "null exception node on exception edge.\n"
                                                + PrettyPrinter.basicBlockString(ir, justProcessed, "\t", "\n")
                                                + "TO\n" + PrettyPrinter.basicBlockString(ir, succ, "\t", "\n") + "IN "
                                                + PrettyPrinter.parseCGNode(currentNode);
                assert out.getReturnNode() == null : "non-null return node on exception edge. " + out.getReturnNode()
                                                + "\n" + PrettyPrinter.basicBlockString(ir, justProcessed, "\t", "\n")
                                                + "TO\n" + PrettyPrinter.basicBlockString(ir, succ, "\t", "\n") + "IN "
                                                + PrettyPrinter.parseCGNode(currentNode);
                assert out.getPCNode() != null : "null PC node on exception edge.\n"
                                                + PrettyPrinter.basicBlockString(ir, justProcessed, "\t", "\n")
                                                + "TO\n" + PrettyPrinter.basicBlockString(ir, succ, "\t", "\n") + "IN "
                                                + PrettyPrinter.parseCGNode(currentNode);
            }
        }

        for (ISSABasicBlock succ : getNormalSuccs(justProcessed, cfg)) {
            if (!isUnreachable(justProcessed, succ)) {
                PDGContext out = outItems.get(succ);
                assert out.getExceptionNode() == null : "non-null exception node on non-void normal edge. "
                                                + out.getExceptionNode() + "\n"
                                                + PrettyPrinter.basicBlockString(ir, justProcessed, "\t", "\n")
                                                + "TO\n" + PrettyPrinter.basicBlockString(ir, succ, "\t", "\n") + "IN "
                                                + PrettyPrinter.parseCGNode(currentNode);
                if (succ.equals(cfg.exit()) && ir.getMethod().getReturnType() != TypeReference.Void) {
                    // entering the exit block of a non-void method
                    assert out.getReturnNode() != null : "non-null return node on void normal edge.\n"
                                                    + PrettyPrinter.basicBlockString(ir, justProcessed, "\t", "\n")
                                                    + "TO\n" + PrettyPrinter.basicBlockString(ir, succ, "\t", "\n")
                                                    + "IN " + PrettyPrinter.parseCGNode(currentNode);
                } else {
                    assert out.getReturnNode() == null : "null return node on non-void normal edge.\n"
                                                    + PrettyPrinter.basicBlockString(ir, justProcessed, "\t", "\n")
                                                    + "TO\n" + PrettyPrinter.basicBlockString(ir, succ, "\t", "\n")
                                                    + "IN " + PrettyPrinter.parseCGNode(currentNode);
                }
                assert out.getPCNode() != null : "null PC node on normal edge.\n"
                                                + PrettyPrinter.basicBlockString(ir, justProcessed, "\t", "\n")
                                                + "TO\n" + PrettyPrinter.basicBlockString(ir, succ, "\t", "\n") + "IN "
                                                + PrettyPrinter.parseCGNode(currentNode);
            }
        }
    }

    /**
     * If this node is an immediate post dominator, then return the PC node of
     * the appropriate post dominated node. Returns null otherwise.
     * 
     * @param bb
     *            Basic block the post dominator for
     * 
     * @return The PC node of the post dominated node, null if this node is not
     *         a post-dominator
     */
    private PDGNode handlePostDominators(ISSABasicBlock bb) {

        if (bb.equals(ir.getControlFlowGraph().exit())) {
            // Do not restore for exit block since we don't actually use that PC
            // anywhere
            return null;
        }

        // The iterator from the dominators is immutable and we need to remove
        // elements so we will create a set iterator and use that
        Set<ISSABasicBlock> postDoms = IteratorSet.make(postDominators.dominators(bb));
        postDoms.remove(bb);
        if (postDoms.size() > 1) {
            Iterator<ISSABasicBlock> iter = postDoms.iterator();
            while (iter.hasNext()) {
                // If there is more than one post-dominated node try to find the
                // one that dominates the others
                ISSABasicBlock dom = iter.next();
                Set<ISSABasicBlock> doms = IteratorSet.make(dominators.dominators(dom));
                doms.retainAll(postDoms);
                if (!doms.isEmpty()) {
                    // one of the doms is also in pd, so we can drop this one
                    iter.remove();
                }
            }
        }
        if (postDoms.size() == 1) {
            // there is exactly one basic block, bb, such that the current basic
            // block is the immediate post-dominator of bb and bb is not
            // dominated by another basic block bb2 such that the current basic
            // block is the immediate post-dominator of bb2.

            // Restore the PC!
            ISSABasicBlock postDominated = postDoms.iterator().next();
            AnalysisRecord<PDGContext> rec = getAnalysisRecord(postDominated);
            if (rec != null) {
                PDGContext postDomContext = confluence(getAnalysisRecord(postDominated).getInput(), bb);
                return postDomContext.getPCNode();
            } else {
                // Have not analyzed the post-dom yet, must be a back edge, will
                // restore after it has been analyzed
                return null;
            }
        } else {
            // we can't restore the PC, since there is no unique PC to restore
            // it to.
            return null;
        }
    }

    /**
     * facts should be non-empty
     * 
     * Do not call this method except to merge upon entering a basic block. Use
     * {@link PDGNodeDataflow#mergeContexts()} instead.
     * <p>
     * {@inheritDoc}
     */
    @Override
    protected PDGContext confluence(Set<PDGContext> facts, ISSABasicBlock bb) {
        assert !facts.isEmpty() : "Empty facts in confluence entering\n"
                                        + PrettyPrinter.basicBlockString(ir, bb, "\t", "\n") + "IN "
                                        + PrettyPrinter.parseCGNode(currentNode);
        if (facts.size() == 1) {
            return facts.iterator().next();
        }

        PDGContext c = mergeContexts("confluence", facts.toArray(new PDGContext[facts.size()]));

        PDGNode restorePC = handlePostDominators(bb);
        if (restorePC != null) {
            // restore the PC of a post dominator
            c = new PDGContext(c.getReturnNode(), c.getExceptionNode(), restorePC);
        }
        return c;
    }

    @Override
    protected PDGContext flowBinaryOp(SSABinaryOpInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected PDGContext flowComparison(SSAComparisonInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected PDGContext flowConversion(SSAConversionInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected PDGContext flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected PDGContext flowGetStatic(SSAGetInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected PDGContext flowInstanceOf(SSAInstanceofInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected PDGContext flowPhi(SSAPhiInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected PDGContext flowPutStatic(SSAPutInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected PDGContext flowUnaryNegation(SSAUnaryOpInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowArrayLength(SSAArrayLengthInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getArrayRef(), ir) + " == null";
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException, in,
                                        desc, current, cfg);

        PDGContext npe = afterEx.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowArrayLoad(SSAArrayLoadInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {

        // Possibly throw NPE
        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getArrayRef(), ir) + " == null";
        Map<ExitType, PDGContext> afterNPE = handlePossibleException(TypeReference.JavaLangNullPointerException, in,
                                        desc, current, cfg);

        PDGContext npe = afterNPE.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterNPE.get(ExitType.NORMAL);

        // If no NPE is thrown then this may throw an
        // ArrayIndexOutOfBoundsException
        String isOOB = PrettyPrinter.valString(i.getIndex(), ir) + " >= "
                                        + PrettyPrinter.valString(i.getArrayRef(), ir) + ".length";
        Map<ExitType, PDGContext> afterEx = handlePossibleException(
                                        TypeReference.JavaLangArrayIndexOutOfBoundsException, normal, isOOB, current,
                                        cfg);

        PDGContext aioob = afterEx.get(ExitType.EXCEPTIONAL);
        normal = afterEx.get(ExitType.NORMAL);

        Map<ISSABasicBlock, PDGContext> out = new LinkedHashMap<>();
        PreciseExceptionResults pe = interProc.getPreciseExceptionResults();

        PDGContext mergedExContext = null;
        if (npe != null && aioob != null) {
            mergedExContext = mergeContexts(i, npe, aioob);
        }

        for (ISSABasicBlock succ : getExceptionalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                Set<TypeReference> exes = pe.getExceptions(current, succ, currentNode);
                if (exes.contains(TypeReference.JavaLangArrayIndexOutOfBoundsException)
                                                && exes.contains(TypeReference.JavaLangNullPointerException)) {
                    // Either NPE or AIOOB need to merge
                    out.put(succ, mergedExContext);
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
    protected Map<ISSABasicBlock, PDGContext> flowArrayStore(SSAArrayStoreInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Map from type of exception to context on edges where it is thrown
        Map<TypeReference, PDGContext> exContexts = new LinkedHashMap<>();

        // Possibly throw NPE
        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getArrayRef(), ir) + " == null";
        Map<ExitType, PDGContext> afterNPE = handlePossibleException(TypeReference.JavaLangNullPointerException, in,
                                        desc, current, cfg);

        exContexts.put(TypeReference.JavaLangNullPointerException, afterNPE.get(ExitType.EXCEPTIONAL));
        PDGContext normal = afterNPE.get(ExitType.NORMAL);

        // If no NPE is thrown then this may throw an
        // ArrayIndexOutOfBoundsException
        String isOOB = PrettyPrinter.valString(i.getIndex(), ir) + " >= "
                                        + PrettyPrinter.valString(i.getArrayRef(), ir) + ".length";
        Map<ExitType, PDGContext> afterAIOOB = handlePossibleException(
                                        TypeReference.JavaLangArrayIndexOutOfBoundsException, normal, isOOB, current,
                                        cfg);
        exContexts.put(TypeReference.JavaLangArrayIndexOutOfBoundsException, afterAIOOB.get(ExitType.EXCEPTIONAL));
        normal = afterAIOOB.get(ExitType.NORMAL);

        // If no ArrayIndexOutOfBoundsException is thrown then this may throw an
        // ArrayStoreException
        String arrayStoreDesc = "!" + PrettyPrinter.valString(i.getValue(), ir) + " instanceof "
                                        + PrettyPrinter.valString(i.getArrayRef(), ir) + ".elementType";
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangArrayStoreException, normal,
                                        arrayStoreDesc, current, cfg);
        exContexts.put(TypeReference.JavaLangArrayStoreException, afterEx.get(ExitType.EXCEPTIONAL));
        normal = afterEx.get(ExitType.NORMAL);

        Map<ISSABasicBlock, PDGContext> out = new LinkedHashMap<>();
        PreciseExceptionResults pe = interProc.getPreciseExceptionResults();

        for (ISSABasicBlock succ : getExceptionalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                Set<TypeReference> exes = pe.getExceptions(current, succ, currentNode);
                PDGContext[] toMerge = new PDGContext[exes.size()];
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
    protected Map<ISSABasicBlock, PDGContext> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getUse(1), ir) + " == 0";
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangArithmeticException, in,
                                        desc, current, cfg);

        PDGContext ex = afterEx.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, ex, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowCheckCast(SSACheckCastInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Possibly throw ClassCastException
        PDGContext in = confluence(previousItems, current);
        String desc = "!" + PrettyPrinter.valString(i.getVal(), ir) + " instanceof "
                                        + PrettyPrinter.parseType(i.getDeclaredResultTypes()[0]);
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangClassCastException, in, desc,
                                        current, cfg);

        PDGContext classCast = afterEx.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, classCast, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        String cond = PrettyPrinter.valString(i.getUse(0), ir) + " "
                                        + PrettyPrinter.conditionalOperatorString(i.getOperator()) + " "
                                        + PrettyPrinter.valString(i.getUse(1), ir);
        PDGNode truePC = PDGNodeFactory.findOrCreateOther(cond, PDGNodeType.BOOLEAN_TRUE_PC, currentNode, i);
        PDGNode falsePC = PDGNodeFactory.findOrCreateOther(cond, PDGNodeType.BOOLEAN_FALSE_PC, currentNode, i);

        Map<ISSABasicBlock, PDGContext> out = new LinkedHashMap<>();

        ISSABasicBlock trueSucc = getTrueSuccessor(current, cfg);
        out.put(trueSucc, new PDGContext(null, null, truePC));

        ISSABasicBlock falseSucc = getFalseSuccessor(current, cfg);
        out.put(falseSucc, new PDGContext(null, null, falsePC));

        return out;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowGetField(SSAGetInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {

        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getRef(), ir) + " == null";
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException, in,
                                        desc, current, cfg);

        PDGContext npe = afterEx.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowInvokeInterface(SSAInvokeInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvokeVirtual(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowInvokeSpecial(SSAInvokeInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvokeVirtual(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowInvokeStatic(SSAInvokeInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowInvokeVirtual(SSAInvokeInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    private Map<ISSABasicBlock, PDGContext> flowInvoke(SSAInvokeInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGContext in = confluence(previousItems, current);
        PDGContext npe = null;
        if (!i.isStatic()) {
            // Could throw NPE
            String desc = PrettyPrinter.valString(i.getReceiver(), ir) + " == null";
            Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException, in,
                                            desc, current, cfg);

            npe = afterEx.get(ExitType.EXCEPTIONAL);

            // TODO if no NPE throw WrongMethodTypeException
        }

        // Join the caller PC before the call to the PC after the call
        PDGNode normalExitPC = PDGNodeFactory.findOrCreateOther("EXIT_PC_JOIN", PDGNodeType.EXIT_PC_JOIN, currentNode,
                                        new OrderedPair<>(i, ExitType.NORMAL));

        PDGNode exExitPC = PDGNodeFactory.findOrCreateOther("EXIT_PC_JOIN", PDGNodeType.EXIT_PC_JOIN, currentNode,
                                        new OrderedPair<>(i, ExitType.EXCEPTIONAL));

        Map<ISSABasicBlock, PDGContext> out = new LinkedHashMap<>();

        PDGNode exValue = PDGNodeFactory.findOrCreateLocal(i.getException(), currentNode);
        PDGContext exContext = new PDGContext(null, exValue, exExitPC);
        callSiteExceptionContexts.put(i, exContext);

        PDGContext mergedExContext = null;
        if (npe != null) {
            // If this instruction can throw a NPE due to the receiver being
            // null then we need to merge that context into the exceptional
            // context for the callee and use that on any edges on which the NPE
            // can be thrown
            mergedExContext = mergeContexts(mergeContexts(new OrderedPair<>(i, "NPE_MERGE"), npe, exContext));
        }

        PreciseExceptionResults pe = interProc.getPreciseExceptionResults();
        for (ISSABasicBlock succ : getExceptionalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                boolean canThrowNPE = pe.getExceptions(current, succ, currentNode).contains(
                                                TypeReference.JavaLangNullPointerException);
                if (canThrowNPE && npe != null) {
                    out.put(succ, mergedExContext);
                } else {
                    out.put(succ, exContext);
                }
            }
        }

        for (ISSABasicBlock succ : getNormalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                out.put(succ, new PDGContext(null, null, normalExitPC));
            }
        }

        return out;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowGoto(SSAGotoInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowLoadMetadata(SSALoadMetadataInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // This can throw a ClassNotFoundException, but this could be known
        // statically if all the class files were known.
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowMonitor(SSAMonitorInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getRef(), ir) + " == null";
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException, in,
                                        desc, current, cfg);

        PDGContext npe = afterEx.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowNewArray(SSANewInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGContext in = confluence(previousItems, current);
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangNegativeArraySizeException,
                                        in, "size < 0", current, cfg);

        PDGContext negArraySize = afterEx.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, negArraySize, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowNewObject(SSANewInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowPutField(SSAPutInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Possibly throw NPE
        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getRef(), ir) + " == null";
        Map<ExitType, PDGContext> afterNPE = handlePossibleException(TypeReference.JavaLangNullPointerException, in,
                                        desc, current, cfg);

        PDGContext npe = afterNPE.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterNPE.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowReturn(SSAReturnInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGContext in = confluence(previousItems, current);
        PDGNode returnValue = i.getResult() > 0 ? PDGNodeFactory.findOrCreateUse(i, 0, currentNode) : null;
        return factToMap(new PDGContext(returnValue, null, in.getPCNode()), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowSwitch(SSASwitchInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode newPC = PDGNodeFactory.findOrCreateOther("switch PC", PDGNodeType.PC_OTHER, currentNode, i);
        return factToMap(new PDGContext(null, null, newPC), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowThrow(SSAThrowInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Can throw NPE, but this does not affect the control flow so no need
        // to account for that
        PDGContext in = confluence(previousItems, current);
        PDGNode exception = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        return factToMap(new PDGContext(null, exception, in.getPCNode()), current, cfg);
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        // Check whether this is an exception edge with no exceptions
        boolean unreachableExceptionEdge = false;
        SSACFG cfg = currentNode.getIR().getControlFlowGraph();
        if (cfg.getExceptionalSuccessors(source).contains(target)) {
            PreciseExceptionResults pe = interProc.getPreciseExceptionResults();
            unreachableExceptionEdge = pe.getExceptions(source, target, currentNode).isEmpty();
        }

        return unreachableExceptionEdge
                                        || interProc.getReachabilityResults()
                                                                        .isUnreachable(source, target, currentNode);
    }

    /**
     * Check whether an exception of the given type can be thrown by the basic
     * block (on at least one successor edge).
     * 
     * @param type
     *            type of exception
     * @param bb
     *            basic block to check
     * @param cfg
     *            control flow graph
     * @return whether an exception of <code>type</code> can be thrown by
     *         <code>bb</code>
     */
    private boolean mayThrowException(TypeReference type, ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        for (ISSABasicBlock succ : getExceptionalSuccs(bb, cfg)) {
            if (interProc.getPreciseExceptionResults().getExceptions(bb, succ, currentNode).contains(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determine whether a exception of the given type can be thrown and create
     * nodes and edges in the PDG to capture the dependencies when an exception
     * is thrown by the JVM (e.g. a {@link NullPointerException}).
     * 
     * @param exType
     *            type of exception being thrown
     * @param beforeException
     *            context (including the PC ndoe) immediately before the
     *            exception is thrown
     * @param branchDescription
     *            description of the condition that causes the exception. The
     *            condition being true should result in the exception. (e.g. o
     *            == null for an NPE, index > a.length for an
     *            ArrayIndexOutOfBoundsException).
     * @param bb
     *            basic block that could throw the exception
     * @param cfg
     *            control flow graph
     * @return Two PDG contexts one for when the exception is not thrown and one
     *         for when it is thrown. If the exception cannot be thrown then the
     *         exceptional context will be null.
     */
    private Map<ExitType, PDGContext> handlePossibleException(TypeReference exType, PDGContext beforeException,
                                    String branchDescription, ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        SSAInstruction i = getLastInstruction(bb);
        if (mayThrowException(exType, bb, cfg)) {
            Map<ExitType, PDGContext> out = new LinkedHashMap<>();
            PDGNode truePC = PDGNodeFactory.findOrCreateOther(branchDescription, PDGNodeType.BOOLEAN_TRUE_PC,
                                            currentNode, i);
            PDGNode falsePC = PDGNodeFactory.findOrCreateOther(branchDescription, PDGNodeType.BOOLEAN_FALSE_PC,
                                            currentNode, i);
            PDGNode exceptionValue = PDGNodeFactory.findOrCreateGeneratedException(exType, currentNode, i);

            PDGContext ex = new PDGContext(null, exceptionValue, truePC);
            PDGContext normal = new PDGContext(null, null, falsePC);
            out.put(ExitType.NORMAL, normal);
            out.put(ExitType.EXCEPTIONAL, ex);
            return out;
        } else {
            return Collections.singletonMap(ExitType.NORMAL, beforeException);
        }
    }

    /**
     * Merge contexts and add appropriate edges to the PDG.
     * 
     * @param disambuationKey
     *            key used to distinguish nodes (in addition to the call graph
     *            node and node type)
     * @param contexts
     *            non-null contexts to merge (array cannot be empty)
     * @return merged context
     */
    private PDGContext mergeContexts(Object disambiguationKey, PDGContext... contexts) {
        assert contexts.length > 0 : "empty context array in mergeContexts " + "\n\tIN "
                                        + PrettyPrinter.parseCGNode(currentNode) + "\nKEY: " + disambiguationKey;
        assert !(disambiguationKey instanceof PDGContext) : "Missing disambiguation key.";
        if (contexts.length == 1) {
            return contexts[0];
        }

        Set<PDGNode> exceptions = new LinkedHashSet<>();
        Set<PDGNode> returns = new LinkedHashSet<>();
        Set<PDGNode> pcs = new LinkedHashSet<>();
        for (PDGContext c : contexts) {
            assert c != null : "empty context array in mergeContexts " + "\n\tIN "
                                            + PrettyPrinter.parseCGNode(currentNode) + "\nKEY: " + disambiguationKey;
            if (c.getExceptionNode() != null) {
                exceptions.add(c.getExceptionNode());
            }
            if (c.getReturnNode() != null) {
                returns.add(c.getPCNode());
            }
            pcs.add(c.getPCNode());
        }

        PDGNode newEx = mergeIfNecessary(exceptions, "EX MERGE", PDGNodeType.EXCEPTION_MERGE, disambiguationKey);
        PDGNode newRet = mergeIfNecessary(exceptions, "RETURN MERGE", PDGNodeType.OTHER_EXPRESSION, disambiguationKey);
        PDGNode newPC = mergeIfNecessary(exceptions, "PC MERGE", PDGNodeType.PC_MERGE, disambiguationKey);
        PDGContext result = new PDGContext(newRet, newEx, newPC);
        return result;
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
     * @return new merge node if any, if the set contains only one node then
     *         that node is returned, null if the set of nodes is empty
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
     * Get the input context for the each instruction, this is unsound until
     * after the analysis completes
     * 
     * @return Map from instruction to input context
     */
    protected Map<SSAInstruction, PDGContext> getInstructionInput() {
        Map<SSAInstruction, PDGContext> res = new LinkedHashMap<>();
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            for (SSAInstruction i : bb) {
                AnalysisRecord<PDGContext> rec = getAnalysisRecord(i);
                if (rec != null) {
                    res.put(i, confluence(rec.getInput(), bb));
                }
            }
        }
        return res;
    }

    /**
     * Get the input contexts for the each basic block, this is unsound until
     * after the analysis completes
     * 
     * @return Map from basic block to input contexts
     */
    protected Map<ISSABasicBlock, Set<PDGContext>> getInputContexts() {
        Map<ISSABasicBlock, Set<PDGContext>> res = new LinkedHashMap<>();
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            AnalysisRecord<PDGContext> rec = getAnalysisRecord(bb);
            if (rec != null) {
                res.put(bb, rec.getInput());
            }
        }
        return res;
    }

    /**
     * Get a map from basic block to PDGContexts for successor blocks
     * 
     * @return map from basic block to output items
     */
    private Map<ISSABasicBlock, Map<ISSABasicBlock, PDGContext>> getOutputContexts() {
        Map<ISSABasicBlock, Map<ISSABasicBlock, PDGContext>> res = new LinkedHashMap<>();
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            AnalysisRecord<PDGContext> rec = getAnalysisRecord(bb);
            if (rec != null) {
                res.put(bb, rec.getOutput());
            }
        }
        return res;
    }
}
