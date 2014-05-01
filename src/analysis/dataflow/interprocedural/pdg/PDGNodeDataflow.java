package analysis.dataflow.interprocedural.pdg;

import java.util.Map;
import java.util.Set;

import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
import analysis.dataflow.util.VarContext;

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

/**
 * Data-flow that builds up the set of nodes in a program dependence graph.
 */
public class PDGNodeDataflow extends InstructionDispatchDataFlow<VarContext<PDGNode>> {

    private CGNode currentNode;
    private PDGInterproceduralDataFlow interProc;

    public PDGNodeDataflow(CGNode currentNode, PDGInterproceduralDataFlow interProc) {
        super(true);

        this.currentNode = currentNode;
        this.interProc = interProc;
        // TODO Auto-generated constructor stub
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
    protected VarContext<PDGNode> confluence(Set<VarContext<PDGNode>> facts) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<PDGNode> flowBinaryOp(SSABinaryOpInstruction i, Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<PDGNode> flowComparison(SSAComparisonInstruction i, Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<PDGNode> flowConversion(SSAConversionInstruction i, Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<PDGNode> flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<PDGNode> flowGetStatic(SSAGetInstruction i, Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<PDGNode> flowInstanceOf(SSAInstanceofInstruction i, Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<PDGNode> flowPhi(SSAPhiInstruction i, Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<PDGNode> flowPutStatic(SSAPutInstruction i, Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<PDGNode> flowUnaryNegation(SSAUnaryOpInstruction i, Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowArrayLength(SSAArrayLengthInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowArrayLoad(SSAArrayLoadInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowArrayStore(SSAArrayStoreInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowCheckCast(SSACheckCastInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowGetField(SSAGetInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowInvokeInterface(SSAInvokeInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowInvokeSpecial(SSAInvokeInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowInvokeStatic(SSAInvokeInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowInvokeVirtual(SSAInvokeInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowGoto(SSAGotoInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowLoadMetadata(SSALoadMetadataInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowMonitor(SSAMonitorInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowNewArray(SSANewInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowNewObject(SSANewInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowPutField(SSAPutInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowReturn(SSAReturnInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowSwitch(SSASwitchInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<PDGNode>> flowThrow(SSAThrowInstruction i,
                                    Set<VarContext<PDGNode>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Check whether a particular exception type can be thrown on a control flow
     * graph edge from the source to the target
     * 
     * @param exceptionType
     *            type of the exception to check for
     * @param source
     *            source of the exception
     * @param target
     *            target of the exception
     * @return true if an exception of type <code>exceptionType</code> is
     *         propagated on the edge from <code>source</code> to
     *         <code>target</code>
     */
    private boolean canThrowExceptionType(TypeReference exceptionType, ISSABasicBlock source, ISSABasicBlock target) {
        PreciseExceptionResults pe = interProc.getPreciseExceptionResults();
        return pe.getExceptions(source, target, currentNode).contains(exceptionType);
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
}
