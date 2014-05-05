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
import analysis.dataflow.interprocedural.pdg.graph.PDGEdgeType;
import analysis.dataflow.interprocedural.pdg.graph.node.AbstractLocationPDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeFactory;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeType;
import analysis.dataflow.interprocedural.pdg.graph.node.ProcedureSummaryNodes;
import analysis.dataflow.util.AbstractLocation;
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

/**
 * Data-flow that builds up the set of nodes in a program dependence graph.
 */
public class PDGNodeDataflow extends InstructionDispatchDataFlow<PDGContext> {

    private final CGNode currentNode;
    private final PDGInterproceduralDataFlow interProc;
    private final ProcedureSummaryNodes summary;
    private final IR ir;
    private final CallGraph cg;
    private final PointsToGraph ptg;
    private final WalaAnalysisUtil util;

    public PDGNodeDataflow(CGNode currentNode, PDGInterproceduralDataFlow interProc, ProcedureSummaryNodes summary,
                                    WalaAnalysisUtil util) {
        super(true);
        this.currentNode = currentNode;
        this.interProc = interProc;
        this.summary = summary;
        this.ir = currentNode.getIR();
        this.cg = interProc.getCallGraph();
        this.ptg = interProc.getPointsToGraph();
        this.util = util;
        // TODO compute post dominators
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flow(Set<PDGContext> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return super.flow(inItems, cfg, current);
    }

    @Override
    protected void post(IR ir) {
        // TODO Auto-generated method stub
    }

    @Override
    protected void postBasicBlock(Set<PDGContext> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock justProcessed, Map<ISSABasicBlock, PDGContext> outItems) {
        // TODO Auto-generated method stub

        // Check the context, make sure that only exit and catch successors have
        // stuff in the return and exception places respectively

        // TODO restore PC for post dominators

        super.postBasicBlock(inItems, cfg, justProcessed, outItems);
    }

    /**
     * Do not call this method except to merge upon entering a basic block. Use
     * {@link PDGNodeDataflow#mergeContexts()} instead.
     * <p>
     * {@inheritDoc}
     */
    @Override
    protected PDGContext confluence(Set<PDGContext> facts, ISSABasicBlock bb) {
        return mergeContexts("confluence", facts.toArray(new PDGContext[facts.size()]));
    }

    @Override
    protected PDGContext flowBinaryOp(SSABinaryOpInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode v0 = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode v1 = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);
        PDGNode assignee = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        PDGContext in = confluence(previousItems, current);

        addEdge(v0, assignee, PDGEdgeType.EXP);
        addEdge(v1, assignee, PDGEdgeType.EXP);
        addEdge(in.getPCNode(), assignee, PDGEdgeType.IMPLICIT);

        return in; 
    }

    @Override
    protected PDGContext flowComparison(SSAComparisonInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode v0 = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode v1 = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);
        PDGNode assignee = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        addEdge(v0, assignee, PDGEdgeType.EXP);
        addEdge(v1, assignee, PDGEdgeType.EXP);

