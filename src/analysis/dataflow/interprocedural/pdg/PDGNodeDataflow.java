package analysis.dataflow.interprocedural.pdg;

import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.interprocedural.pdg.graph.PDGEdgeType;
import analysis.dataflow.interprocedural.pdg.graph.node.ExpressionNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
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

/**
 * Data-flow that builds up the set of nodes in a program dependence graph.
 */
public class PDGNodeDataflow extends InstructionDispatchDataFlow<PDGContext> {

    private final CGNode currentNode;
    private final PDGInterproceduralDataFlow interProc;
    private final ProcedureSummaryNodes summary;
    private final IR ir;

    public PDGNodeDataflow(CGNode currentNode, PDGInterproceduralDataFlow interProc, ProcedureSummaryNodes summary) {
        super(true);
        this.currentNode = currentNode;
        this.interProc = interProc;
        this.summary = summary;
        this.ir = currentNode.getIR();
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
        PDGNode newPC = ExpressionNode.findOrCreate("PC MERGE", PDGNodeType.PC_MERGE, currentNode, bb);
        for (PDGContext fact : facts) {
            PDGNode inPC = fact.getPCNode();
            addEdge(inPC, newPC, PDGEdgeType.MERGE);
        }
        return new PDGContext(null, null, newPC);
    }

    /**
     * Find the unique node for the local variable or constant used by
     * instruction, <code>i</code>, in position <code>useNumber</code>. Create
     * if necessary.
     * 
     * @param i
     *            instruction with at least <code>useNumber</code> + 1 uses
     * @param useNumber
     *            valid use index (uses are 0 indexed)
     * @return PDG node for the use with the given use number in <code>i</code>
     */
    private PDGNode findOrCreateUseNode(SSAInstruction i, int useNumber) {
        assert i.getNumberOfUses() > useNumber;
        int valueNumber = i.getUse(useNumber);
        assert valueNumber >= 0;
        PDGNode n;
        if (ir.getSymbolTable().isConstant(valueNumber)) {
            n = ExpressionNode.findOrCreate(PrettyPrinter.valString(valueNumber, ir), PDGNodeType.BASE_VALUE,
                                            currentNode, valueNumber);
        } else {
            n = ExpressionNode.findOrCreate(PrettyPrinter.valString(valueNumber, ir), PDGNodeType.LOCAL, currentNode,
                                            valueNumber);
        }

        return n;
    }

    /**
     * Find the unique PDG node for the local variable defined by <code>i</code>
     * . Create if necessary.
     * 
     * @param i
     *            instruction that defines a local variable
     * @return PDG node for the defined local
     */
    private PDGNode findOrCreateDefNode(SSAInstruction i) {
        assert i.hasDef();
        return ExpressionNode.findOrCreateLocalDef(i, currentNode);
    }

    @Override
    protected PDGContext flowBinaryOp(SSABinaryOpInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode v0 = findOrCreateUseNode(i, 0);
        PDGNode v1 = findOrCreateUseNode(i, 1);
        PDGNode assignee = findOrCreateDefNode(i);

        addEdge(v0, assignee, PDGEdgeType.EXP);
        addEdge(v1, assignee, PDGEdgeType.EXP);

        PDGContext in = confluence(previousItems, current);
        addEdge(in.getPCNode(), assignee, PDGEdgeType.IMPLICIT);
        return in;
    }

    @Override
    protected PDGContext flowComparison(SSAComparisonInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode v0 = findOrCreateUseNode(i, 0);
        PDGNode v1 = findOrCreateUseNode(i, 1);
        PDGNode assignee = findOrCreateDefNode(i);

        addEdge(v0, assignee, PDGEdgeType.EXP);
        addEdge(v1, assignee, PDGEdgeType.EXP);

        PDGContext in = confluence(previousItems, current);
        addEdge(in.getPCNode(), assignee, PDGEdgeType.IMPLICIT);
        return in;
    }

    @Override
    protected PDGContext flowConversion(SSAConversionInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        PDGNode converted = findOrCreateUseNode(i, 0);
        PDGNode result = findOrCreateDefNode(i);

        addEdge(converted, result, PDGEdgeType.COPY);

        PDGContext in = confluence(previousItems, current);
        addEdge(in.getPCNode(), result, PDGEdgeType.IMPLICIT);
        return in;
    }

    @Override
    protected PDGContext flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Merge exceptions
        PDGNode caughtEx = findOrCreateDefNode(i);
        for (PDGContext fact : previousItems) {
            PDGNode inEx = fact.getExceptionNode();
            addEdge(inEx, caughtEx, PDGEdgeType.MERGE);
        }

        return confluence(previousItems, current);
    }

    @Override
    protected PDGContext flowGetStatic(SSAGetInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PDGContext flowInstanceOf(SSAInstanceofInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PDGContext flowPhi(SSAPhiInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PDGContext flowPutStatic(SSAPutInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PDGContext flowUnaryNegation(SSAUnaryOpInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowArrayLength(SSAArrayLengthInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowArrayLoad(SSAArrayLoadInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowArrayStore(SSAArrayStoreInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowCheckCast(SSACheckCastInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowGetField(SSAGetInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowInvokeInterface(SSAInvokeInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowInvokeSpecial(SSAInvokeInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowInvokeStatic(SSAInvokeInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowInvokeVirtual(SSAInvokeInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowGoto(SSAGotoInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowLoadMetadata(SSALoadMetadataInstruction i,
                                    Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowMonitor(SSAMonitorInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
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
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PDGContext> flowThrow(SSAThrowInstruction i, Set<PDGContext> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
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
}
