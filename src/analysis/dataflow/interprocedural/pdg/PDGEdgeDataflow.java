package analysis.dataflow.interprocedural.pdg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.interprocedural.pdg.graph.CallSiteEdgeLabel;
import analysis.dataflow.interprocedural.pdg.graph.CallSiteEdgeLabel.SiteType;
import analysis.dataflow.interprocedural.pdg.graph.PDGEdgeType;
import analysis.dataflow.interprocedural.pdg.graph.node.AbstractLocationPDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeFactory;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeType;
import analysis.dataflow.interprocedural.pdg.graph.node.ProcedureSummaryNodes;
import analysis.dataflow.util.AbstractLocation;
import analysis.dataflow.util.Unit;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
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
public class PDGEdgeDataflow extends InstructionDispatchDataFlow<Unit> {

    private final CGNode currentNode;
    private final PDGInterproceduralDataFlow interProc;
    private final ProcedureSummaryNodes summary;
    private final IR ir;
    private final CallGraph cg;
    private final PointsToGraph ptg;
    private final WalaAnalysisUtil util;
    private final Map<ISSABasicBlock, Set<PDGContext>> inputFacts;
    private final Map<ISSABasicBlock, Map<ISSABasicBlock, PDGContext>> outputFacts;
    private final Map<SSAInstruction, PDGContext> instructionInput;
    private final Map<SSAInvokeInstruction, PDGContext> callSiteExceptionContexts;
    private final Map<PDGNode, Set<PDGNode>> mergeNodes;

    public PDGEdgeDataflow(CGNode currentNode, PDGInterproceduralDataFlow interProc, ProcedureSummaryNodes summary,
                                    WalaAnalysisUtil util, Map<PDGNode, Set<PDGNode>> mergeNodes,
                                    Map<SSAInvokeInstruction, PDGContext> callSiteExceptionContexts,
                                    Map<ISSABasicBlock, Set<PDGContext>> inputFacts,
                                    Map<ISSABasicBlock, Map<ISSABasicBlock, PDGContext>> outputFacts,
                                    Map<SSAInstruction, PDGContext> instructionInput) {
        super(true);
        this.currentNode = currentNode;
        this.interProc = interProc;
        this.summary = summary;
        this.ir = currentNode.getIR();
        this.cg = interProc.getCallGraph();
        this.ptg = interProc.getPointsToGraph();
        this.util = util;
        this.callSiteExceptionContexts = callSiteExceptionContexts;
        this.mergeNodes = mergeNodes;
        this.inputFacts = inputFacts;
        this.outputFacts = outputFacts;
        this.instructionInput = instructionInput;
    }

    /**
     * Perform the dataflow
     */
    protected void dataflow() {
        dataflow(ir);
    }

    @Override
    protected void post(IR ir) {
        // Hook up the exceptions and returns to the summary nodes
        ISSABasicBlock exit = ir.getExitBlock();

        PDGContext exExit = summary.getExceptionalExitContext();
        for (ISSABasicBlock pred : ir.getControlFlowGraph().getExceptionalPredecessors(exit)) {
            if (!isUnreachable(pred, exit)) {
                PDGContext predContext = outputFacts.get(pred).get(exit);
                addEdge(predContext.getPCNode(), exExit.getPCNode(), PDGEdgeType.MERGE);
                addEdge(predContext.getExceptionNode(), exExit.getExceptionNode(), PDGEdgeType.MERGE);
            }
        }

        PDGContext normExit = summary.getNormalExitContext();
        for (ISSABasicBlock pred : ir.getControlFlowGraph().getNormalPredecessors(exit)) {
            if (!isUnreachable(pred, exit)) {
                PDGContext predContext = outputFacts.get(pred).get(exit);
                addEdge(predContext.getPCNode(), normExit.getPCNode(), PDGEdgeType.MERGE);
                if (ir.getMethod().getReturnType() != TypeReference.Void) {
                    addEdge(predContext.getReturnNode(), normExit.getReturnNode(), PDGEdgeType.MERGE);
                }
            }
        }

        // TODO add edges for all the merged contexts
    }