        PDGContext in = confluence(previousItems, current);
        addEdge(in.getPCNode(), assignee, PDGEdgeType.IMPLICIT);
        return in;
    }

    @Override
    protected PDGContext flowConversion(SSAConversionInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode converted = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        addEdge(converted, result, PDGEdgeType.COPY);

        PDGContext in = confluence(previousItems, current);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);
        return in;
    }

    @Override
    protected PDGContext flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Merge exceptions
        PDGNode caughtEx = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        for (PDGContext fact : previousItems) {
            PDGNode inEx = fact.getExceptionNode();
            addEdge(inEx, caughtEx, PDGEdgeType.MERGE);
        }

        return confluence(previousItems, current);
    }

    @Override
    protected PDGContext flowGetStatic(SSAGetInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        AbstractLocation fieldLoc = AbstractLocation.createStatic(i.getDeclaredField());
        AbstractLocationPDGNode fieldNode = PDGNodeFactory.findOrCreateAbstractLocation(fieldLoc);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        addEdge(fieldNode, result, PDGEdgeType.COPY);

        PDGContext in = confluence(previousItems, current);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);
        return in;
    }

    @Override
    protected PDGContext flowInstanceOf(SSAInstanceofInstruction i, Set<PDGContext> previousItems,
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

        PDGContext in = confluence(previousItems, current);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);
        return in;
    }

    @Override
    protected PDGContext flowPhi(SSAPhiInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int useNum = i.getUse(j);
            PDGNode choice = PDGNodeFactory.findOrCreateUse(i, useNum, currentNode);
            addEdge(choice, result, PDGEdgeType.MERGE);
        }

        PDGContext in = confluence(previousItems, current);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);
        return in;
    }

    @Override
    protected PDGContext flowPutStatic(SSAPutInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        AbstractLocation fieldLoc = AbstractLocation.createStatic(i.getDeclaredField());
        AbstractLocationPDGNode fieldNode = PDGNodeFactory.findOrCreateAbstractLocation(fieldLoc);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);

        // create intermediate node for this assignment
        String desc = PrettyPrinter.rightSideString(i, currentNode.getIR());
        PDGNode assignment = PDGNodeFactory.findOrCreateExpression(desc, PDGNodeType.OTHER_EXPRESSION, currentNode, i);

        addEdge(value, assignment, PDGEdgeType.COPY);
        addEdge(assignment, fieldNode, PDGEdgeType.MERGE);

        PDGContext in = confluence(previousItems, current);
        // The PC edge is into the assignment node as this is what depends on
        // reaching the current program point
        addEdge(in.getPCNode(), assignment, PDGEdgeType.IMPLICIT);
        return in;
    }

    @Override
    protected PDGContext flowUnaryNegation(SSAUnaryOpInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {

        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        addEdge(value, result, PDGEdgeType.EXP);

        PDGContext in = confluence(previousItems, current);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);
        return in;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowArrayLength(SSAArrayLengthInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode array = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        addEdge(array, result, PDGEdgeType.EXP);

        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getArrayRef(), ir) + " == null";
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException, array,
                                        in, desc, current, cfg);

        PDGContext npe = afterEx.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterEx.get(ExitType.NORMAL);
        // The only way the value gets assigned is if no NPE is thrown
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowArrayLoad(SSAArrayLoadInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode array = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode index = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        // Possibly throw NPE
        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getArrayRef(), ir) + " == null";
        Map<ExitType, PDGContext> afterNPE = handlePossibleException(TypeReference.JavaLangNullPointerException, array,
                                        in, desc, current, cfg);

        PDGContext npe = afterNPE.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterNPE.get(ExitType.NORMAL);

        // Node and edges for the access itself. This extra node is created
        // because it is the access itself that can cause an
        // ArrayIndexOutOfBoundsException.
        String d = PrettyPrinter.valString(i.getArrayRef(), ir) + "[" + PrettyPrinter.valString(i.getIndex(), ir) + "]";
        PDGNode arrayAccess = PDGNodeFactory.findOrCreateExpression(d, PDGNodeType.OTHER_EXPRESSION, currentNode, i);
        addEdge(array, arrayAccess, PDGEdgeType.EXP);
        addEdge(index, arrayAccess, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), arrayAccess, PDGEdgeType.IMPLICIT);

        // Add edges from the array contents to the access
        // TODO make sure we need the merge node for locations
        PDGNode locMerge = PDGNodeFactory.findOrCreateExpression("ABS LOC MERGE", PDGNodeType.LOCATION_SUMMARY,
                                        currentNode, i);
        for (AbstractLocation loc : interProc.getLocationsForArrayContents(i.getArrayRef(), currentNode)) {
            PDGNode locNode = PDGNodeFactory.findOrCreateAbstractLocation(loc);
            addEdge(locNode, locMerge, PDGEdgeType.MERGE);
        }
        addEdge(locMerge, arrayAccess, PDGEdgeType.COPY);

        // If no NPE is thrown then this may throw an
        // ArrayIndexOutOfBoundsException
        String isOOB = PrettyPrinter.valString(i.getIndex(), ir) + " >= "
                                        + PrettyPrinter.valString(i.getArrayRef(), ir) + ".length";
        Map<ExitType, PDGContext> afterEx = handlePossibleException(
                                        TypeReference.JavaLangArrayIndexOutOfBoundsException, arrayAccess, normal,
                                        isOOB, current, cfg);
        PDGContext aioob = afterEx.get(ExitType.EXCEPTIONAL);
        normal = afterEx.get(ExitType.NORMAL);

        // The only way the value gets assigned is if no exception is thrown
        addEdge(arrayAccess, result, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        Map<ISSABasicBlock, PDGContext> out = new LinkedHashMap<>();
        PreciseExceptionResults pe = interProc.getPreciseExceptionResults();

        PDGContext mergedExContext = null;
        if (npe != null && aioob != null) {
            mergedExContext = mergeContexts(i, npe, aioob);
        }

        for (ISSABasicBlock succ : getExceptionalSuccs(current, cfg)) {
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

        for (ISSABasicBlock succ : getNormalSuccs(current, cfg)) {
            out.put(succ, normal);
        }

        return out;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowArrayStore(SSAArrayStoreInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode array = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode index = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 2, currentNode);

        // Map from type of exception to context on edges where it is thrown
        Map<TypeReference, PDGContext> exContexts = new LinkedHashMap<>();

        // Possibly throw NPE
        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getArrayRef(), ir) + " == null";
        Map<ExitType, PDGContext> afterNPE = handlePossibleException(TypeReference.JavaLangNullPointerException, array,
                                        in, desc, current, cfg);

        exContexts.put(TypeReference.JavaLangNullPointerException, afterNPE.get(ExitType.EXCEPTIONAL));
        PDGContext normal = afterNPE.get(ExitType.NORMAL);

        // Node and edges for the access itself. This extra node is created
        // because it is the access itself that can cause an
        // ArrayIndexOutOfBoundsException.
        String d = PrettyPrinter.valString(i.getArrayRef(), ir) + "[" + PrettyPrinter.valString(i.getIndex(), ir) + "]";
        PDGNode arrayAccess = PDGNodeFactory.findOrCreateExpression(d, PDGNodeType.OTHER_EXPRESSION, currentNode,
                                        new OrderedPair<>(i, "ARRAY_ACCESS"));
        addEdge(array, arrayAccess, PDGEdgeType.EXP);
        addEdge(index, arrayAccess, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), arrayAccess, PDGEdgeType.IMPLICIT);

        // If no NPE is thrown then this may throw an
        // ArrayIndexOutOfBoundsException
        String isOOB = PrettyPrinter.valString(i.getIndex(), ir) + " >= "
                                        + PrettyPrinter.valString(i.getArrayRef(), ir) + ".length";
        Map<ExitType, PDGContext> afterAIOOB = handlePossibleException(
                                        TypeReference.JavaLangArrayIndexOutOfBoundsException, arrayAccess, normal,
                                        isOOB, current, cfg);
        exContexts.put(TypeReference.JavaLangArrayIndexOutOfBoundsException, afterAIOOB.get(ExitType.EXCEPTIONAL));
        normal = afterAIOOB.get(ExitType.NORMAL);

        // Node and edges for the assignment. This extra node is created
        // because it is the assignment that can cause an
        // ArrayStoreException.
        String storeDesc = "STORE " + PrettyPrinter.instructionString(i, ir);
        PDGNode store = PDGNodeFactory.findOrCreateExpression(storeDesc, PDGNodeType.OTHER_EXPRESSION, currentNode,
                                        new OrderedPair<>(i, "STORE"));
        addEdge(arrayAccess, store, PDGEdgeType.EXP);
        addEdge(value, store, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), store, PDGEdgeType.IMPLICIT);

        // If no ArrayIndexOutOfBoundsException is thrown then this may throw an
        // ArrayStoreException
        String arrayStoreDesc = "!" + PrettyPrinter.valString(i.getValue(), ir) + " instanceof "
                                        + PrettyPrinter.valString(i.getArrayRef(), ir) + ".elementType";
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangArrayStoreException, store,
                                        normal, arrayStoreDesc, current, cfg);
        exContexts.put(TypeReference.JavaLangArrayStoreException, afterEx.get(ExitType.EXCEPTIONAL));
        normal = afterEx.get(ExitType.NORMAL);

        // If there are no exceptions then the assignment occurs
        String resultDesc = PrettyPrinter.instructionString(i, ir);
        PDGNode result = PDGNodeFactory.findOrCreateExpression(resultDesc, PDGNodeType.OTHER_EXPRESSION, currentNode,
                                        new OrderedPair<>(i, "RESULT"));
        addEdge(store, result, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        // Add edge from the assignment to the array contents
        for (AbstractLocation loc : interProc.getLocationsForArrayContents(i.getArrayRef(), currentNode)) {
            PDGNode locNode = PDGNodeFactory.findOrCreateAbstractLocation(loc);
            addEdge(result, locNode, PDGEdgeType.MERGE);
        }

        Map<ISSABasicBlock, PDGContext> out = new LinkedHashMap<>();
        PreciseExceptionResults pe = interProc.getPreciseExceptionResults();

        for (ISSABasicBlock succ : getExceptionalSuccs(current, cfg)) {
            Set<TypeReference> exes = pe.getExceptions(current, succ, currentNode);
            PDGContext[] toMerge = new PDGContext[exes.size()];
            int j = 0;
            for (TypeReference exType : exes) {
                toMerge[j] = exContexts.get(exType);
                j++;
            }
            out.put(succ, mergeContexts(new OrderedPair<>(i, exes), toMerge));
        }

        for (ISSABasicBlock succ : getNormalSuccs(current, cfg)) {
            out.put(succ, normal);
        }

        return out;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode v0 = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode v1 = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);
        PDGNode assignee = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getUse(1), ir) + " == 0";
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangArithmeticException, v1,
                                        in, desc, current, cfg);

        PDGContext ex = afterEx.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterEx.get(ExitType.NORMAL);

        // The only way the value gets assigned is if no exception is thrown
        addEdge(v0, assignee, PDGEdgeType.EXP);
        addEdge(v1, assignee, PDGEdgeType.EXP);
        addEdge(normal.getPCNode(), assignee, PDGEdgeType.IMPLICIT);
        return factsToMapWithExceptions(normal, ex, current, cfg);
    }
    
    @Override
    protected Map<ISSABasicBlock, PDGContext> flowCheckCast(SSACheckCastInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);

        // Possibly throw ClassCastException
        PDGContext in = confluence(previousItems, current);
        String desc = "!" + PrettyPrinter.valString(i.getVal(), ir) + " instanceof "
                                        + PrettyPrinter.parseType(i.getDeclaredResultTypes()[0]);
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangClassCastException, value,
                                        in, desc, current, cfg);

        PDGContext classCast = afterEx.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterEx.get(ExitType.NORMAL);

        // If there is no exception then assign
        addEdge(value, result, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        return factsToMapWithExceptions(normal, classCast, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        String cond = PrettyPrinter.valString(i.getUse(0), ir) + " "
                                        + PrettyPrinter.conditionalOperatorString(i.getOperator()) + " "
                                        + PrettyPrinter.valString(i.getUse(1), ir);
        PDGNode condNode = PDGNodeFactory.findOrCreateExpression(cond, PDGNodeType.OTHER_EXPRESSION, currentNode, i);
        PDGNode val0 = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode val1 = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);
        PDGNode truePC = PDGNodeFactory.findOrCreateExpression(cond, PDGNodeType.BOOLEAN_TRUE_PC, currentNode, i);
        PDGNode falsePC = PDGNodeFactory.findOrCreateExpression(cond, PDGNodeType.BOOLEAN_FALSE_PC, currentNode, i);

        addEdge(val0, condNode, PDGEdgeType.EXP);
        addEdge(val1, condNode, PDGEdgeType.EXP);

        PDGContext in = confluence(previousItems, current);
        addEdge(in.getPCNode(), truePC, PDGEdgeType.CONJUNCTION);
        addEdge(in.getPCNode(), falsePC, PDGEdgeType.CONJUNCTION);

        addEdge(condNode, truePC, PDGEdgeType.TRUE);
        addEdge(condNode, falsePC, PDGEdgeType.FALSE);

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
        PDGNode target = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);

        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getRef(), ir) + " == null";
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException, target,
                                        in, desc, current, cfg);

        PDGContext npe = afterEx.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterEx.get(ExitType.NORMAL);

        // The only way the value gets assigned is if no NPE is thrown

        // Add edges from the field to the result
        // TODO make sure we need the merge node for locations
        PDGNode locMerge = PDGNodeFactory.findOrCreateExpression("ABS LOC MERGE", PDGNodeType.LOCATION_SUMMARY,
                                        currentNode, i);
        for (AbstractLocation loc : interProc.getLocationsForNonStaticField(i.getRef(), i.getDeclaredField(),
                                        currentNode)) {
            PDGNode locNode = PDGNodeFactory.findOrCreateAbstractLocation(loc);
            addEdge(locNode, locMerge, PDGEdgeType.MERGE);
        }
        addEdge(locMerge, result, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

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
        List<PDGNode> params = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            params.add(PDGNodeFactory.findOrCreateUse(i, j, currentNode));
        }

        PDGContext in = confluence(previousItems, current);
        PDGContext npe = null;
        PDGContext normal = in;
        if (!i.isStatic()) {
            // Could throw NPE
            String desc = PrettyPrinter.valString(i.getReceiver(), ir) + " == null";
            Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException,
                                            params.get(0), in, desc, current, cfg);

            npe = afterEx.get(ExitType.EXCEPTIONAL);
            normal = afterEx.get(ExitType.NORMAL);

            // TODO if no NPE throw WrongMethodTypeException
        }

        // Assign actuals to formal assignment nodes in the caller
        List<PDGNode> formalAssignments = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            String s = "formal(" + j + ") = " + PrettyPrinter.valString(i.getUse(j), ir);
            PDGNode formalAssign = PDGNodeFactory.findOrCreateExpression(s, PDGNodeType.FORMAL_ASSIGNMENT, currentNode,
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
            addEdge(normal.getPCNode(), calleeEntry.getPCNode(), PDGEdgeType.MERGE);

            // Copy formalAssignments into formals
            for (int j = 0; j < i.getNumberOfParameters(); j++) {
                PDGNode formal = calleeSummary.getFormal(j);
                addEdge(formalAssignments.get(j), formal, PDGEdgeType.MERGE);
            }

            calleeNormalExits.add(calleeSummary.getNormalExitContext());
            calleeExceptionalExits.add(calleeSummary.getExceptionalExitContext());
        }
        PDGContext calleeNormalExit = mergeContexts(new OrderedPair<>(i, ExitType.NORMAL),
                                        calleeNormalExits.toArray(new PDGContext[calleeNormalExits.size()]));
        PDGContext calleeExceptionalExit = mergeContexts(new OrderedPair<>(i, ExitType.EXCEPTIONAL),
                                        calleeExceptionalExits.toArray(new PDGContext[calleeExceptionalExits.size()]));

        // Join the caller PC before the call to the PC after the call
        PDGNode normalExitPC = PDGNodeFactory.findOrCreateExpression("EXIT_PC_JOIN", PDGNodeType.EXIT_PC_JOIN,
                                        currentNode, new OrderedPair<>(i, ExitType.NORMAL));
        addEdge(calleeNormalExit.getPCNode(), normalExitPC, PDGEdgeType.CONJUNCTION);
        addEdge(normal.getPCNode(), normalExitPC, PDGEdgeType.CONJUNCTION);

        PDGNode exExitPC = PDGNodeFactory.findOrCreateExpression("EXIT_PC_JOIN", PDGNodeType.EXIT_PC_JOIN, currentNode,
                                        new OrderedPair<>(i, ExitType.EXCEPTIONAL));
        addEdge(calleeExceptionalExit.getPCNode(), exExitPC, PDGEdgeType.CONJUNCTION);
        addEdge(normal.getPCNode(), exExitPC, PDGEdgeType.CONJUNCTION);

        // Copy return if any
        if (i.getNumberOfReturnValues() > 0) {
            PDGNode result = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
            addEdge(calleeNormalExit.getReturnNode(), result, PDGEdgeType.COPY);
            addEdge(normalExitPC, result, PDGEdgeType.IMPLICIT);
        }

        Map<ISSABasicBlock, PDGContext> out = new LinkedHashMap<>();

        PDGNode exValue = PDGNodeFactory.findOrCreateLocal(i.getException(), currentNode);
        addEdge(calleeExceptionalExit.getExceptionNode(), exValue, PDGEdgeType.COPY);
        addEdge(exExitPC, exValue, PDGEdgeType.IMPLICIT);

        PDGContext exContext = new PDGContext(null, exValue, exExitPC);
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
            boolean canThrowNPE = pe.getExceptions(current, succ, currentNode).contains(
                                            TypeReference.JavaLangNullPointerException);
            if (canThrowNPE && npe != null) {
                out.put(succ, mergedExContext);
            } else {
                out.put(succ, exContext);
            }
        }

        for (ISSABasicBlock succ : getNormalSuccs(current, cfg)) {
            out.put(succ, new PDGContext(null, null, normalExitPC));
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
        // TODO this can throw a ClassNotFoundException, but this could be known
        // statically if all the class files were known.
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowMonitor(SSAMonitorInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode ref = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);

        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getRef(), ir) + " == null";
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangNullPointerException, ref,
                                        in, desc, current, cfg);

        PDGContext npe = afterEx.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterEx.get(ExitType.NORMAL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowNewArray(SSANewInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode allSizes = null;
        if (i.getNumberOfUses() > 1) {
            allSizes = PDGNodeFactory.findOrCreateExpression(PrettyPrinter.rightSideString(i, ir) + " indices",
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

        PDGContext in = confluence(previousItems, current);
        Map<ExitType, PDGContext> afterEx = handlePossibleException(TypeReference.JavaLangNegativeArraySizeException,
                                        allSizes, in, "size < 0", current, cfg);

        PDGContext negArraySize = afterEx.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterEx.get(ExitType.NORMAL);

        // The only way the value gets assigned is if no exception is thrown
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

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
        PDGNode receiver = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode value = PDGNodeFactory.findOrCreateUse(i, 1, currentNode);

        // Possibly throw NPE
        PDGContext in = confluence(previousItems, current);
        String desc = PrettyPrinter.valString(i.getRef(), ir) + " == null";
        Map<ExitType, PDGContext> afterNPE = handlePossibleException(TypeReference.JavaLangNullPointerException,
                                        receiver, in, desc, current, cfg);

        PDGContext npe = afterNPE.get(ExitType.EXCEPTIONAL);
        PDGContext normal = afterNPE.get(ExitType.NORMAL);

        // If there are no exceptions then the assignment occurs
        String resultDesc = PrettyPrinter.instructionString(i, ir);
        PDGNode result = PDGNodeFactory.findOrCreateExpression(resultDesc, PDGNodeType.OTHER_EXPRESSION, currentNode,
                                        new OrderedPair<>(i, "RESULT"));
        addEdge(value, result, PDGEdgeType.COPY);
        addEdge(normal.getPCNode(), result, PDGEdgeType.IMPLICIT);

        // Add edge from the assignment to the field
        for (AbstractLocation loc : interProc.getLocationsForNonStaticField(i.getRef(), i.getDeclaredField(),
                                        currentNode)) {
            PDGNode locNode = PDGNodeFactory.findOrCreateAbstractLocation(loc);
            addEdge(result, locNode, PDGEdgeType.MERGE);
        }

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowReturn(SSAReturnInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGContext in = confluence(previousItems, current);
        PDGNode returnValue = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        return factToMap(new PDGContext(returnValue, null, in.getPCNode()), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowSwitch(SSASwitchInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGContext in = confluence(previousItems, current);
        // Create a new PC node based the switched value
        PDGNode switchGuard = PDGNodeFactory.findOrCreateUse(i, 0, currentNode);
        PDGNode newPC = PDGNodeFactory.findOrCreateExpression("switch PC", PDGNodeType.PC_OTHER, currentNode, i);
        addEdge(switchGuard, newPC, PDGEdgeType.SWITCH);
        addEdge(in.getPCNode(), newPC, PDGEdgeType.CONJUNCTION);
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
     * @return Two PDG contexts one for when the exception is not thrown and one
     *         for when it is thrown. If the exception cannot be thrown then the
     *         exceptional context will be null.
     */
    private Map<ExitType, PDGContext> handlePossibleException(TypeReference exType, PDGNode cause,
                                    PDGContext beforeException, String branchDescription, ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        SSAInstruction i = getLastInstruction(bb);
        if (mayThrowException(exType, bb, cfg)) {
            Map<ExitType, PDGContext> out = new LinkedHashMap<>();

            PDGNode branch = PDGNodeFactory.findOrCreateExpression(branchDescription, PDGNodeType.OTHER_EXPRESSION,
                                            currentNode, i);
            addEdge(cause, branch, PDGEdgeType.EXP);

            PDGNode truePC = PDGNodeFactory.findOrCreateExpression(branchDescription, PDGNodeType.BOOLEAN_TRUE_PC,
                                            currentNode, i);
            addEdge(branch, truePC, PDGEdgeType.TRUE);
            addEdge(beforeException.getPCNode(), truePC, PDGEdgeType.CONJUNCTION);

            PDGNode falsePC = PDGNodeFactory.findOrCreateExpression(branchDescription, PDGNodeType.BOOLEAN_FALSE_PC,
                                            currentNode, i);
            addEdge(branch, falsePC, PDGEdgeType.FALSE);
            addEdge(beforeException.getPCNode(), falsePC, PDGEdgeType.CONJUNCTION);

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
        assert contexts.length > 0;
        assert !(disambiguationKey instanceof PDGContext) : "Missing disambiguation key.";
        if (contexts.length == 1) {
            return contexts[0];
        }

        PDGNode newEx = PDGNodeFactory.findOrCreateExpression("EX MERGE", PDGNodeType.EXCEPTION_MERGE, currentNode,
                                        disambiguationKey);
        PDGNode newRet = PDGNodeFactory.findOrCreateExpression("RETURN MERGE", PDGNodeType.OTHER_EXPRESSION,
                                        currentNode, disambiguationKey);
        PDGNode newPC = PDGNodeFactory.findOrCreateExpression("PC MERGE", PDGNodeType.PC_MERGE, currentNode,
                                        disambiguationKey);
        boolean hasException = false;
        boolean hasReturn = false;
        for (PDGContext c : contexts) {
            assert c != null;
            // add edges
            if (c.getExceptionNode() != null) {
                addEdge(c.getExceptionNode(), newEx, PDGEdgeType.MERGE);
                hasException = true;
            }
            if (c.getReturnNode() != null) {
                addEdge(c.getReturnNode(), newRet, PDGEdgeType.MERGE);
                hasReturn = true;
            }
            addEdge(c.getPCNode(), newPC, PDGEdgeType.MERGE);
        }
        return new PDGContext(hasReturn ? newRet : null, hasException ? newEx : null, newPC);
    }
}
