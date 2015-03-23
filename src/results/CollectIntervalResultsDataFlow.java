package results;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import analysis.AnalysisUtil;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.interprocedural.interval.IntervalAbsVal;
import analysis.dataflow.interprocedural.interval.IntervalResults;
import analysis.dataflow.util.Unit;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
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
import com.ibm.wala.util.CancelException;

public class CollectIntervalResultsDataFlow extends InstructionDispatchDataFlow<Unit> {

    private final IntervalResults interval;
    private final PointsToGraph g;
    private final CollectedResults collected;
    private final Set<Context> allContexts;
    private final IMethod currentMethod;

    public CollectIntervalResultsDataFlow(IntervalResults interval, PointsToGraph g, CollectedResults collected,
                                          Set<Context> allContexts, IMethod m) {
        super(true);
        this.interval = interval;
        this.g = g;
        this.collected = collected;
        this.allContexts = allContexts;
        this.currentMethod = m;
    }

    @Override
    protected Unit flowBinaryOp(SSABinaryOpInstruction i, Set<Unit> previousItems,
                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        this.recordPossibleZeroIntervalForDef(i);
        return Unit.VALUE;
    }

    @Override
    protected Unit flowComparison(SSAComparisonInstruction i, Set<Unit> previousItems,
                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    @Override
    protected Unit flowConversion(SSAConversionInstruction i, Set<Unit> previousItems,
                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    @Override
    protected Unit flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<Unit> previousItems,
                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    @Override
    protected Unit flowGetStatic(SSAGetInstruction i, Set<Unit> previousItems,
                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        this.recordPossibleZeroIntervalForDef(i);
        return Unit.VALUE;
    }

    @Override
    protected Unit flowInstanceOf(SSAInstanceofInstruction i, Set<Unit> previousItems,
                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    @Override
    protected Unit flowPhi(SSAPhiInstruction i, Set<Unit> previousItems,
                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        this.recordPossibleZeroIntervalForDef(i);
        return Unit.VALUE;
    }

    @Override
    protected Unit flowPutStatic(SSAPutInstruction i, Set<Unit> previousItems,
                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    @Override
    protected Unit flowUnaryNegation(SSAUnaryOpInstruction i, Set<Unit> previousItems,
                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        this.recordPossibleZeroIntervalForDef(i);
        return Unit.VALUE;
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayLength(SSAArrayLengthInstruction i, Set<Unit> previousItems,
                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                        ISSABasicBlock current) {
        this.recordPossibleZeroIntervalForDef(i);
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayLoad(SSAArrayLoadInstruction i, Set<Unit> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        this.recordPossibleZeroIntervalForDef(i);
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayStore(SSAArrayStoreInstruction i, Set<Unit> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowBinaryOpWithException(SSABinaryOpInstruction i, Set<Unit> previousItems,
                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                  ISSABasicBlock current) {
        this.recordPossibleZeroIntervalForDef(i);
        this.recordPossibleArithmeticException(i);
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowCheckCast(SSACheckCastInstruction i, Set<Unit> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                                              Set<Unit> previousItems,
                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                              ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowGetField(SSAGetInstruction i, Set<Unit> previousItems,
                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                     ISSABasicBlock current) {
        this.recordPossibleZeroIntervalForDef(i);
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeInterface(SSAInvokeInstruction i, Set<Unit> previousItems,
                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                            ISSABasicBlock current) {
        this.recordPossibleZeroIntervalForDef(i);
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeSpecial(SSAInvokeInstruction i, Set<Unit> previousItems,
                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                          ISSABasicBlock current) {
        this.recordPossibleZeroIntervalForDef(i);
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeStatic(SSAInvokeInstruction i, Set<Unit> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        this.recordPossibleZeroIntervalForDef(i);
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeVirtual(SSAInvokeInstruction i, Set<Unit> previousItems,
                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                          ISSABasicBlock current) {
        this.recordPossibleZeroIntervalForDef(i);
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowGoto(SSAGotoInstruction i, Set<Unit> previousItems,
                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                 ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowLoadMetadata(SSALoadMetadataInstruction i, Set<Unit> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowMonitor(SSAMonitorInstruction i, Set<Unit> previousItems,
                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                    ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowNewArray(SSANewInstruction i, Set<Unit> previousItems,
                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                     ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowNewObject(SSANewInstruction i, Set<Unit> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowPutField(SSAPutInstruction i, Set<Unit> previousItems,
                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                     ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowReturn(SSAReturnInstruction i, Set<Unit> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowSwitch(SSASwitchInstruction i, Set<Unit> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowThrow(SSAThrowInstruction i, Set<Unit> previousItems,
                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                  ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowEmptyBlock(Set<Unit> inItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected void post(IR ir) {
        // Nothing to do
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        return false;
    }

    private Set<CGNode> getAllCGNodes() {
        Set<CGNode> allNodes = new HashSet<>();
        for (Context c : this.allContexts) {
            try {
                CGNode n = g.getCallGraph().findOrCreateNode(currentMethod, c);
                allNodes.add(n);
            }
            catch (CancelException e) {
                throw new RuntimeException(e);
            }
        }
        return allNodes;
    }

    /**
     * Check whether the given instruction defines a local variable with an interval that contains zero and record the
     * result.
     *
     * @param i instruction that may define a primitive value
     */
    private void recordPossibleZeroIntervalForDef(SSAInstruction i) {
        if (!i.hasDef()) {
            // no def here
            return;
        }
        int def = i.getDef();
        collected.recordPossibleZeroInterval();
        for (CGNode n : getAllCGNodes()) {
            IntervalAbsVal inter = interval.getLocalIntervalAfter(def, i, n);
            if (inter != null && inter.containsZero()) {
                collected.recordZeroInterval();
                break;
            }
        }
    }

    /**
     * Check whether the given instruction could throw an arithmetic exception and record the result.
     *
     * @param i instruction that may define a primitive value
     */
    private void recordPossibleArithmeticException(SSABinaryOpInstruction i) {
        int use = i.getUse(1);
        collected.recordPossibleArithmeticException();
        for (CGNode n : getAllCGNodes()) {
            IntervalAbsVal inter = interval.getLocalIntervalBefore(use, i, n);
            if (inter != null && inter.containsZero()) {
                collected.recordArithmeticException();
                return;
            }
        }
    }

    /**
     * Run the dataflow
     */
    void run() {
        IR ir = AnalysisUtil.getIR(currentMethod);
        assert ir != null;
        this.dataflow(ir);
    }
}