    // /**
    // * If this node is an immediate post dominator, then return the PC node of
    // * the appropriate post dominated node. Returns null otherwise.
    // *
    // * @param bb
    // * Basic block the post dominator for
    // *
    // * @return The PC node of the post dominated node, null if this node is
    // not
    // * a post-dominator
    // */
    // private PDGNode handlePostDominators(ISSABasicBlock bb) {
    //
    // if (bb.equals(ir.getControlFlowGraph().exit())) {
    // // Do not restore for exit block since we don't actually use that PC
    // // anywhere
    // return null;
    // }
    //
    // // The iterator from the dominators is immutable and we need to remove
    // // elements so we will create a set iterator and use that
    // Set<ISSABasicBlock> postDoms =
    // IteratorSet.make(postDominators.dominators(bb));
    // postDoms.remove(bb);
    // if (postDoms.size() > 1) {
    // Iterator<ISSABasicBlock> iter = postDoms.iterator();
    // while (iter.hasNext()) {
    // // If there is more than one post-dominated node try to find the
    // // one that dominates the others
    // ISSABasicBlock dom = iter.next();
    // Set<ISSABasicBlock> doms = IteratorSet.make(dominators.dominators(dom));
    // doms.retainAll(postDoms);
    // if (!doms.isEmpty()) {
    // // one of the doms is also in pd, so we can drop this one
    // iter.remove();
    // }
    // }
    // }
    // if (postDoms.size() == 1) {
    // // there is exactly one basic block, bb, such that the current basic
    // // block is the immediate post-dominator of bb and bb is not
    // // dominated by another basic block bb2 such that the current basic
    // // block is the immediate post-dominator of bb2.
    //
    // // Restore the PC!
    // ISSABasicBlock postDominated = postDoms.iterator().next();
    // PDGContext postDomContext =
    // inputFacts.get(postDominated.iterator().next());
    // return postDomContext.getPCNode();
    // } else {
    // // we can't restore the PC, since there is no unique PC to restore
    // // it to.
    // return null;
    // }
    // }

    @Override
    protected Unit confluence(Set<Unit> facts, ISSABasicBlock bb) {
        return Unit.VALUE;
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flow(Set<Unit> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock current) {
        return super.flow(inItems, cfg, current);
    }

    @Override
    protected Unit flowBinaryOp(SSABinaryOpInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode v0 = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode v1 = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);
        PDGNode assignee = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        PDGContext in = instructionInput.get(i);

        addEdge(v0, assignee, PDGEdgeType.EXP);
        addEdge(v1, assignee, PDGEdgeType.EXP);
        addEdge(in.getPCNode(), assignee, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowComparison(SSAComparisonInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode v0 = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode v1 = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);
        PDGNode assignee = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        addEdge(v0, assignee, PDGEdgeType.EXP);
        addEdge(v1, assignee, PDGEdgeType.EXP);

        PDGContext in = instructionInput.get(i);
        addEdge(in.getPCNode(), assignee, PDGEdgeType.IMPLICIT);
        return Unit.VALUE;
    }

    @Override
    protected Unit flowConversion(SSAConversionInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode converted = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        addEdge(converted, result, PDGEdgeType.COPY);

        PDGContext in = instructionInput.get(i);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);
        return Unit.VALUE;
    }

    @Override
    protected Unit flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert i == current.iterator().next() : "Exception catch should be the first instruction in a basic block\n"
                                        + PrettyPrinter.basicBlockString(ir, current, "\t", "\n");

        // Merge exceptions
        PDGNode caughtEx = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        for (PDGContext fact : inputFacts.get(current)) {
            PDGNode inEx = fact.getExceptionNode();
            addEdge(inEx, caughtEx, PDGEdgeType.MERGE);
        }

