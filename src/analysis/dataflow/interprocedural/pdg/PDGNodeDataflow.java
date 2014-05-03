package analysis.dataflow.interprocedural.pdg;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flow(Set<PDGContext> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return super.flow(inItems, cfg, current);
    }

    @Override
    protected void post(IR ir) {
        // TODO Auto-generated method stub

        // construct the intra-procedural PDG from the VarContexts in the output
        // map

        // If the node is different for a local add an edge in the PDG from the
        // source local to the target local

        // TODO how do we keep track of AbstractLocations?

        // Maybe another pass to add edges rather than the above
    }

    @Override
    protected void postBasicBlock(Set<PDGContext> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock justProcessed, Map<ISSABasicBlock, PDGContext> outItems) {
        // TODO Auto-generated method stub

        // Check the context, make sure that only exit and catch successors have
        // stuff in the return and exception places respectively
        super.postBasicBlock(inItems, cfg, justProcessed, outItems);
    }

    @Override
    protected PDGContext confluence(Set<PDGContext> facts, ISSABasicBlock bb) {
        if (bb.isExitBlock()) {
            // Merge the exceptions and returns
            PDGNode summaryEx = summary.getExceptionNode();
            PDGNode summaryRet = summary.getReturnNode();
            for (PDGContext fact : facts) {
                PDGNode inEx = fact.getExceptionNode();
                if (inEx != null) {
                    addEdge(inEx, summaryEx, PDGEdgeType.MERGE);
                }

                PDGNode inRet = fact.getReturnNode();
                if (inRet != null) {
                    addEdge(inRet, summaryRet, PDGEdgeType.MERGE);
                }
            }
            return null;
        }

        if (facts.size() == 1) {
            return facts.iterator().next();
        }

        // Merge the PCNodes
        // TODO only create a new node if necessary
        PDGNode newPC = PDGNodeFactory.findOrCreateExpression("PC MERGE", PDGNodeType.PC_MERGE, currentNode, bb);
        for (PDGContext fact : facts) {
            PDGNode inPC = fact.getPCNode();
            addEdge(inPC, newPC, PDGEdgeType.MERGE);
        }
        return new PDGContext(null, null, newPC);
    }

    @Override
    protected PDGContext flowBinaryOp(SSABinaryOpInstruction i, Set<PDGContext> previousItems,
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
        PDGNode value = PDGNodeFactory.findOrCreateLocalDef(i, currentNode);
        addEdge(array, value, PDGEdgeType.EXP);

        PDGContext in = confluence(previousItems, current);
        if (mayThrowException(TypeReference.JavaLangNullPointerException, current, cfg)) {
            String desc = PrettyPrinter.valString(i.getArrayRef(), ir) + " == null";
            Map<ExitType, PDGContext> afterEx = handleGeneratedException(TypeReference.JavaLangNullPointerException,
                                            array, in.getPCNode(), desc, i);

            PDGContext npe = afterEx.get(ExitType.EXCEPTIONAL);
            PDGContext normal = afterEx.get(ExitType.NORMAL);
            // The only way the value gets assigned is if no NPE is thrown
            addEdge(normal.getPCNode(), value, PDGEdgeType.IMPLICIT);

            return factsToMapWithExceptions(normal, npe, current, cfg);
        } else {
            addEdge(in.getPCNode(), value, PDGEdgeType.IMPLICIT);
            return factToMap(in, current, cfg);
        }
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowArrayLoad(SSAArrayLoadInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub

        // Throw NPE

        // If no NPE then AIOOB

        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowArrayStore(SSAArrayStoreInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub

        // Throw NPE

        // If no NPE then AIOOB

        // If no AIOOB then array store

        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowCheckCast(SSACheckCastInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub

        // throw check cast

        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub

        // set up PC on both branches use getTrueSuccessor and getFalseSuccessor

        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowGetField(SSAGetInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub

        // Throw NPE
        return null;
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
        // TODO Auto-generated method stub

        // throw exceptions declared by callee

        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowInvokeVirtual(SSAInvokeInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub

        // Throw NPE

        // TODO if no NPE throw WrongMethodTypeException
        
        // Copy actuals into formals

        // If no NPE (and WrongMethodType) copy callee summary exception value
        
        // If no normal termination copy callee summary return value

        return null;
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
        // TODO Auto-generated method stub

        // throw ClassNotFoundException

        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowMonitor(SSAMonitorInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub

        // throw NPE

        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowNewArray(SSANewInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowNewObject(SSANewInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowPutField(SSAPutInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowReturn(SSAReturnInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowSwitch(SSASwitchInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub

        // throw NegativeArraySize

        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowThrow(SSAThrowInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub

        // throw NPE

        // if no NPE throw declared exception

        return null;
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
     * Create nodes and edges in the PDG to capture the dependencies when an
     * exception is thrown by the JVM (e.g. a {@link NullPointerException})
     * 
     * @param exType
     *            type of exception being thrown
     * @param cause
     *            cause of the exception (e.g. possibly null value if an NPE)
     * @param pcBeforeException
     *            program counter node for the program point immediately before
     *            the exception is thrown
     * @param branchDescription
     *            description of the condition that causes the exception. The
     *            condition being true should result in the exception. (e.g. o
     *            == null for an NPE, index > a.length for an
     *            ArrayIndexOutOfBoundsException).
     * @param i
     *            instruction that could throw the exception
     * @return Two PDG contexts one for when the exception is not thrown and one
     *         for when it is thrown.
     */
    private Map<ExitType, PDGContext> handleGeneratedException(TypeReference exType, PDGNode cause,
                                    PDGNode pcBeforeException, String branchDescription, SSAInstruction i) {
        Map<ExitType, PDGContext> out = new LinkedHashMap<>();

        PDGNode branch = PDGNodeFactory.findOrCreateExpression(branchDescription, PDGNodeType.OTHER_EXPRESSION,
                                        currentNode, i);
        addEdge(cause, branch, PDGEdgeType.EXP);

        PDGNode truePC = PDGNodeFactory.findOrCreateExpression(branchDescription, PDGNodeType.BOOLEAN_TRUE_PC,
                                        currentNode, i);
        addEdge(branch, truePC, PDGEdgeType.TRUE);
        addEdge(pcBeforeException, truePC, PDGEdgeType.CONJUNCTION);

        PDGNode falsePC = PDGNodeFactory.findOrCreateExpression(branchDescription, PDGNodeType.BOOLEAN_FALSE_PC,
                                        currentNode, i);
        addEdge(branch, falsePC, PDGEdgeType.FALSE);
        addEdge(pcBeforeException, falsePC, PDGEdgeType.CONJUNCTION);

        PDGNode exceptionValue = PDGNodeFactory.findOrCreateGeneratedException(exType, currentNode, i);

        PDGContext ex = new PDGContext(null, exceptionValue, truePC);
        PDGContext normal = new PDGContext(null, null, falsePC);
        out.put(ExitType.NORMAL, normal);
        out.put(ExitType.EXCEPTIONAL, ex);
        return out;
    }
}
