package analysis.dataflow.flowsensitizer;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import analysis.dataflow.InstructionDispatchDataFlow;

import com.ibm.wala.cfg.ControlFlowGraph;
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

public abstract class MostlyBoringDataFlow<T> extends InstructionDispatchDataFlow<T> {

    public MostlyBoringDataFlow(boolean forward) {
        super(forward);
    }

    protected abstract T joinTs(Collection<T> previousItems);

    protected final static <T> Map<ISSABasicBlock, T> sameForAllSuccessors(Iterator<ISSABasicBlock> succNodes, T fact) {
        Map<ISSABasicBlock, T> m = new HashMap<>();

        while (succNodes.hasNext()) {
            ISSABasicBlock succ = succNodes.next();
            m.put(succ, fact);
        }

        return m;
    }

    @Override
    protected T flowBinaryOp(SSABinaryOpInstruction i, Set<T> previousItems,
                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinTs(previousItems);
    }

    @Override
    protected T flowComparison(SSAComparisonInstruction i, Set<T> previousItems,
                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinTs(previousItems);
    }

    @Override
    protected T flowConversion(SSAConversionInstruction i, Set<T> previousItems,
                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinTs(previousItems);
    }

    @Override
    protected T flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<T> previousItems,
                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinTs(previousItems);
    }

    @Override
    protected T flowGetStatic(SSAGetInstruction i, Set<T> previousItems,
                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinTs(previousItems);
    }

    @Override
    protected T flowInstanceOf(SSAInstanceofInstruction i, Set<T> previousItems,
                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinTs(previousItems);
    }

    @Override
    protected T flowPhi(SSAPhiInstruction i, Set<T> previousItems,
                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinTs(previousItems);
    }

    @Override
    protected T flowPutStatic(SSAPutInstruction i, Set<T> previousItems,
                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinTs(previousItems);
    }

    @Override
    protected T flowUnaryNegation(SSAUnaryOpInstruction i, Set<T> previousItems,
                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinTs(previousItems);
    }

    @Override
    protected Map<ISSABasicBlock, T> flowArrayLength(SSAArrayLengthInstruction i, Set<T> previousItems,
                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                     ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowArrayLoad(SSAArrayLoadInstruction i, Set<T> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowArrayStore(SSAArrayStoreInstruction i, Set<T> previousItems,
                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                    ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowBinaryOpWithException(SSABinaryOpInstruction i, Set<T> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowCheckCast(SSACheckCastInstruction i, Set<T> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowConditionalBranch(SSAConditionalBranchInstruction i, Set<T> previousItems,
                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                           ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowGetField(SSAGetInstruction i, Set<T> previousItems,
                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                  ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowInvokeInterface(SSAInvokeInstruction i, Set<T> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowInvokeSpecial(SSAInvokeInstruction i, Set<T> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowInvokeStatic(SSAInvokeInstruction i, Set<T> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowInvokeVirtual(SSAInvokeInstruction i, Set<T> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowGoto(SSAGotoInstruction i, Set<T> previousItems,
                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                              ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowLoadMetadata(SSALoadMetadataInstruction i, Set<T> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowMonitor(SSAMonitorInstruction i, Set<T> previousItems,
                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                 ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowNewArray(SSANewInstruction i, Set<T> previousItems,
                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                  ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowNewObject(SSANewInstruction i, Set<T> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowPutField(SSAPutInstruction i, Set<T> previousItems,
                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                  ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowReturn(SSAReturnInstruction i, Set<T> previousItems,
                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowSwitch(SSASwitchInstruction i, Set<T> previousItems,
                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowThrow(SSAThrowInstruction i, Set<T> previousItems,
                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                               ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, T> flowEmptyBlock(Set<T> inItems,
                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                    ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(inItems));
    }

    @Override
    protected void post(IR ir) {
        // XXX: Everyone else leaves this blank, so should we? What is it for?
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        // XXX: Everyone else returns false, so should we? How do I implement it? What is it for?
        return false;
    }

}
