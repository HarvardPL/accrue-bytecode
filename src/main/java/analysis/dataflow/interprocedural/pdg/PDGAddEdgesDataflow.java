package analysis.dataflow.interprocedural.pdg;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import signatures.Signatures;
import types.TypeRepository;
import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.pdg.graph.CallSiteEdgeLabel;
import analysis.dataflow.interprocedural.pdg.graph.CallSiteEdgeLabel.SiteType;
import analysis.dataflow.interprocedural.pdg.graph.PDGEdgeType;
import analysis.dataflow.interprocedural.pdg.graph.node.AbstractLocationPDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeFactory;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeType;
import analysis.dataflow.interprocedural.pdg.graph.node.ProcedureSummaryPDGNodes;
import analysis.dataflow.util.AbstractLocation;
import analysis.dataflow.util.Unit;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.shrikeBT.IConditionalBranchInstruction.Operator;
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
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * Part of an inter-procedural data-flow, this intra-procedural data-flow adds edges to a program dependence graph
 * (PDG). The nodes at basic block and instruction boundaries and for the program points just after possible exceptions
 * are based on the results of another analysis (e.g. {@link PDGComputeNodesDataflow}.
 */
public class PDGAddEdgesDataflow extends InstructionDispatchDataFlow<Unit> {

    /**
     * Call graph node this data-flow is over
     */
    private final CGNode currentNode;
    /**
     * Inter-procedural analysis managing the queue of call graph nodes and holding data structures that persist over
     * the analysis of a multiple call graph nodes
     */
    private final PDGInterproceduralDataFlow interProc;
    /**
     * Code we are analyzing
     */
    private final IR ir;
    /**
     * Pretty printer for this method
     */
    private final PrettyPrinter pp;
    /**
     * Type inference results
     */
    private final TypeRepository types;
    /**
     * Map from basic block to the computed data-flow facts (PCNode, nodes for exceptions and return values) for each
     * exit edge
     */
    private final Map<ISSABasicBlock, Map<ISSABasicBlock, PDGContext>> outputFacts;
    /**
     * Input PC, return, and exception nodes for each instruction (after merging if there are multiple incoming edges)
     */
    private final Map<SSAInstruction, PDGContext> instructionInput;
    /**
     * Map from basic block to context holding the PC node and exception node for the program point just after an
     * exception (of a particular type) is thrown in that basic block
     */
    private final Map<ISSABasicBlock, Map<TypeReference, PDGContext>> trueExceptionContexts;
    /**
     * Map from basic block to context holding the PC node for the program point just after an exception (of a
     * particular type) that could be thrown is NOT thrown
     */
    private final Map<ISSABasicBlock, Map<TypeReference, PDGContext>> falseExceptionContexts;
    /**
     * Map from a merge node to the nodes that were merged when creating that node
     */
    private final Map<PDGNode, Set<PDGNode>> mergeNodes;
    /**
     * Context created for the program point just after a callee throws an exception
     */
    private final Map<SSAInvokeInstruction, PDGContext> calleeExceptionContexts;

    /**
     * Create a data-flow that adds edge to a PDG for the given call graph node.
     *
     * @param currentNode node this analysis is over
     * @param pp Pretty printer for the method contained in the CG node
     * @param types type inference for local variables in the method contained in the CG node
     * @param interProc Inter-procedural analysis managing the queue of call graph nodes and holding data structures
     *            that persist over the analysis of a multiple call graph nodes
     * @param mergeNodes Map from a merge node to the nodes that were merged when creating that node
     * @param trueExceptionContexts Map from basic block to context holding the PC node and exception node for the
     *            program point just after an exception (of a particular type) is thrown in that basic block
     * @param falseExceptionContexts Map from basic block to context holding the PC node for the program point just
     *            after an exception (of a particular type) that could be thrown is NOT thrown
     * @param calleeExceptionContexts Context created for the program point just after a callee throws an exception
     * @param outputFacts Map from basic block to the computed data-flow facts (PCNode, nodes for exceptions and return
     *            values) for each exit edge
     * @param instructionInput Map from basic block to context holding the PC node and exception node for the program
     *            point just after an exception (of a particular type) is thrown in that basic block
     */
    public PDGAddEdgesDataflow(CGNode currentNode, PrettyPrinter pp, TypeRepository types,
                               PDGInterproceduralDataFlow interProc, Map<PDGNode, Set<PDGNode>> mergeNodes,
                               Map<ISSABasicBlock, Map<TypeReference, PDGContext>> trueExceptionContexts,
                               Map<ISSABasicBlock, Map<TypeReference, PDGContext>> falseExceptionContexts,
                               Map<SSAInvokeInstruction, PDGContext> calleeExceptionContexts,
                               Map<ISSABasicBlock, Map<ISSABasicBlock, PDGContext>> outputFacts,
                               Map<SSAInstruction, PDGContext> instructionInput) {
        super(true);
        this.currentNode = currentNode;
        this.interProc = interProc;
        this.ir = currentNode.getIR();
        this.pp = pp;
        this.types = types;
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
        ProcedureSummaryPDGNodes summary = PDGNodeFactory.findOrCreateProcedureSummary(currentNode);

        // Add edges from the summary nodes to the nodes for the local variables
        for (int i = 0; i < ir.getNumberOfParameters(); i++) {
            PDGNode param = PDGNodeFactory.findOrCreateLocal(ir.getParameter(i), currentNode, pp, types);
            addEdge(summary.getFormal(i), param, PDGEdgeType.COPY);
        }

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
    protected Map<ISSABasicBlock, Unit> flowInstruction(SSAInstruction i, Set<Unit> inItems,
                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                        ISSABasicBlock current) {
        return super.flowInstruction(i, inItems, cfg, current);
    }

    @Override
    protected Unit flowBinaryOp(SSABinaryOpInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode v0 = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
        PDGNode v1 = PDGNodeFactory.findOrCreateUse(i, 1, currentNode, pp, types);
        PDGNode assignee = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        addEdge(v0, assignee, PDGEdgeType.EXP);
        addEdge(v1, assignee, PDGEdgeType.EXP);
        addEdge(in.getPCNode(), assignee, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowComparison(SSAComparisonInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode v0 = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
        PDGNode v1 = PDGNodeFactory.findOrCreateUse(i, 1, currentNode, pp, types);
        PDGNode assignee = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        addEdge(v0, assignee, PDGEdgeType.EXP);
        addEdge(v1, assignee, PDGEdgeType.EXP);
        addEdge(in.getPCNode(), assignee, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowConversion(SSAConversionInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode converted = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        addEdge(converted, result, PDGEdgeType.EXP);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode caughtEx = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);

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
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        addEdge(fieldNode, result, PDGEdgeType.COPY);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowInstanceOf(SSAInstanceofInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);
        PDGNode refNode = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        addEdge(refNode, result, PDGEdgeType.EXP);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    /**
     * Can it be statically determined that an instanceof check always passes or always fails, and thus does not depend
     * on the dynamic input.
     *
     * @param i instruction to check
     * @return true if we can determine statically that the instanceof check always returns true or false
     */
    @SuppressWarnings("unused")
    private boolean isInstanceOfConstant(SSAInstanceofInstruction i) {
        boolean instanceOfAlwaysFalse = true;
        boolean instanceOfAlwaysTrue = true;

        // Check if everything the cast object could point to is safe to cast (or unsafe)
        IClass checked = AnalysisUtil.getClassHierarchy().lookupClass(i.getCheckedType());
        Iterator<InstanceKey> iter = interProc.getPointsToGraph().pointsToIterator(interProc.getReplica(i.getRef(),
                                                                                                        currentNode),
                                                                                   null);
        while (iter.hasNext()) {
            InstanceKey hContext = iter.next();

            IClass actual = hContext.getConcreteType();
            if (AnalysisUtil.getClassHierarchy().isAssignableFrom(checked, actual)) {
                instanceOfAlwaysFalse = false;
            }
            else {
                instanceOfAlwaysTrue = false;
            }

            if (!instanceOfAlwaysFalse && !instanceOfAlwaysTrue) {
                break;
            }
        }

        return instanceOfAlwaysFalse || instanceOfAlwaysTrue;
    }

    @Override
    protected Unit flowPhi(SSAPhiInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Need to create an "assignment" into each argument to record the PC, this may have already happened in a
        // branch, but often assignments in different branches will be translated away into a single phi statement.
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);

        Set<PDGNode> temporaryAssignments = new LinkedHashSet<>();

        Iterator<ISSABasicBlock> preds = cfg.getPredNodes(current);
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            ISSABasicBlock pred = preds.next();
            Map<ISSABasicBlock, PDGContext> predMap = outputFacts.get(pred);
            if (predMap == null) {
                assert isUnreachable(pred, current) : "No output at all for pred BB" + pred.getNumber() + " in "
                                                + PrettyPrinter.cgNodeString(currentNode);
                continue;
            }

            PDGContext predContext = predMap.get(current);
            if (predContext == null) {
                assert isUnreachable(pred, current) : "No output from pred BB" + pred.getNumber() + " to BB"
                                                + current.getNumber() + " in "
                                                + PrettyPrinter.cgNodeString(currentNode);
                continue;
            }

            PDGNode v_j = PDGNodeFactory.findOrCreateUse(i, j, currentNode, pp, types);
            PDGNode assignment_j = PDGNodeFactory.findOrCreateOther("temp = " + v_j, PDGNodeType.OTHER_EXPRESSION,
                                                                    currentNode,
                                                                    v_j.getJavaType(),
                                                                    new OrderedPair<>(i, j));
            temporaryAssignments.add(assignment_j);
            addEdge(v_j, assignment_j, PDGEdgeType.COPY);
            addEdge(predContext.getPCNode(), assignment_j, PDGEdgeType.IMPLICIT);
        }

        PDGContext in = instructionInput.get(i);

        // Node representing the phi before the assignment
        PDGNode phi = PDGNodeFactory.findOrCreateOther(pp.rightSideString(i),
                                                       PDGNodeType.OTHER_EXPRESSION,
                                                       currentNode,
                                                       result.getJavaType(),
                                                       new OrderedPair<>("PHI_MERGE", result));

        addEdgesForMerge(temporaryAssignments, phi);
        addEdge(phi, result, PDGEdgeType.COPY);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowPutStatic(SSAPutInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        AbstractLocation fieldLoc = AbstractLocation.createStatic(i.getDeclaredField());
        AbstractLocationPDGNode fieldNode = PDGNodeFactory.findOrCreateAbstractLocation(fieldLoc);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
        // Create intermediate node for this assignment
        // This is done so we can associate the PC node for the program point
        // this assignment happens at with the value being assigned
        String desc = pp.instructionString(i);
        PDGNode assignment = PDGNodeFactory.findOrCreateOther(desc,
                                                              PDGNodeType.OTHER_EXPRESSION,
                                                              currentNode,
                                                              i.getDeclaredFieldType(),
                                                              i);

        PDGContext in = instructionInput.get(i);

        addEdge(value, assignment, PDGEdgeType.COPY);
        addEdge(assignment, fieldNode, PDGEdgeType.MERGE);
        addEdge(in.getPCNode(), assignment, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Unit flowUnaryNegation(SSAUnaryOpInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        addEdge(value, result, PDGEdgeType.EXP);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return Unit.VALUE;
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayLength(SSAArrayLengthInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode array = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        String desc = pp.valString(i.getArrayRef()) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, array, in, desc,
                                        current);

        // The only way the value gets assigned is if no NPE is thrown
        // XXX there is also an implicit "length" field in an array do we want
        // an edge from that? Should that live in the heap?
        addEdge(array, result, PDGEdgeType.POINTER);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayLoad(SSAArrayLoadInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode array = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
        PDGNode index = PDGNodeFactory.findOrCreateUse(i, 1, currentNode, pp, types);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        // Possibly throw NPE
        String desc = pp.valString(i.getArrayRef()) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, array, in, desc,
                                        current);

        // Node and edges for the access. This extra node is created
        // because it is the access itself that can cause an
        // ArrayIndexOutOfBoundsException.
        String d = pp.valString(i.getArrayRef()) + "[" + pp.valString(i.getIndex()) + "]";
        assert array.getJavaType().isArrayType();
        PDGNode arrayAccess = PDGNodeFactory.findOrCreateOther(d,
                                                               PDGNodeType.OTHER_EXPRESSION,
                                                               currentNode,
                                                               array.getJavaType().getArrayElementType(),
                                                               i);
        addEdge(array, arrayAccess, PDGEdgeType.EXP);
        addEdge(index, arrayAccess, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), arrayAccess, PDGEdgeType.IMPLICIT);

        // If a NPE is NOT thrown then this may throw an
        // ArrayIndexOutOfBoundsException
        String isOOB = pp.valString(i.getIndex()) + " >= " + pp.valString(i.getArrayRef()) + ".length";
        normal = handlePossibleException(TypeReference.JavaLangArrayIndexOutOfBoundsException, arrayAccess, normal,
                                        isOOB, current);

        // The only way the value gets assigned is if no exception is thrown
        addEdge(arrayAccess, result, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        // Add edges from the array contents to the result
        Set<PDGNode> locNodes = new LinkedHashSet<>();
        for (AbstractLocation loc : interProc.getLocationsForArrayContents(i.getArrayRef(), currentNode)) {
            locNodes.add(PDGNodeFactory.findOrCreateAbstractLocation(loc));
        }
        PDGNode locMerge = mergeIfNecessary(locNodes,
                                            "ABS LOC MERGE",
                                            PDGNodeType.LOCATION_SUMMARY,
                                            result.getJavaType(),
                                            i);
        addEdge(locMerge, result, PDGEdgeType.EXP);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayStore(SSAArrayStoreInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode array = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
        PDGNode index = PDGNodeFactory.findOrCreateUse(i, 1, currentNode, pp, types);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 2, currentNode, pp, types);

        // Possibly throw NPE
        PDGContext in = instructionInput.get(i);
        String desc = pp.valString(i.getArrayRef()) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, array, in, desc,
                                        current);

        // Node and edges for the access. This extra node is created
        // because it is the access itself that can cause an
        // ArrayIndexOutOfBoundsException.
        String d = pp.valString(i.getArrayRef()) + "[" + pp.valString(i.getIndex()) + "]";
        assert array.getJavaType().isArrayType();
        PDGNode arrayAccess = PDGNodeFactory.findOrCreateOther(d,
                                                               PDGNodeType.OTHER_EXPRESSION,
                                                               currentNode,
                                                               array.getJavaType().getArrayElementType(),
                                                               new OrderedPair<>(i, "ARRAY_ACCESS"));
        addEdge(array, arrayAccess, PDGEdgeType.EXP);
        addEdge(index, arrayAccess, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), arrayAccess, PDGEdgeType.IMPLICIT);

        // If no NPE is thrown then this may throw an
        // ArrayIndexOutOfBoundsException
        String isOOB = pp.valString(i.getIndex()) + " >= " + pp.valString(i.getArrayRef()) + ".length";
        normal = handlePossibleException(TypeReference.JavaLangArrayIndexOutOfBoundsException, arrayAccess, normal,
                                        isOOB, current);

        // Node and edges for the assignment. This extra node is created
        // because it is the assignment that can cause an
        // ArrayStoreException.
        String storeDesc = "STORE " + pp.instructionString(i);
        PDGNode store = PDGNodeFactory.findOrCreateOther(storeDesc,
                                                         PDGNodeType.OTHER_EXPRESSION,
                                                         currentNode,
                                                         array.getJavaType().getArrayElementType(),
                                                         new OrderedPair<>(i, "STORE"));
        addEdge(arrayAccess, store, PDGEdgeType.EXP);
        addEdge(value, store, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), store, PDGEdgeType.IMPLICIT);

        // If no ArrayIndexOutOfBoundsException is thrown then this may throw an
        // ArrayStoreException
        String arrayStoreDesc = "!" + pp.valString(i.getValue()) + " instanceof " + pp.valString(i.getArrayRef())
                                        + ".elementType";
        normal = handlePossibleException(TypeReference.JavaLangArrayStoreException, store, normal, arrayStoreDesc,
                                        current);

        // If there are no exceptions then the assignment occurs. We create a
        // result node (rather than an edge directly into the abstract location
        // node) because that allows us to associate this particular store
        // operation with a program point (and PC node)
        String resultDesc = pp.instructionString(i);
        PDGNode result = PDGNodeFactory.findOrCreateOther(resultDesc,
                                                          PDGNodeType.OTHER_EXPRESSION,
                                                          currentNode,
                                                          array.getJavaType().getArrayElementType(),
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

        PDGNode v0 = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
        PDGNode v1 = PDGNodeFactory.findOrCreateUse(i, 1, currentNode, pp, types);
        PDGNode assignee = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        String desc = pp.valString(i.getUse(1)) + " == 0";
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
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);

        // Possibly throw ClassCastException
        PDGContext in = instructionInput.get(i);
        String desc = "!" + pp.valString(i.getVal()) + " instanceof "
                                        + PrettyPrinter.typeString(i.getDeclaredResultTypes()[0]);
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
        String cond = pp.valString(i.getUse(0)) + " " + PrettyPrinter.conditionalOperatorString(i.getOperator()) + " "
                                        + pp.valString(i.getUse(1));
        PDGNode val0 = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
        PDGNode val1 = PDGNodeFactory.findOrCreateUse(i, 1, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        ISSABasicBlock trueSucc = getTrueSuccessor(current, cfg);
        PDGNode truePC = outputFacts.get(current).get(trueSucc).getPCNode();
        ISSABasicBlock falseSucc = getFalseSuccessor(current, cfg);
        PDGNode falsePC = outputFacts.get(current).get(falseSucc).getPCNode();

        // The old PC is joined with the conditional expression for the new PC
        addEdge(in.getPCNode(), truePC, PDGEdgeType.CONJUNCTION);
        addEdge(in.getPCNode(), falsePC, PDGEdgeType.CONJUNCTION);

        if (ir.getSymbolTable().isZeroOrFalse(i.getUse(1)) && i.getOperator().equals(Operator.EQ)) {
            // This is an equality comparison with the constant zero (i.e. false)
            // flip the branches and set the first argument to the branching node
            // This is a special case because this is how a branch on a unary boolean expression is translated
            addEdge(val0, truePC, PDGEdgeType.FALSE);
            addEdge(val0, falsePC, PDGEdgeType.TRUE);
        }
        else if (ir.getSymbolTable().isZeroOrFalse(i.getUse(1)) && i.getOperator().equals(Operator.NE)) {
            // This is an inequality comparison with the constant zero (i.e. false)
            // This is a special case because this is how a branch on a unary-negation boolean expression is translated
            addEdge(val0, truePC, PDGEdgeType.TRUE);
            addEdge(val0, falsePC, PDGEdgeType.FALSE);
        }
        else {
            // Create a node for the conditional itself
            PDGNode condNode = PDGNodeFactory.findOrCreateOther(cond,
                                                                PDGNodeType.OTHER_EXPRESSION,
                                                                currentNode,
                                                                TypeReference.Boolean,
                                                                i);
            addEdge(val0, condNode, PDGEdgeType.EXP);
            addEdge(val1, condNode, PDGEdgeType.EXP);

            // Get the PC nodes for each branch and add dependencies on the previous
            // program point (PC node) and the condition that branches

            addEdge(condNode, truePC, PDGEdgeType.TRUE);
            addEdge(condNode, falsePC, PDGEdgeType.FALSE);
        }

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowGetField(SSAGetInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {

        PDGNode target = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        String desc = pp.valString(i.getRef()) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, target, in, desc,
                                        current);

        // Add edges from the field to the result
        Set<PDGNode> locNodes = new LinkedHashSet<>();
        for (AbstractLocation loc : interProc.getLocationsForNonStaticField(i.getRef(), i.getDeclaredField(),
                                        currentNode)) {
            locNodes.add(PDGNodeFactory.findOrCreateAbstractLocation(loc));
        }

        PDGNode locMerge = mergeIfNecessary(locNodes,
                                            "ABS LOC MERGE",
                                            PDGNodeType.LOCATION_SUMMARY,
                                            result.getJavaType(),
                                            i);
        addEdge(locMerge, result, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);
        addEdge(target, result, PDGEdgeType.POINTER);

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
        return flowInvoke(i, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeVirtual(SSAInvokeInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvoke(i, cfg, current);
    }

    private Map<ISSABasicBlock, Unit> flowInvoke(SSAInvokeInstruction i,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGContext in = instructionInput.get(i);
        PDGContext normal = in;

        PDGNode normalExitPC;
        ISSABasicBlock normalSucc = getNormalSuccs(current, cfg).iterator().next();
        if (outputFacts.get(current).get(normalSucc) == null) {
            // There was no fact for the normal successor, the only way this could happen is if all possible callees cannot terminate normally.
            for (CGNode callee : interProc.getCallGraph().getPossibleTargets(currentNode, i.getCallSite())) {
                assert !canProcedureTerminateNormally(callee);
            }
            normalExitPC = null;
        }
        else {
            normalExitPC = outputFacts.get(current).get(normalSucc).getPCNode();
        }

        List<PDGNode> params = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            params.add(PDGNodeFactory.findOrCreateUse(i, j, currentNode, pp, types));
        }

        if (!i.isStatic() && !interProc.getNonNullResults().isNonNull(i.getReceiver(), i, currentNode, null)) {
            // Could throw NPE due to null receiver
            String desc = pp.valString(i.getReceiver()) + " == null";
            normal = handlePossibleException(TypeReference.JavaLangNullPointerException,
                                             params.get(0),
                                             in,
                                             desc,
                                             current);

            // TODO if no NPE throw WrongMethodTypeException
        }

        if (Signatures.isImmutableWrapperCall(i) || Signatures.isArraycopy(i.getDeclaredTarget())) {
            assert normalExitPC != null : "Callees for " + i + " cannot terminate normally.";
            // We handle calls to methods on immutable wrappers (String, Integer, etc.) specially.
            // The Objects are handled as if they were a primitive.

            // Similarly there is a special signature for arraycopy
            addEdge(normal.getPCNode(), normalExitPC, PDGEdgeType.CONJUNCTION);

            addEdgesForInlineSignature(i, normal.getPCNode());
            return factToMap(Unit.VALUE, current, cfg);
        }

        // Labels for the entry and exit to this particular call these are used
        // to associate call sites with the associated exit (normal or
        // exceptional) sites

        // If the method is declared on a subtype of java.util.Map then record the receiver objects
        Map<CGNode, Set<InstanceKey>> receivers = null;
        IClass recType = AnalysisUtil.getClassHierarchy().lookupClass(i.getDeclaredTarget().getDeclaringClass());
        IClass mapType = AnalysisUtil.getClassHierarchy().lookupClass(TypeReference.JavaUtilMap);
        if (!i.isStatic() && AnalysisUtil.getClassHierarchy().isAssignableFrom(mapType, recType)) {
            receivers = new HashMap<>();
            Iterator<InstanceKey> iter = interProc.getPointsToGraph()
                                                  .pointsToIterator(interProc.getReplica(i.getReceiver(), currentNode));
            HeapAbstractionFactory haf = interProc.getPointsToGraph().getHaf();
            while (iter.hasNext()) {
                CallSiteLabel l = new CallSiteLabel(currentNode.getMethod(), i.getCallSite());
                InstanceKey ik = iter.next();
                Context calleeContext = haf.merge(l, ik, currentNode.getContext());
                IMethod callee;
                if (i.isSpecial()) {
                    callee = AnalysisUtil.getClassHierarchy().resolveMethod(i.getDeclaredTarget());
                }
                else {
                    // Virtual call
                    callee = AnalysisUtil.getClassHierarchy().resolveMethod(ik.getConcreteType(),
                                                                            i.getDeclaredTarget().getSelector());
                }
                CGNode calleeNode = interProc.getCallGraph().getNode(callee, calleeContext);
                Set<InstanceKey> rec = receivers.get(calleeNode);
                if (rec == null) {
                    rec = new HashSet<>();
                    receivers.put(calleeNode, rec);
                }
                rec.add(ik);
            }
        }
        CallSiteEdgeLabel entry = new CallSiteEdgeLabel(i, currentNode, SiteType.ENTRY);
        CallSiteEdgeLabel exit = new CallSiteEdgeLabel(i, currentNode, SiteType.EXIT);

        // The PC at the call site is the PC if no exception was thrown
        PDGNode callSitePC = normal.getPCNode();

        // Add dependencies from actuals to nodes representing the assignment to
        // formals right before the call
        List<PDGNode> formalAssignments = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            String s = "formal-" + j + " = " + pp.valString(i.getUse(j)) + " for "
                                            + PrettyPrinter.methodString(i.getDeclaredTarget());
            PDGNode formalAssign = PDGNodeFactory.findOrCreateOther(s,
                                                                    PDGNodeType.FORMAL_ASSIGNMENT,
                                                                    currentNode,
                                                                    params.get(j).getJavaType(),
                                                                    new OrderedPair<>(i, j));
            formalAssignments.add(formalAssign);
            addEdge(params.get(j), formalAssign, PDGEdgeType.COPY);
            addEdge(callSitePC, formalAssign, PDGEdgeType.IMPLICIT);
        }

        // Collect nodes and add edges for each possible callee
        Set<OrderedPair<CGNode, PDGContext>> normalExits = new LinkedHashSet<>();
        Set<OrderedPair<CGNode, PDGContext>> exceptionExits = new LinkedHashSet<>();
        for (CGNode callee : interProc.getCallGraph().getPossibleTargets(currentNode, i.getCallSite())) {
            if (receivers != null) {
                // This is one of the types for which we are tracking receivers add them to the edge label.
                assert receivers.get(callee) != null : "null receivers for " + callee;
                assert !receivers.get(callee).isEmpty() : "no receivers for " + callee;
                exit = exit.getLabelForReceivers(receivers.get(callee));
                entry = entry.getLabelForReceivers(receivers.get(callee));
            }
            ProcedureSummaryPDGNodes calleeSummary = PDGNodeFactory.findOrCreateProcedureSummary(callee);
            PDGContext calleeEntry = calleeSummary.getEntryContext();
            addEdge(callSitePC, calleeEntry.getPCNode(), PDGEdgeType.MERGE, entry);

            // Add dependency from nodes representing the assignment to
            // formals right before the call to the formals themselves
            for (int j = 0; j < i.getNumberOfParameters(); j++) {
                PDGNode formal = calleeSummary.getFormal(j);
                addEdge(formalAssignments.get(j), formal, PDGEdgeType.MERGE, entry);
            }

            if (canProcedureTerminateNormally(callee)) {
                PDGContext calleeNormal = calleeSummary.getNormalExitContext();
                normalExits.add(new OrderedPair<>(callee, calleeNormal));
            }

            if (interProc.getPreciseExceptionResults().canProcedureThrowAnyException(callee)) {
                PDGContext calleeEx = calleeSummary.getExceptionalExitContext();
                exceptionExits.add(new OrderedPair<>(callee, calleeEx));

            }
        }

        PDGNode result = null;
        if (i.getNumberOfReturnValues() > 0) {
            // Method has a return
            result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);
        }

        if (normalExitPC != null) {
            // Connect up the callee summary nodes to nodes in the caller
            handleCalleeExit(callSitePC, normalExits, exit, i, result, normalExitPC, ExitType.NORMAL);
        }

        // some callee may throw an exception
        PDGContext exExitContext = calleeExceptionContexts.get(i);
        if (exExitContext != null) {
            // Connect up the callee summary nodes to nodes in the caller
            handleCalleeExit(callSitePC,
                             exceptionExits,
                             exit,
                             i,
                             exExitContext.getExceptionNode(),
                             exExitContext.getPCNode(),
                             ExitType.EXCEPTIONAL);
        }


        return factToMap(Unit.VALUE, current, cfg);
    }

    /**
     * Add edges directly to the PDG for a method call into an immutable wrapper class (e.g. String, Integer, etc.)
     *
     * @param i invocation instruction
     * @param pcNode PC node at the call site
     */
    private void addEdgesForInlineSignature(SSAInvokeInstruction i, PDGNode pcNode) {
        MethodReference mr = i.getDeclaredTarget();
        // Target of the edges to be added
        PDGNode targetNode;
        // When looping over source nodes where to start in the "use" list
        int startIndex;
        // If the target is an array this is the contents
        Set<PDGNode> arrayContents = null;

        if (Signatures.isArraycopy(i.getDeclaredTarget())) {
            targetNode = null;
            startIndex = 0;

            // The third argument is the destination array
            arrayContents = new LinkedHashSet<>();
            for (AbstractLocation loc : interProc.getLocationsForArrayContents(i.getUse(2), currentNode)) {
                arrayContents.add(PDGNodeFactory.findOrCreateAbstractLocation(loc));
            }
        }
        else if (i.getNumberOfReturnValues() == 0 && mr.isInit()) {
            startIndex = 1;
            // Instance initializer add edges to the receiver
            targetNode = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
            // Replace the string for the local with something more descriptive
            targetNode.setDescription(targetNode.toString() + " = " + pp.instructionString(i));
        }
        else if (i.getNumberOfReturnValues() == 0) {
            // void return methods. They all copy into an array passed in as an argument

            // No target node, just edges into the contents of the array
            targetNode = null;
            startIndex = 0;

            for (int j = 0; j < i.getNumberOfUses(); j++) {
                if (j > 0 && mr.getParameterType(j - 1).isArrayType()) {
                    arrayContents = new LinkedHashSet<>();
                    for (AbstractLocation loc : interProc.getLocationsForArrayContents(i.getUse(j), currentNode)) {
                        arrayContents.add(PDGNodeFactory.findOrCreateAbstractLocation(loc));
                    }
                }
            }
            assert arrayContents != null : "One of the arguments should be an array for " + i;
        }
        else {
            // This method returns a value, add edges to the return node
            startIndex = 0;
            targetNode = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);

            // If the return is an array then record nodes for the contents and add edges later
            if (i.getDeclaredTarget().getReturnType().isArrayType()) {
                arrayContents = new LinkedHashSet<>();
                for (AbstractLocation loc : interProc.getLocationsForArrayContents(i.getReturnValue(0), currentNode)) {
                    arrayContents.add(PDGNodeFactory.findOrCreateAbstractLocation(loc));
                }
            }
        }

        // Node representing an uninterpretted expression of the arguments
        // XXX Type is weird here since we really don't know what it is
        TypeReference javaType = targetNode != null ? targetNode.getJavaType() : TypeReference.Void;
        PDGNode expr = PDGNodeFactory.findOrCreateOther(PrettyPrinter.methodString(mr) + " SIGNATURE",
                                                        PDGNodeType.OTHER_EXPRESSION,
                                                        currentNode,
                                                        javaType,
                                                        new OrderedPair<>("SIGNATURE_EXPRESSION", i));

        // Offset of parameter number and use number
        int offset;
        if (i.isStatic()) {
            offset = 0;
        }
        else {
            offset = 1;
        }

        for (int j = startIndex; j < i.getNumberOfUses(); j++) {
            addEdge(PDGNodeFactory.findOrCreateUse(i, j, currentNode, pp, types), expr, PDGEdgeType.EXP);

            // If this argument is an array add an edge from the contents as well
            if ((j - offset) >= 0 && interProc.isArray(i.getUse(j), currentNode, mr.getParameterType(j - offset))) {
                Set<PDGNode> locNodes = new LinkedHashSet<>();
                for (AbstractLocation loc : interProc.getLocationsForArrayContents(i.getUse(j), currentNode)) {
                    locNodes.add(PDGNodeFactory.findOrCreateAbstractLocation(loc));
                }
                PDGNode locMerge = mergeIfNecessary(locNodes,
                                                    "ABS LOC MERGE",
                                                    PDGNodeType.LOCATION_SUMMARY,
                                                    expr.getJavaType(),
                                                    i);
                addEdge(locMerge, expr, PDGEdgeType.EXP);
            }
        }

        if (targetNode != null) {
            // Add edges into the assignment node from the expression and the PC node
            addEdge(expr, targetNode, PDGEdgeType.COPY);
            addEdge(pcNode, targetNode, PDGEdgeType.IMPLICIT);
        }
        else {
            assert i.getNumberOfReturnValues() == 0 && !mr.isInit() : "Non-void or init method resulted in a null target";
        }

        // Add edges into the array contents if the return is an array
        if (arrayContents != null && !arrayContents.isEmpty()) {
            PDGNode contentsNode = PDGNodeFactory.findOrCreateOther(PointsToGraph.ARRAY_CONTENTS + " = SIGNATURE",
                                                                    PDGNodeType.OTHER_EXPRESSION,
                                                                    currentNode,
                                                                    arrayContents.iterator().next().getJavaType(),
                                                                    new OrderedPair<>("SIGNATURE_CONTENTS", i));
            addEdge(expr, contentsNode, PDGEdgeType.COPY);
            addEdge(pcNode, contentsNode, PDGEdgeType.IMPLICIT);
            for (PDGNode contents : arrayContents) {
                addEdge(contentsNode, contents, PDGEdgeType.MERGE);
            }
        }

        // Handle exceptions thrown by this callee
        PDGContext exExitContext = calleeExceptionContexts.get(i);
        if (exExitContext != null) {
            //            PDGNode exAssign = PDGNodeFactory.findOrCreateOther("EXCEPTION = SIGNATURE",
            //                                                                    PDGNodeType.OTHER_EXPRESSION,
            //                                                                    currentNode,
            //                                                                    new OrderedPair<>("SIGNATURE_EXCEPTION", i));
            //            PDGNode exceptionNode = exExitContext.getExceptionNode();
            // The exception doesn't depend explicitly on any of the arguments, only implicitly
            //            addEdge(expr, exAssign, PDGEdgeType.COPY);
            //            addEdge(pcNode, exAssign, PDGEdgeType.IMPLICIT);
            addEdge(pcNode, exExitContext.getPCNode(), PDGEdgeType.CONJUNCTION);
        }
    }

    /**
     * Connect the summary nodes for callees in <code>exits</code> to the result and pcResult from the caller
     *
     * @param callSitePC pc at the call site before the call
     * @param exits Possible concrete callees and the corresponding exit contexts
     * @param exitLabel label for the return site
     * @param i call instruction
     * @param result node in the caller for the value resulting from the call (either the exception or return value if
     *            any)
     * @param pcResult pc node for the program point in the caller after the call
     * @param type either exceptional or normal exit
     */
    private void handleCalleeExit(PDGNode callSitePC, Set<OrderedPair<CGNode, PDGContext>> exits,
                                  CallSiteEdgeLabel exitLabel, SSAInvokeInstruction i, PDGNode result,
                                  PDGNode pcResult, ExitType type) {
        // assert !exits.isEmpty() : "No exits for " + i + " for " + type;
        assert callSitePC.getNodeType().isPathCondition();
        assert exitLabel != null;
        assert pcResult != null;
        assert pcResult.getNodeType().isPathCondition();
        assert result != null || (type == ExitType.NORMAL && i.getNumberOfReturnValues() == 0) : "Only void return methods should have no result node";
        Set<PDGNode> pcJoins = new LinkedHashSet<>();
        Set<PDGNode> exitAssignments = new LinkedHashSet<>();
        OrderedPair<SSAInvokeInstruction, ExitType> k = new OrderedPair<>(i, type);
        for (OrderedPair<CGNode, PDGContext> p : exits) {
            OrderedPair<PDGNode, PDGNode> res = addCallerExitEdges(callSitePC, p, exitLabel, k);
            pcJoins.add(res.fst());
            if (result != null) {
                exitAssignments.add(res.snd());
            }
        }
        PDGNode pcMerge;
        if (pcJoins.isEmpty()) {
            // Just copy the callSite PC to the result if there are no calls to process
            // XXX add edges from arguments in case the receiver is "missing" instead of there actually being no receivers
            String methodName = PrettyPrinter.methodString(i.getDeclaredTarget());
            String desc = "SYNTHETIC " + type + "_EXIT_PC after " + methodName;
            pcMerge = PDGNodeFactory.findOrCreateOther(desc, PDGNodeType.EXIT_PC_JOIN, currentNode, null, k);
            addEdge(callSitePC, pcMerge, PDGEdgeType.CONJUNCTION);
        }
        else {
            pcMerge = mergeIfNecessary(pcJoins, type + "_PC_MERGE", PDGNodeType.PC_MERGE, null, k);
        }
        addEdge(pcMerge, pcResult, PDGEdgeType.CONJUNCTION);

        if (result != null) {
            PDGNode retMerge;
            if (exitAssignments.isEmpty()) {
                // Also make sure that the exit assignment gets implicitly guarded appropriately
                String methodName = PrettyPrinter.methodString(i.getDeclaredTarget());
                String s = "SYNTHETIC " + type + "_RET after " + methodName;
                retMerge = PDGNodeFactory.findOrCreateOther(s,
                                                            PDGNodeType.EXIT_ASSIGNMENT,
                                                            currentNode,
                                                            result.getJavaType(),
                                                            k);
                addEdge(pcResult, retMerge, PDGEdgeType.IMPLICIT);
            } else {
                retMerge = mergeIfNecessary(exitAssignments,
                                            type + "_RET_MERGE",
                                            PDGNodeType.OTHER_EXPRESSION,
                                            result.getJavaType(),
                                            k);
            }
            addEdge(retMerge, result, PDGEdgeType.COPY);
        }
    }

    /**
     * Add edges in the caller for a particular resolved method call
     *
     * @param callSitePC pc at the call site before the call
     * @param callee callee call graph node and summary exit context
     * @param exitLabel label for the return site
     * @param callKey instruction and whether adding edges for normal or exceptional return
     * @return Pair of PDG nodes, the first is the new PC after the call (in the caller) and the second is the exit
     *         assignment node (in the caller)
     */
    private OrderedPair<PDGNode, PDGNode> addCallerExitEdges(PDGNode callSitePC,
                                                             OrderedPair<CGNode, PDGContext> callee,
                                                             CallSiteEdgeLabel exitLabel,
                                                             OrderedPair<SSAInvokeInstruction, ExitType> callKey) {
        assert callSitePC.getNodeType().isPathCondition();
        assert callee != null;
        assert exitLabel != null;
        assert exitLabel.getType() == SiteType.EXIT;
        assert callKey != null;
        OrderedPair<OrderedPair<SSAInvokeInstruction, ExitType>, CGNode> key = new OrderedPair<>(callKey, callee.fst());
        String methodName = PrettyPrinter.methodString(callee.fst().getMethod());
        ExitType type = callKey.snd();
        PDGContext context = callee.snd();

        // Create a PC node that is the program point right after returning from the callee
        String desc = type + "_EXIT_PC after " + methodName;
        PDGNode newPC = PDGNodeFactory.findOrCreateOther(desc, PDGNodeType.EXIT_PC_JOIN, currentNode, null, key);
        // The new PC is only reached if the method was called (oldPC) and the method returned normally (calleeNormal.getPCNode)
        addEdge(callSitePC, newPC, PDGEdgeType.CONJUNCTION);
        addEdge(context.getPCNode(), newPC, PDGEdgeType.CONJUNCTION, exitLabel);

        PDGNode exitAssignment = null;
        if (type == ExitType.EXCEPTIONAL || callKey.fst().getNumberOfReturnValues() > 0) {
            PDGNode exitNode = (type == ExitType.NORMAL) ? context.getReturnNode() : context.getExceptionNode();
            assert exitNode != null;

            // Create a node representing the assignment of the return value in the caller
            String s = type + "_RET after " + methodName;
            exitAssignment = PDGNodeFactory.findOrCreateOther(s,
                                                              PDGNodeType.EXIT_ASSIGNMENT,
                                                              currentNode,
                                                              exitNode.getJavaType(),
                                                              key);

            // Add edges constraining the exit assignment node
            addEdge(newPC, exitAssignment, PDGEdgeType.IMPLICIT);
            addEdge(exitNode, exitAssignment, PDGEdgeType.COPY, exitLabel);
        }

        return new OrderedPair<>(newPC, exitAssignment);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowGoto(SSAGotoInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowLoadMetadata(SSALoadMetadataInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGContext in = instructionInput.get(i);
        String desc;
        if (i.getToken() instanceof TypeReference) {
            desc = PrettyPrinter.typeString((TypeReference) i.getToken());
        }
        else {
            desc = "metadata " + i.getToken();
        }
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);
        PDGNode ref = PDGNodeFactory.findOrCreateOther("load " + desc,
                                                       PDGNodeType.OTHER_EXPRESSION,
                                                       currentNode,
                                                       result.getJavaType(),
                                                       i);

        addEdge(ref, result, PDGEdgeType.EXP);

        handlePossibleException(TypeReference.JavaLangClassNotFoundException, ref, in, desc + " not found", current);
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowMonitor(SSAMonitorInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode ref = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        String desc = pp.valString(i.getRef()) + " == null";
        handlePossibleException(TypeReference.JavaLangNullPointerException, ref, in, desc, current);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowNewArray(SSANewInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);
        Set<PDGNode> allSizes = new LinkedHashSet<>();
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            PDGNode size = PDGNodeFactory.findOrCreateUse(i, j, currentNode, pp, types);
            allSizes.add(size);
            addEdge(size, result, PDGEdgeType.EXP);
        }

        PDGNode sizeMerge = mergeIfNecessary(allSizes,
                                             "INDICES",
                                             PDGNodeType.OTHER_EXPRESSION,
                                             null,
                                             new OrderedPair<>(i, "INDEX MERGE"));

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
        if (Signatures.isImmutableWrapperType(i.getConcreteType())) {
            // Immutable wrappers (e.g. String, Integer, etc.) are handled when the constructor is called in addEdgesForImmutableWrapper
            // They are treated as primitives
            return factToMap(Unit.VALUE, current, cfg);
        }

        PDGNode allocNode = PDGNodeFactory.findOrCreateOther(pp.rightSideString(i), PDGNodeType.BASE_VALUE,
                                                             currentNode,
                                                             i.getConcreteType(),
                                                             i);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        addEdge(allocNode, result, PDGEdgeType.COPY);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return factToMap(Unit.VALUE, current, cfg);
    }


    @Override
    protected Map<ISSABasicBlock, Unit> flowPutField(SSAPutInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode receiver = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 1, currentNode, pp, types);

        PDGContext in = instructionInput.get(i);

        // Possibly throw NPE
        String desc = pp.valString(i.getRef()) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException, receiver, in, desc,
                                        current);

        // If there are no exceptions then the assignment occurs. A new node is
        // created to record the program point via a PC edge.
        String resultDesc = pp.instructionString(i);
        PDGNode result = PDGNodeFactory.findOrCreateOther(resultDesc, PDGNodeType.OTHER_EXPRESSION, currentNode,
                                                          i.getDeclaredFieldType(),
                                                          new OrderedPair<>(i, "RESULT"));
        addEdge(value, result, PDGEdgeType.COPY);
        addEdge(receiver, result, PDGEdgeType.POINTER);
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
        if (i.getNumberOfUses() > 0) {
            PDGContext out = outputFacts.get(current).get(getNormalSuccs(current, cfg).iterator().next());
            PDGContext in = instructionInput.get(i);
            PDGNode ret = out.getReturnNode();
            PDGNode val = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
            addEdge(val, ret, PDGEdgeType.COPY);
            addEdge(in.getPCNode(), ret, PDGEdgeType.IMPLICIT);
        }
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowSwitch(SSASwitchInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGContext in = instructionInput.get(i);

        PDGNode switchGuard = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);
        PDGNode newPC = outputFacts.get(current).get(getNormalSuccs(current, cfg).iterator().next()).getPCNode();

        // record that the switch PC depends on the guard and the input PC
        addEdge(switchGuard, newPC, PDGEdgeType.SWITCH);
        addEdge(in.getPCNode(), newPC, PDGEdgeType.CONJUNCTION);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowThrow(SSAThrowInstruction i, Set<Unit> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode exception = PDGNodeFactory.findOrCreateUse(i, 0, currentNode, pp, types);

        // Get the input context
        PDGContext in = instructionInput.get(i);

        String desc = pp.valString(i.getException()) + " == null";
        PDGContext normal = handlePossibleException(TypeReference.JavaLangNullPointerException,
                                                    exception,
                                                    in,
                                                    desc,
                                                    current);

        PDGNode throwExpr = PDGComputeNodesDataflow.getNodeForThrowExpression(i,
                                                                              current,
                                                                              currentNode,
                                                                              pp,
                                                                              types.getType(i.getException()));
        addEdge(exception, throwExpr, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), throwExpr, PDGEdgeType.IMPLICIT);

        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        return interProc.getReachabilityResults().isUnreachable(source, target, currentNode);
    }

    /**
     * Add an edge of the given type from/to a procdure summary node for a callee
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
     * @param nodesToMerge nodes to be merged if necessary
     * @param mergeNodeDesc description to be put into the new merge node
     * @param mergeNodeType type of the new merge node
     * @param javaType type of the expression represented by the merge node or null if the node doesn't have one
     * @param disambiguationKey key to disambiguate the new merge node
     * @return new merge node if any, if the set contains only one node then that node is returned, null if the set of
     *         nodes is empty
     */
    private PDGNode mergeIfNecessary(Set<PDGNode> nodesToMerge, String mergeNodeDesc, PDGNodeType mergeNodeType,
                                     TypeReference javaType, Object disambiguationKey) {
        PDGNode result;
        if (nodesToMerge.size() > 1) {
            result = PDGNodeFactory.findOrCreateOther(mergeNodeDesc,
                                                      mergeNodeType,
                                                      currentNode,
                                                      javaType,
                                                      disambiguationKey);
            addEdgesForMerge(nodesToMerge, result);
        } else if (nodesToMerge.size() == 1) {
            return nodesToMerge.iterator().next();
        } else {
            if (outputLevel >= 1) {
                // TODO Probably an empty points-to set, sometimes this is due to dead code
                System.err.println("Empty set of nodes to merge, DESC: " + mergeNodeDesc + " KEY: " + disambiguationKey);
            }
            return PDGNodeFactory.findOrCreateOther(mergeNodeDesc,
                                                    mergeNodeType,
                                                    currentNode,
                                                    javaType,
                                                    disambiguationKey);
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
     * Determine whether a exception of the given type can be thrown and create nodes and edges in the PDG to capture
     * the dependencies when an exception is thrown by the JVM (e.g. a {@link NullPointerException}).
     *
     * @param exType
     *            type of exception being thrown
     * @param cause
     *            cause of the exception (e.g. possibly null value if an NPE)
     * @param beforeException
     *            context (including the PC node) immediately before the exception is thrown
     * @param branchDescription
     *            description of the condition that causes the exception. The condition being true should result in the
     *            exception. (e.g. o == null for an NPE, index > a.length for an ArrayIndexOutOfBoundsException).
     * @param bb
     *            basic block that could throw the exception
     *
     * @return PDGContext when the exception is not thrown
     */
    private PDGContext handlePossibleException(TypeReference exType, PDGNode cause, PDGContext beforeException,
                                    String branchDescription, ISSABasicBlock bb) {
        if (interProc.getPreciseExceptionResults().canThrowException(exType, bb, currentNode)) {
            // XXX Use a boolean type, the node is meant to model a conditional branch
            PDGNode branch = PDGNodeFactory.findOrCreateOther(branchDescription, PDGNodeType.OTHER_EXPRESSION,
                                                              currentNode,
                                                              TypeReference.Boolean,
                                                              new OrderedPair<>(bb, exType));
            addEdge(cause, branch, PDGEdgeType.EXP);

            assert trueExceptionContexts.get(bb) != null;
            assert trueExceptionContexts.get(bb).get(exType) != null;
            PDGContext trueContext = trueExceptionContexts.get(bb).get(exType);
            PDGNode truePC = trueContext.getPCNode();
            addEdge(branch, truePC, PDGEdgeType.TRUE);
            addEdge(beforeException.getPCNode(), truePC, PDGEdgeType.CONJUNCTION);
            // The exception is only created if the truePC is triggered
            addEdge(truePC, trueContext.getExceptionNode(), PDGEdgeType.IMPLICIT);

            PDGContext falseContext = falseExceptionContexts.get(bb).get(exType);
            addEdge(branch, falseContext.getPCNode(), PDGEdgeType.FALSE);
            addEdge(beforeException.getPCNode(), falseContext.getPCNode(), PDGEdgeType.CONJUNCTION);

            // Return normal context
            return falseContext;
        }
        return beforeException;
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowEmptyBlock(Set<Unit> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    /**
     * Check whether the procedure can terminate normally (in a particular context).
     *
     * @param n
     *            call graph node containing the method and context
     * @return whether the method can terminate normally in the given context
     */
    private boolean canProcedureTerminateNormally(CGNode n) {
        if (n.getMethod().isNative() && !AnalysisUtil.hasSignature(n.getMethod())) {
            // assume native methods can terminate normally
            return true;
        }

        SSACFG cfg = n.getIR().getControlFlowGraph();
        ISSABasicBlock exit = cfg.exit();

        for (ISSABasicBlock pred : cfg.getNormalPredecessors(exit)) {
            if (!interProc.getReachabilityResults().isUnreachable(pred, exit, n)) {
                return true;
            }
        }
        return false;
    }
}