        return Unit.VALUE;
    }

    @Override
    protected Unit flowGetStatic(SSAGetInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        AbstractLocation fieldLoc = AbstractLocation.createStatic(i.getDeclaredField());
        AbstractLocationPDGNode fieldNode = PDGNodeFactory.findOrCreateAbstractLocation(fieldLoc);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        addEdge(fieldNode, result, PDGEdgeType.COPY);

        PDGContext in = instructionInput.get(i);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);
        return Unit.VALUE;
    }

    @Override
    protected Unit flowInstanceOf(SSAInstanceofInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {

        boolean instanceOfAlwaysFalse = true;
        boolean instanceOfAlwaysTrue = true;
        IClass checked = util.getClassHierarchy().lookupClass(i.getCheckedType());
        for (InstanceKey hContext : ptg.getPointsToSet(interProc.getReplica(i.getRef(), currentNode))) {
            IClass actual = hContext.getConcreteType();
            if (util.getClassHierarchy().isSubclassOf(actual, checked)) {
                instanceOfAlwaysFalse = false;
            } else {
                instanceOfAlwaysTrue = false;
            }

            if (!instanceOfAlwaysFalse && !instanceOfAlwaysTrue) {
                break;
            }
        }

        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        if (!instanceOfAlwaysFalse && !instanceOfAlwaysTrue) {
            // This check does not always return the same value so the result
            // depends on the input.
            PDGNode refNode = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
            addEdge(refNode, result, PDGEdgeType.EXP);
        }

        PDGContext in = instructionInput.get(i);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);
        return Unit.VALUE;
    }

    @Override
    protected Unit flowPhi(SSAPhiInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int useNum = i.getUse(j);
            PDGNode choice = PDGNodeFactory.findOrCreateUse(i, useNum, currentNode);
            addEdge(choice, result, PDGEdgeType.MERGE);
        }

        PDGContext in = instructionInput.get(i);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);
        return Unit.VALUE;
    }

    @Override
    protected Unit flowPutStatic(SSAPutInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        AbstractLocation fieldLoc = AbstractLocation.createStatic(i.getDeclaredField());
        AbstractLocationPDGNode fieldNode = PDGNodeFactory.findOrCreateAbstractLocation(fieldLoc);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);

        // create intermediate node for this assignment
        String desc = PrettyPrinter.instructionString(i, currentNode.getIR());
        PDGNode assignment = PDGNodeFactory.findOrCreateOther(desc, PDGNodeType.OTHER_EXPRESSION, currentNode, i);

        addEdge(value, assignment, PDGEdgeType.COPY);
        addEdge(assignment, fieldNode, PDGEdgeType.MERGE);

        PDGContext in = instructionInput.get(i);
        // The PC edge is into the assignment node as this is what depends on
        // reaching the current program point
        addEdge(in.getPCNode(), assignment, PDGEdgeType.IMPLICIT);
        return Unit.VALUE;
    }

    @Override
    protected Unit flowUnaryNegation(SSAUnaryOpInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {

        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        addEdge(value, result, PDGEdgeType.EXP);

        PDGContext in = instructionInput.get(i);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);
        return Unit.VALUE;
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayLength(SSAArrayLengthInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode array = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        addEdge(array, result, PDGEdgeType.EXP);

        PDGContext in = instructionInput.get(i);

        String desc = PrettyPrinter.valString(i.getArrayRef(), ir) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, array, in, desc,
                                        current, cfg);

        // The only way the value gets assigned is if no NPE is thrown
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayLoad(SSAArrayLoadInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode array = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode index = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        // Possibly throw NPE
        PDGContext in = instructionInput.get(i);
        String desc = PrettyPrinter.valString(i.getArrayRef(), ir) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, array, in, desc,
                                        current, cfg);

        // Node and edges for the access itself. This extra node is created
        // because it is the access itself that can cause an
        // ArrayIndexOutOfBoundsException.
        String d = PrettyPrinter.valString(i.getArrayRef(), ir) + "[" + PrettyPrinter.valString(i.getIndex(), ir) + "]";
        PDGNode arrayAccess = PDGNodeFactory.findOrCreateOther(d, PDGNodeType.OTHER_EXPRESSION, currentNode, i);
        addEdge(array, arrayAccess, PDGEdgeType.EXP);
        addEdge(index, arrayAccess, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), arrayAccess, PDGEdgeType.IMPLICIT);

        // Add edges from the array contents to the access
        Set<PDGNode> locNodes = new LinkedHashSet<>();
        for (AbstractLocation loc : interProc.getLocationsForArrayContents(i.getArrayRef(), currentNode)) {
            locNodes.add(PDGNodeFactory.findOrCreateAbstractLocation(loc));
        }
        PDGNode locMerge = mergeIfNecessary(locNodes, "ABS LOC MERGE", PDGNodeType.LOCATION_SUMMARY, i);
        addEdge(locMerge, arrayAccess, PDGEdgeType.COPY);

        // If no NPE is thrown then this may throw an
        // ArrayIndexOutOfBoundsException
        String isOOB = PrettyPrinter.valString(i.getIndex(), ir) + " >= "
                                        + PrettyPrinter.valString(i.getArrayRef(), ir) + ".length";
        normal = handlePossibleException(TypeReference.JavaLangArrayIndexOutOfBoundsException, arrayAccess, normal,
                                        isOOB, current, cfg);

        // The only way the value gets assigned is if no exception is thrown
        addEdge(arrayAccess, result, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayStore(SSAArrayStoreInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode array = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode index = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 2, currentNode);

        // Possibly throw NPE
        PDGContext in = instructionInput.get(i);
        String desc = PrettyPrinter.valString(i.getArrayRef(), ir) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, array, in, desc,
                                        current, cfg);

        // Node and edges for the access itself. This extra node is created
        // because it is the access itself that can cause an
        // ArrayIndexOutOfBoundsException.
        String d = PrettyPrinter.valString(i.getArrayRef(), ir) + "[" + PrettyPrinter.valString(i.getIndex(), ir) + "]";
        PDGNode arrayAccess = PDGNodeFactory.findOrCreateOther(d, PDGNodeType.OTHER_EXPRESSION, currentNode,
                                        new OrderedPair<>(i, "ARRAY_ACCESS"));
        addEdge(array, arrayAccess, PDGEdgeType.EXP);
        addEdge(index, arrayAccess, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), arrayAccess, PDGEdgeType.IMPLICIT);

        // If no NPE is thrown then this may throw an
        // ArrayIndexOutOfBoundsException
        String isOOB = PrettyPrinter.valString(i.getIndex(), ir) + " >= "
                                        + PrettyPrinter.valString(i.getArrayRef(), ir) + ".length";
        normal = handlePossibleException(TypeReference.JavaLangArrayIndexOutOfBoundsException, arrayAccess, normal,
                                        isOOB, current, cfg);

        // Node and edges for the assignment. This extra node is created
        // because it is the assignment that can cause an
        // ArrayStoreException.
        String storeDesc = "STORE " + PrettyPrinter.instructionString(i, ir);
        PDGNode store = PDGNodeFactory.findOrCreateOther(storeDesc, PDGNodeType.OTHER_EXPRESSION, currentNode,
                                        new OrderedPair<>(i, "STORE"));
        addEdge(arrayAccess, store, PDGEdgeType.EXP);
        addEdge(value, store, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), store, PDGEdgeType.IMPLICIT);

        // If no ArrayIndexOutOfBoundsException is thrown then this may throw an
        // ArrayStoreException
        String arrayStoreDesc = "!" + PrettyPrinter.valString(i.getValue(), ir) + " instanceof "
                                        + PrettyPrinter.valString(i.getArrayRef(), ir) + ".elementType";
        normal = handlePossibleException(TypeReference.JavaLangArrayStoreException, store, normal, arrayStoreDesc,
                                        current, cfg);

        // If there are no exceptions then the assignment occurs
        String resultDesc = PrettyPrinter.instructionString(i, ir);
        PDGNode result = PDGNodeFactory.findOrCreateOther(resultDesc, PDGNodeType.OTHER_EXPRESSION, currentNode,
                                        new OrderedPair<>(i, "RESULT"));
        addEdge(store, result, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        // Add edge from the assignment to the array contents
        for (AbstractLocation loc : interProc.getLocationsForArrayContents(i.getArrayRef(), currentNode)) {
            PDGNode locNode = PDGNodeFactory.findOrCreateAbstractLocation(loc);
            addEdge(result, locNode, PDGEdgeType.MERGE);
        }

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowBinaryOpWithException(SSABinaryOpInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode v0 = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode v1 = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);
        PDGNode assignee = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        PDGContext in = instructionInput.get(i);
        String desc = PrettyPrinter.valString(i.getUse(1), ir) + " == 0";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangArithmeticException, v1, in, desc, current,
                                        cfg);

        // The only way the value gets assigned is if no exception is thrown
        addEdge(v0, assignee, PDGEdgeType.EXP);
        addEdge(v1, assignee, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), assignee, PDGEdgeType.IMPLICIT);
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowCheckCast(SSACheckCastInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);

        // Possibly throw ClassCastException
        PDGContext in = instructionInput.get(i);
        String desc = "!" + PrettyPrinter.valString(i.getVal(), ir) + " instanceof "
                                        + PrettyPrinter.parseType(i.getDeclaredResultTypes()[0]);
        PDGContext normal = handlePossibleException(TypeReference.JavaLangClassCastException, value, in, desc, current,
                                        cfg);

        // If there is no exception then assign
        addEdge(value, result, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<Unit> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock current) {
        String cond = PrettyPrinter.valString(i.getUse(0), ir) + " "
                                        + PrettyPrinter.conditionalOperatorString(i.getOperator()) + " "
                                        + PrettyPrinter.valString(i.getUse(1), ir);
        PDGNode condNode = PDGNodeFactory.findOrCreateOther(cond, PDGNodeType.OTHER_EXPRESSION, currentNode, i);
        PDGNode val0 = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode val1 = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);

        addEdge(val0, condNode, PDGEdgeType.EXP);
        addEdge(val1, condNode, PDGEdgeType.EXP);

        PDGContext in = instructionInput.get(i);

        ISSABasicBlock trueSucc = getTrueSuccessor(current, cfg);
        PDGNode truePC = outputFacts.get(current).get(trueSucc).getPCNode();
        addEdge(in.getPCNode(), truePC, PDGEdgeType.CONJUNCTION);

        ISSABasicBlock falseSucc = getFalseSuccessor(current, cfg);
        PDGNode falsePC = outputFacts.get(current).get(falseSucc).getPCNode();
        addEdge(in.getPCNode(), falsePC, PDGEdgeType.CONJUNCTION);

        addEdge(condNode, truePC, PDGEdgeType.TRUE);
        addEdge(condNode, falsePC, PDGEdgeType.FALSE);
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowGetField(SSAGetInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode target = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        PDGContext in = instructionInput.get(i);
        String desc = PrettyPrinter.valString(i.getRef(), ir) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, target, in, desc,
                                        current, cfg);

        // The only way the value gets assigned is if no NPE is thrown

        // Add edges from the field to the result
        Set<PDGNode> locNodes = new LinkedHashSet<>();
        for (AbstractLocation loc : interProc.getLocationsForNonStaticField(i.getRef(), i.getDeclaredField(),
                                        currentNode)) {
            locNodes.add(PDGNodeFactory.findOrCreateAbstractLocation(loc));
        }

        PDGNode locMerge = mergeIfNecessary(locNodes, "ABS LOC MERGE", PDGNodeType.LOCATION_SUMMARY, i);
        addEdge(locMerge, result, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeInterface(SSAInvokeInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvokeVirtual(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeSpecial(SSAInvokeInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvokeVirtual(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeStatic(SSAInvokeInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeVirtual(SSAInvokeInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    private Map<ISSABasicBlock, Unit> flowInvoke(SSAInvokeInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        CallSiteEdgeLabel entry = new CallSiteEdgeLabel(i.getCallSite(), currentNode, SiteType.ENTRY);
        CallSiteEdgeLabel exit = new CallSiteEdgeLabel(i.getCallSite(), currentNode, SiteType.EXIT);

        List<PDGNode> params = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            params.add(PDGNodeFactory.findOrCreateUse(i, j, currentNode));
        }

        PDGContext in = instructionInput.get(i);
        PDGContext npe = null;
        PDGContext normal = in;
        if (!i.isStatic()) {
            // Could throw NPE
            String desc = PrettyPrinter.valString(i.getReceiver(), ir) + " == null";
            normal = handlePossibleException(TypeReference.JavaLangNullPointerException, params.get(0), in, desc,
                                            current, cfg);

            // TODO if no NPE throw WrongMethodTypeException
        }

        // Assign actuals to formal assignment nodes in the caller
        List<PDGNode> formalAssignments = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            String s = "formal(" + j + ") = " + PrettyPrinter.valString(i.getUse(j), ir);
            PDGNode formalAssign = PDGNodeFactory.findOrCreateOther(s, PDGNodeType.FORMAL_ASSIGNMENT, currentNode,
                                            new OrderedPair<>(i, j));
            formalAssignments.add(formalAssign);
            addEdge(params.get(j), formalAssign, PDGEdgeType.COPY);
            addEdge(normal.getPCNode(), formalAssign, PDGEdgeType.IMPLICIT);
        }

        Set<PDGContext> calleeNormalExits = new LinkedHashSet<>();
        Set<PDGContext> calleeExceptionalExits = new LinkedHashSet<>();
        for (CGNode callee : cg.getPossibleTargets(currentNode, i.getCallSite())) {
            ProcedureSummaryNodes calleeSummary = interProc.getProcedureSummary(callee);
            PDGContext calleeEntry = calleeSummary.getEntryContext();
            addEdge(normal.getPCNode(), calleeEntry.getPCNode(), PDGEdgeType.MERGE, entry);

            // Copy formalAssignments into formals
            for (int j = 0; j < i.getNumberOfParameters(); j++) {
                PDGNode formal = calleeSummary.getFormal(j);
                addEdge(formalAssignments.get(j), formal, PDGEdgeType.MERGE, entry);
            }

            // TODO keep relevant nodes instead
            calleeNormalExits.add(calleeSummary.getNormalExitContext());
            calleeExceptionalExits.add(calleeSummary.getExceptionalExitContext());
        }

        // TODO merge nodes instead
        PDGContext calleeNormalExit = null;
        // mergeContexts(new OrderedPair<>(i, ExitType.NORMAL),
        // calleeNormalExits.toArray(new PDGContext[calleeNormalExits.size()]));
        PDGContext calleeExceptionalExit = null;
        // mergeContexts(new OrderedPair<>(i, ExitType.EXCEPTIONAL),
        // calleeExceptionalExits.toArray(new
        // PDGContext[calleeExceptionalExits.size()]));

        // Join the caller PC before the call to the PC after the call
        PDGNode normalExitPC = PDGNodeFactory.findOrCreateOther("EXIT_PC_JOIN", PDGNodeType.EXIT_PC_JOIN, currentNode,
                                        new OrderedPair<>(i, ExitType.NORMAL));
        addEdge(calleeNormalExit.getPCNode(), normalExitPC, PDGEdgeType.CONJUNCTION, exit);
        addEdge(normal.getPCNode(), normalExitPC, PDGEdgeType.CONJUNCTION);

        PDGNode exExitPC = PDGNodeFactory.findOrCreateOther("EXIT_PC_JOIN", PDGNodeType.EXIT_PC_JOIN, currentNode,
                                        new OrderedPair<>(i, ExitType.EXCEPTIONAL));
        addEdge(calleeExceptionalExit.getPCNode(), exExitPC, PDGEdgeType.CONJUNCTION, exit);
        addEdge(normal.getPCNode(), exExitPC, PDGEdgeType.CONJUNCTION);

        // Copy return if any
        if (i.getNumberOfReturnValues() > 0) {
            PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
            addEdge(calleeNormalExit.getReturnNode(), result, PDGEdgeType.COPY, exit);
            addEdge(normalExitPC, result, PDGEdgeType.IMPLICIT);
        }

        Map<ISSABasicBlock, PDGContext> out = new LinkedHashMap<>();

        // TODO may have some extra edges if no exception can be thrown, but
        // they will be disconnected from the rest of the graph
        // same with normal termination

        PDGNode exValue = PDGNodeFactory.findOrCreateLocal(i.getException(), currentNode);
        addEdge(calleeExceptionalExit.getExceptionNode(), exValue, PDGEdgeType.COPY, exit);
        addEdge(exExitPC, exValue, PDGEdgeType.IMPLICIT);

        // TODO Get this from the map and use it above
        PDGContext exContext = new PDGContext(null, exValue, exExitPC);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowGoto(SSAGotoInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowLoadMetadata(SSALoadMetadataInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowMonitor(SSAMonitorInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode ref = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);

        PDGContext in = instructionInput.get(i);
        String desc = PrettyPrinter.valString(i.getRef(), ir) + " == null";
        handlePossibleException(TypeReference.JavaLangNullPointerException, ref, in, desc, current, cfg);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowNewArray(SSANewInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode allSizes = null;
        if (i.getNumberOfUses() > 1) {
            allSizes = PDGNodeFactory.findOrCreateOther(PrettyPrinter.rightSideString(i, ir) + " indices",
                                            PDGNodeType.OTHER_EXPRESSION, currentNode, new OrderedPair<>(i, "INDICES"));
            for (int j = 0; j < i.getNumberOfUses(); j++) {
                PDGNode size = PDGNodeFactory.findOrCreateUse(i, j, currentNode);
                addEdge(size, allSizes, PDGEdgeType.EXP);
            }
        } else {
            allSizes = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        }

        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        addEdge(allSizes, result, PDGEdgeType.EXP);

        PDGContext in = instructionInput.get(i);
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNegativeArraySizeException, allSizes, in,
                                        "size < 0", current, cfg);

        // The only way the value gets assigned is if no exception is thrown
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowNewObject(SSANewInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowPutField(SSAPutInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode receiver = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);

        // Possibly throw NPE
        PDGContext in = instructionInput.get(i);
        String desc = PrettyPrinter.valString(i.getRef(), ir) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, receiver, in, desc,
                                        current, cfg);

        // If there are no exceptions then the assignment occurs
        String resultDesc = PrettyPrinter.instructionString(i, ir);
        PDGNode result = PDGNodeFactory.findOrCreateOther(resultDesc, PDGNodeType.OTHER_EXPRESSION, currentNode,
                                        new OrderedPair<>(i, "RESULT"));
        addEdge(value, result, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        // Add edge from the assignment to the field
        for (AbstractLocation loc : interProc.getLocationsForNonStaticField(i.getRef(), i.getDeclaredField(),
                                        currentNode)) {
            PDGNode locNode = PDGNodeFactory.findOrCreateAbstractLocation(loc);
            addEdge(result, locNode, PDGEdgeType.MERGE);
        }

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowReturn(SSAReturnInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowSwitch(SSASwitchInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGContext in = instructionInput.get(i);
        // Create a new PC node based the switched value
        PDGNode switchGuard = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode newPC = outputFacts.get(current).get(getNormalSuccs(current, cfg).iterator().next()).getPCNode();
        addEdge(switchGuard, newPC, PDGEdgeType.SWITCH);
        addEdge(in.getPCNode(), newPC, PDGEdgeType.CONJUNCTION);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowThrow(SSAThrowInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
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
     * Add an edge of the given type from/to a procdure summary node for a
     * callee
     * 
     * @param source
     *            source of the new edge
     * @param target
     *            target of the new edge
     * @param type
     *            type of edge being added
     * @param label
     *            label of the call or return site for the callee
     */
    private void addEdge(PDGNode source, PDGNode target, PDGEdgeType type, CallSiteEdgeLabel label) {
        interProc.getAnalysisResults().addEdge(source, target, type, label);
    }

    /**
     * Add an edge of the given type to the PDG
     * 
     * @param source
     *            source of the new edge
     * @param target
     *            target of the new edge
     * @param type
     *            type of edge being added
     */
    private void addEdge(PDGNode source, PDGNode target, PDGEdgeType type) {
        interProc.getAnalysisResults().addEdge(source, target, type);
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
            addEdgesForMerge(nodesToMerge, result);
        } else if (nodesToMerge.size() == 1) {
            return nodesToMerge.iterator().next();
        } else {
            throw new RuntimeException("Empty set of nodes to merge, DESC: " + mergeNodeDesc + " KEY: "
                                            + disambiguationKey);
        }

        return result;
    }

    /**
     * Add MERGE edges from every node in nodesToMerge to the merge node
     * 
     * @param nodesToMerge
     *            set of sources of the new edges
     * @param mergeNode
     *            target of the new edges
     */
    private void addEdgesForMerge(Set<PDGNode> nodesToMerge, PDGNode mergeNode) {
        for (PDGNode n : nodesToMerge) {
            addEdge(n, mergeNode, PDGEdgeType.MERGE);
        }
    }

    /**
     * Determine whether a exception of the given type can be thrown and create
     * nodes and edges in the PDG to capture the dependencies when an exception
     * is thrown by the JVM (e.g. a {@link NullPointerException}).
     * 
     * @param exType
     *            type of exception being thrown
     * @param cause
     *            cause of the exception (e.g. possibly null value if an NPE)
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
     * @return PDGContext when the exception is not thrown
     */
    private PDGContext handlePossibleException(TypeReference exType, PDGNode cause, PDGContext beforeException,
                                    String branchDescription, ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        SSAInstruction i = getLastInstruction(bb);
        if (mayThrowException(exType, bb, cfg)) {
            PDGNode branch = PDGNodeFactory.findOrCreateOther(branchDescription, PDGNodeType.OTHER_EXPRESSION,
                                            currentNode, i);
            addEdge(cause, branch, PDGEdgeType.EXP);

            // TODO PC must be the same as for the PDGNodeDataFlow
            PDGNode truePC = PDGNodeFactory.findOrCreateOther(branchDescription, PDGNodeType.BOOLEAN_TRUE_PC,
                                            currentNode, i);
            addEdge(branch, truePC, PDGEdgeType.TRUE);
            addEdge(beforeException.getPCNode(), truePC, PDGEdgeType.CONJUNCTION);

            PDGNode falsePC = PDGNodeFactory.findOrCreateOther(branchDescription, PDGNodeType.BOOLEAN_FALSE_PC,
                                            currentNode, i);
            addEdge(branch, falsePC, PDGEdgeType.FALSE);
            addEdge(beforeException.getPCNode(), falsePC, PDGEdgeType.CONJUNCTION);

            return new PDGContext(null, null, falsePC);
        } else {
            return beforeException;
        }
    }
}
