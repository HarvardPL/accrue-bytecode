package analysis.dataflow.interprocedural.pdg;

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

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
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
 * Part of an inter-procedural data-flow, this intra-procedural data-flow adds
 * edges to a program dependence graph (PDG). The nodes at basic block and
 * instruction boundaries and for the program points just after possible
 * exceptions are based on the results of another analysis (e.g.
 * {@link PDGNodeDataflow}.
 */
public class PDGEdgeDataflow extends InstructionDispatchDataFlow<Unit> {

    /**
     * Call graph node this data-flow is over
     */
    private final CGNode currentNode;
    /**
     * Inter-procedural analysis managing the queue of call graph nodes and
     * holding data structures that persist over the analysis of a multiple call
     * graph nodes
     */
    private final PDGInterproceduralDataFlow interProc;
    /**
     * Code we are analyzing
     */
    private final IR ir;
    /**
     * Class hierarchy produced by WALA
     */
    private final IClassHierarchy cha;
    /**
     * Map from basic block to the computed data-flow facts (PCNode, nodes for
     * exceptions and return values) for each exit edge
     */
    private final Map<ISSABasicBlock, Map<ISSABasicBlock, PDGContext>> outputFacts;
    /**
     * Input PC, return, and exception nodes for each instruction (after merging
     * if there are multiple incoming edges)
     */
    private final Map<SSAInstruction, PDGContext> instructionInput;
    /**
     * Map from basic block to context holding the PC node and exception node
     * for the program point just after an exception (of a particular type) is
     * thrown in that basic block
     */
    private final Map<ISSABasicBlock, Map<TypeReference, PDGContext>> trueExceptionContexts;
    /**
     * Map from basic block to context holding the PC node for the program point
     * just after an exception (of a particular type) that could be thrown is
     * NOT thrown
     */
    private final Map<ISSABasicBlock, Map<TypeReference, PDGContext>> falseExceptionContexts;
    /**
     * Map from a merge node to the nodes that were merged when creating that
     * node
     */
    private final Map<PDGNode, Set<PDGNode>> mergeNodes;
    /**
     * Context created for the program point just after a callee throws an
     * exception
     */
    private final Map<CallSiteReference, PDGContext> calleeExceptionContexts;

    /**
     * Create a data-flow that adds edge to a PDG for the given call graph node.
     * 
     * @param currentNode
     *            node this analysis is over
     * @param interProc
     *            Inter-procedural analysis managing the queue of call graph
     *            nodes and holding data structures that persist over the
     *            analysis of a multiple call graph nodes
     * @param util
     *            Class hierarchy produced by WALA and other utility classes
     *            common to a number of analyses
     * @param mergeNodes
     *            Map from a merge node to the nodes that were merged when
     *            creating that node
     * @param trueExceptionContexts
     *            Map from basic block to context holding the PC node and
     *            exception node for the program point just after an exception
     *            (of a particular type) is thrown in that basic block
     * @param falseExceptionContexts
     *            Map from basic block to context holding the PC node for the
     *            program point just after an exception (of a particular type)
     *            that could be thrown is NOT thrown
     * @param calleeExceptionContexts
     *            Context created for the program point just after a callee
     *            throws an exception
     * @param outputFacts
     *            Map from basic block to the computed data-flow facts (PCNode,
     *            nodes for exceptions and return values) for each exit edge
     * @param instructionInput
     *            Map from basic block to context holding the PC node and
     *            exception node for the program point just after an exception
     *            (of a particular type) is thrown in that basic block
     */
    public PDGEdgeDataflow(CGNode currentNode, PDGInterproceduralDataFlow interProc, WalaAnalysisUtil util,
                                    Map<PDGNode, Set<PDGNode>> mergeNodes,
                                    Map<ISSABasicBlock, Map<TypeReference, PDGContext>> trueExceptionContexts,
                                    Map<ISSABasicBlock, Map<TypeReference, PDGContext>> falseExceptionContexts,
                                    Map<CallSiteReference, PDGContext> calleeExceptionContexts,
                                    Map<ISSABasicBlock, Map<ISSABasicBlock, PDGContext>> outputFacts,
                                    Map<SSAInstruction, PDGContext> instructionInput) {
        super(true);
        this.currentNode = currentNode;
        this.interProc = interProc;
        this.ir = currentNode.getIR();
        this.cha = util.getClassHierarchy();
        this.trueExceptionContexts = trueExceptionContexts;
        this.falseExceptionContexts = falseExceptionContexts;
        this.calleeExceptionContexts = calleeExceptionContexts;
        this.mergeNodes = mergeNodes;
        this.outputFacts = outputFacts;
        this.instructionInput = instructionInput;
    }

    /**
     * Perform the data-flow
     */
    protected void dataflow() {
        dataflow(ir);
    }

    @Override
    protected void post(IR ir) {
        // Hook up the exceptions and returns to the summary nodes
        ISSABasicBlock exit = ir.getExitBlock();
        ProcedureSummaryNodes summary = PDGNodeFactory.findOrCreateProcedureSummary(currentNode);

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

        // Add edges from the nodes being merged to the merge nodes
        // This is for merge nodes that were created in the PDGNodeDataFlow
        for (PDGNode mergeNode : mergeNodes.keySet()) {
            Set<PDGNode> nodesToMerge = mergeNodes.get(mergeNode);
            assert !nodesToMerge.isEmpty() : "Empty set of merge nodes for " + mergeNode;
            addEdgesForMerge(nodesToMerge, mergeNode);
        }
    }

    @Override
    protected Unit confluence(Set<Unit> facts, ISSABasicBlock bb) {
        return Unit.VALUE;
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

        PDGContext in = instructionInput.get(i);

        addEdge(v0, assignee, PDGEdgeType.EXP);
        addEdge(v1, assignee, PDGEdgeType.EXP);
        addEdge(in.getPCNode(), assignee, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowConversion(SSAConversionInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode converted = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        PDGContext in = instructionInput.get(i);

        addEdge(converted, result, PDGEdgeType.COPY);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert i == current.iterator().next() : "Exception catch should be the first instruction in a basic block\n"
                                        + PrettyPrinter.basicBlockString(ir, current, "\t", "\n");

        PDGNode caughtEx = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        PDGContext in = instructionInput.get(i);

        addEdge(in.getExceptionNode(), caughtEx, PDGEdgeType.COPY);
        addEdge(in.getPCNode(), caughtEx, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowGetStatic(SSAGetInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        AbstractLocation fieldLoc = AbstractLocation.createStatic(i.getDeclaredField());
        AbstractLocationPDGNode fieldNode = PDGNodeFactory.findOrCreateAbstractLocation(fieldLoc);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        PDGContext in = instructionInput.get(i);

        addEdge(fieldNode, result, PDGEdgeType.COPY);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowInstanceOf(SSAInstanceofInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        boolean instanceOfAlwaysFalse = true;
        boolean instanceOfAlwaysTrue = true;

        // Check if every think the cast object could point to is safe to cast
        IClass checked = cha.lookupClass(i.getCheckedType());
        for (InstanceKey hContext : interProc.getPointsToGraph().getPointsToSet(
                                        interProc.getReplica(i.getRef(), currentNode))) {
            IClass actual = hContext.getConcreteType();
            if (cha.isSubclassOf(actual, checked)) {
                instanceOfAlwaysFalse = false;
            } else {
                instanceOfAlwaysTrue = false;
            }

            if (!instanceOfAlwaysFalse && !instanceOfAlwaysTrue) {
                break;
            }
        }

        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        PDGNode refNode = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);

        PDGContext in = instructionInput.get(i);

        if (!instanceOfAlwaysFalse && !instanceOfAlwaysTrue) {
            // This check does not always return the same value so the result
            // depends on the input.
            addEdge(refNode, result, PDGEdgeType.EXP);
        }
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowPhi(SSAPhiInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        Set<PDGNode> choices = new LinkedHashSet<>();
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int useNum = i.getUse(j);
            choices.add(PDGNodeFactory.findOrCreateUse(i, useNum, currentNode));
        }

        PDGContext in = instructionInput.get(i);

        addEdgesForMerge(choices, result);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowPutStatic(SSAPutInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        AbstractLocation fieldLoc = AbstractLocation.createStatic(i.getDeclaredField());
        AbstractLocationPDGNode fieldNode = PDGNodeFactory.findOrCreateAbstractLocation(fieldLoc);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        // Create intermediate node for this assignment
        // This is done so we can associate the PC node for the program point
        // this assignment happens at with the value being assigned
        String desc = PrettyPrinter.instructionString(i, currentNode.getIR());
        PDGNode assignment = PDGNodeFactory.findOrCreateOther(desc, PDGNodeType.OTHER_EXPRESSION, currentNode, i);

        PDGContext in = instructionInput.get(i);

        addEdge(value, assignment, PDGEdgeType.COPY);
        addEdge(assignment, fieldNode, PDGEdgeType.MERGE);
        addEdge(in.getPCNode(), assignment, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowUnaryNegation(SSAUnaryOpInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);

        PDGContext in = instructionInput.get(i);

        addEdge(value, result, PDGEdgeType.EXP);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayLength(SSAArrayLengthInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode array = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        PDGContext in = instructionInput.get(i);

        String desc = PrettyPrinter.valString(i.getArrayRef(), ir) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, array, in, desc,
                                        current);

        // The only way the value gets assigned is if no NPE is thrown
        // TODO there is also an implicit "length" field in an array do we want
        // an edge from that?
        addEdge(array, result, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayLoad(SSAArrayLoadInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode array = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode index = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        PDGContext in = instructionInput.get(i);

        // Possibly throw NPE
        String desc = PrettyPrinter.valString(i.getArrayRef(), ir) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, array, in, desc,
                                        current);

        // Node and edges for the access. This extra node is created
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

        // If a NPE is NOT thrown then this may throw an
        // ArrayIndexOutOfBoundsException
        String isOOB = PrettyPrinter.valString(i.getIndex(), ir) + " >= "
                                        + PrettyPrinter.valString(i.getArrayRef(), ir) + ".length";
        normal = handlePossibleException(TypeReference.JavaLangArrayIndexOutOfBoundsException, arrayAccess, normal,
                                        isOOB, current);

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
                                        current);

        // Node and edges for the access. This extra node is created
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
                                        isOOB, current);

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
                                        current);

        // If there are no exceptions then the assignment occurs. We create a
        // result node (rather than an edge directly into the abstract location
        // node) because that allows us to associate this particular store
        // operation with a program point (and PC node)
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
        PDGContext normal = handlePossibleException(TypeReference.JavaLangArithmeticException, v1, in, desc, current);

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
        PDGContext normal = handlePossibleException(TypeReference.JavaLangClassCastException, value, in, desc, current);

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

        // Get the PC nodes for each branch and add dependencies on the previous
        // program point (PC node) and the condition that branches

        ISSABasicBlock trueSucc = getTrueSuccessor(current, cfg);
        PDGNode truePC = outputFacts.get(current).get(trueSucc).getPCNode();
        addEdge(in.getPCNode(), truePC, PDGEdgeType.CONJUNCTION);
        addEdge(condNode, truePC, PDGEdgeType.TRUE);

        ISSABasicBlock falseSucc = getFalseSuccessor(current, cfg);
        PDGNode falsePC = outputFacts.get(current).get(falseSucc).getPCNode();
        addEdge(in.getPCNode(), falsePC, PDGEdgeType.CONJUNCTION);
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
                                        current);

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
        // Labels for the entry and exit to this particular call these are used
        // to associate call sites with the associated exit (normal or
        // exceptional) sites
        CallSiteEdgeLabel entry = new CallSiteEdgeLabel(i.getCallSite(), currentNode, SiteType.ENTRY);
        CallSiteEdgeLabel exit = new CallSiteEdgeLabel(i.getCallSite(), currentNode, SiteType.EXIT);

        List<PDGNode> params = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            params.add(PDGNodeFactory.findOrCreateUse(i, j, currentNode));
        }

        PDGContext in = instructionInput.get(i);

        PDGContext normal = in;
        if (!i.isStatic()) {
            // Could throw NPE
            String desc = PrettyPrinter.valString(i.getReceiver(), ir) + " == null";
            normal = handlePossibleException(TypeReference.JavaLangNullPointerException, params.get(0), in, desc,
                                            current);

            // TODO if no NPE throw WrongMethodTypeException
        }

        // Add dependencies from actuals to nodes representing the assignment to
        // formals right before the call
        List<PDGNode> formalAssignments = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            String s = "formal(" + j + ") = " + PrettyPrinter.valString(i.getUse(j), ir);
            PDGNode formalAssign = PDGNodeFactory.findOrCreateOther(s, PDGNodeType.FORMAL_ASSIGNMENT, currentNode,
                                            new OrderedPair<>(i, j));
            formalAssignments.add(formalAssign);
            addEdge(params.get(j), formalAssign, PDGEdgeType.COPY);
            addEdge(normal.getPCNode(), formalAssign, PDGEdgeType.IMPLICIT);
        }

        // Collect nodes and add edges for each possible callee
        Set<PDGNode> calleeNormalReturns = new LinkedHashSet<>();
        Set<PDGNode> calleeExceptionValues = new LinkedHashSet<>();
        Set<PDGNode> calleeNormalPCs = new LinkedHashSet<>();
        Set<PDGNode> calleeExceptionalPCs = new LinkedHashSet<>();
        for (CGNode callee : interProc.getCallGraph().getPossibleTargets(currentNode, i.getCallSite())) {
            ProcedureSummaryNodes calleeSummary = PDGNodeFactory.findOrCreateProcedureSummary(callee);
            PDGContext calleeEntry = calleeSummary.getEntryContext();
            addEdge(normal.getPCNode(), calleeEntry.getPCNode(), PDGEdgeType.MERGE, entry);

            // Add dependency from nodes representing the assignment to
            // formals right before the call to the formals themselves
            for (int j = 0; j < i.getNumberOfParameters(); j++) {
                PDGNode formal = calleeSummary.getFormal(j);
                addEdge(formalAssignments.get(j), formal, PDGEdgeType.MERGE, entry);
            }

            if (interProc.canProcedureTerminateNormally(callee)) {
                PDGContext calleeNormal = calleeSummary.getNormalExitContext();
                calleeNormalReturns.add(calleeNormal.getReturnNode());
                calleeNormalPCs.add(calleeNormal.getPCNode());
            }

            if (interProc.getPreciseExceptionResults().canProcedureThrowAnyException(callee)) {
                PDGContext calleeEx = calleeSummary.getExceptionalExitContext();
                calleeExceptionValues.add(calleeEx.getExceptionNode());
                calleeExceptionalPCs.add(calleeEx.getPCNode());
            }
        }

        if (!calleeNormalPCs.isEmpty()) {
            // at least one callee can terminate normally

            OrderedPair<ISSABasicBlock, ExitType> normalKey = new OrderedPair<>(current, ExitType.NORMAL);
            PDGNode calleeNormPC = mergeIfNecessary(calleeNormalPCs, "CALLEE NORMAL PC MERGE", PDGNodeType.PC_MERGE,
                                            normalKey);

            // Join the caller PC before the call to the PC after the call, this
            // captures the fact that we can only exit the procedure if we call
            // it first

            ISSABasicBlock normalSucc = getNormalSuccs(current, cfg).iterator().next();
            PDGNode normalExitPC = outputFacts.get(current).get(normalSucc).getPCNode();
            addEdge(calleeNormPC, normalExitPC, PDGEdgeType.CONJUNCTION, exit);
            addEdge(normal.getPCNode(), normalExitPC, PDGEdgeType.CONJUNCTION);

            if (i.getNumberOfReturnValues() > 0) {
                PDGNode calleeReturn = mergeIfNecessary(calleeNormalReturns, "CALLEE RETURN MERGE",
                                                PDGNodeType.OTHER_EXPRESSION, normalKey);
                PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
                addEdge(calleeReturn, result, PDGEdgeType.COPY, exit);
                addEdge(normalExitPC, result, PDGEdgeType.IMPLICIT);
            }
        }

        if (!calleeExceptionalPCs.isEmpty()) {
            // some callee may throw an exception

            OrderedPair<ISSABasicBlock, ExitType> exKey = new OrderedPair<>(current, ExitType.EXCEPTIONAL);
            PDGNode calleeException = mergeIfNecessary(calleeExceptionValues, "CALLEE EXCEPTION MERGE",
                                            PDGNodeType.EXCEPTION_MERGE, exKey);
            PDGNode calleeExPC = mergeIfNecessary(calleeExceptionalPCs, "CALLEE EXCEPTION PC MERGE",
                                            PDGNodeType.PC_MERGE, exKey);

            // Join the caller PC before the call to the PC after the exception,
            // this captures the fact that we can only throw an exception if we
            // first call the procedure

            PDGContext exExitContext = calleeExceptionContexts.get(i.getCallSite());
            addEdge(calleeExPC, exExitContext.getPCNode(), PDGEdgeType.CONJUNCTION, exit);
            addEdge(normal.getPCNode(), exExitContext.getPCNode(), PDGEdgeType.CONJUNCTION);

            addEdge(calleeException, exExitContext.getExceptionNode(), PDGEdgeType.COPY, exit);
            addEdge(exExitContext.getPCNode(), exExitContext.getExceptionNode(), PDGEdgeType.IMPLICIT);
        }

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
        handlePossibleException(TypeReference.JavaLangNullPointerException, ref, in, desc, current);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowNewArray(SSANewInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        Set<PDGNode> allSizes = new LinkedHashSet<>();
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            PDGNode size = PDGNodeFactory.findOrCreateUse(i, j, currentNode);
            allSizes.add(size);
            addEdge(size, result, PDGEdgeType.EXP);
        }

        PDGNode sizeMerge = mergeIfNecessary(allSizes, "INDICES", PDGNodeType.OTHER_EXPRESSION, new OrderedPair<>(i,
                                        "INDEX MERGE"));

        PDGContext in = instructionInput.get(i);

        PDGContext normal = handlePossibleException(TypeReference.JavaLangNegativeArraySizeException, sizeMerge, in,
                                        "size < 0", current);

        // The only way the value gets assigned is if no exception is thrown
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowNewObject(SSANewInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode allocNode = PDGNodeFactory.findOrCreateOther(PrettyPrinter.rightSideString(i, ir),
                                        PDGNodeType.BASE_VALUE, currentNode, i);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        PDGContext in = instructionInput.get(i);

        addEdge(allocNode, result, PDGEdgeType.COPY);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowPutField(SSAPutInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode receiver = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);

        PDGContext in = instructionInput.get(i);

        // Possibly throw NPE
        String desc = PrettyPrinter.valString(i.getRef(), ir) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, receiver, in, desc,
                                        current);

        // If there are no exceptions then the assignment occurs. A new node is
        // created to record the program point via a PC edge.
        String resultDesc = PrettyPrinter.instructionString(i, ir);
        PDGNode result = PDGNodeFactory.findOrCreateOther(resultDesc, PDGNodeType.OTHER_EXPRESSION, currentNode,
                                        new OrderedPair<>(i, "RESULT"));
        addEdge(value, result, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        // Add edge from the assignment to the field
        Set<PDGNode> locs = new LinkedHashSet<>();
        for (AbstractLocation loc : interProc.getLocationsForNonStaticField(i.getRef(), i.getDeclaredField(),
                                        currentNode)) {
            locs.add(PDGNodeFactory.findOrCreateAbstractLocation(loc));
        }
        mergeIfNecessary(locs, "ABS LOC MERGE", PDGNodeType.LOCATION_SUMMARY, i);

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

        PDGNode switchGuard = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode newPC = outputFacts.get(current).get(getNormalSuccs(current, cfg).iterator().next()).getPCNode();

        // record that the switch PC depends on the guard and the input PC
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
        return interProc.isUnreachable(source, target, currentNode);
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
     *            context (including the PC node) immediately before the
     *            exception is thrown
     * @param branchDescription
     *            description of the condition that causes the exception. The
     *            condition being true should result in the exception. (e.g. o
     *            == null for an NPE, index > a.length for an
     *            ArrayIndexOutOfBoundsException).
     * @param bb
     *            basic block that could throw the exception
     * 
     * @return PDGContext when the exception is not thrown
     */
    private PDGContext handlePossibleException(TypeReference exType, PDGNode cause, PDGContext beforeException,
                                    String branchDescription, ISSABasicBlock bb) {
        if (interProc.getPreciseExceptionResults().canThrowException(exType, bb, currentNode)) {
            PDGNode branch = PDGNodeFactory.findOrCreateOther(branchDescription, PDGNodeType.OTHER_EXPRESSION,
                                            currentNode, new OrderedPair<>(bb, exType));
            addEdge(cause, branch, PDGEdgeType.EXP);

            PDGNode truePC = trueExceptionContexts.get(bb).get(exType).getPCNode();
            addEdge(branch, truePC, PDGEdgeType.TRUE);
            addEdge(beforeException.getPCNode(), truePC, PDGEdgeType.CONJUNCTION);

            PDGContext falseContext = falseExceptionContexts.get(bb).get(exType);
            addEdge(branch, falseContext.getPCNode(), PDGEdgeType.FALSE);
            addEdge(beforeException.getPCNode(), falseContext.getPCNode(), PDGEdgeType.CONJUNCTION);

            // Return normal context
            return falseContext;
        } else {
            return beforeException;
        }
    }
}
