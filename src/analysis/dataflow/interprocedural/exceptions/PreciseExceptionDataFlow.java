package analysis.dataflow.interprocedural.exceptions;

import java.util.Map;
import java.util.Set;

import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
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

public class PreciseExceptionDataFlow extends InterproceduralDataFlow<PreciseExceptionAbsVal> {

    public PreciseExceptionDataFlow(CGNode currentNode, CallGraph cg, PointsToGraph ptg) {
        super(currentNode, cg, ptg);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> call(SSAInvokeInstruction instruction,
            Set<PreciseExceptionAbsVal> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PreciseExceptionAbsVal confluence(Set<PreciseExceptionAbsVal> items) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected void postBasicBlock(Set<PreciseExceptionAbsVal> inItems,
            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock justProcessed,
            Map<Integer, PreciseExceptionAbsVal> outItems) {
        // TODO Auto-generated method stub

    }

    @Override
    protected PreciseExceptionAbsVal flowBinaryOp(SSABinaryOpInstruction i, Set<PreciseExceptionAbsVal> previousItems,
            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PreciseExceptionAbsVal flowComparison(SSAComparisonInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PreciseExceptionAbsVal flowConversion(SSAConversionInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PreciseExceptionAbsVal flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PreciseExceptionAbsVal flowGetStatic(SSAGetInstruction i, Set<PreciseExceptionAbsVal> previousItems,
            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PreciseExceptionAbsVal flowInstanceOf(SSAInstanceofInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PreciseExceptionAbsVal flowPhi(SSAPhiInstruction i, Set<PreciseExceptionAbsVal> previousItems,
            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PreciseExceptionAbsVal flowPutStatic(SSAPutInstruction i, Set<PreciseExceptionAbsVal> previousItems,
            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PreciseExceptionAbsVal flowUnaryNegation(SSAUnaryOpInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowArrayLength(SSAArrayLengthInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowArrayLoad(SSAArrayLoadInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowArrayStore(SSAArrayStoreInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowCheckCast(SSACheckCastInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowConditionalBranch(SSAConditionalBranchInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowGetField(SSAGetInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowInvokeInterface(SSAInvokeInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowInvokeSpecial(SSAInvokeInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowInvokeStatic(SSAInvokeInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowInvokeVirtual(SSAInvokeInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowGoto(SSAGotoInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowLoadMetadata(SSALoadMetadataInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowMonitor(SSAMonitorInstruction i,
            Set<PreciseExceptionAbsVal> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowNewArray(SSANewInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        // By default this throws errors, but we are not tracking errors

        // Also throws NegativeArraySizeException, check if the size (for all
        // dimensions) is a constant and positive
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowNewObject(SSANewInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        // By default this throws errors, but we are not tracking errors
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowPutField(SSAPutInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowReturn(SSAReturnInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowSwitch(SSASwitchInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowThrow(SSAThrowInstruction i,
            Set<PreciseExceptionAbsVal> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }
}
