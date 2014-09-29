package analysis.pointer.registrar;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.pointer.statements.ProgramPoint;

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

/**
 * Data-flow that computes program points
 */
public class ComputeProgramPointsDataflow extends InstructionDispatchDataFlow<ProgramPoint> {

    private final IR ir;
    private final Map<OrderedPair<ISSABasicBlock, SSAInstruction>, ProgramPoint> memoizedProgramPoints;
    private final Map<OrderedPair<ISSABasicBlock, SSAInstruction>, ProgramPoint> mostRecentProgramPoint;
    private final Set<ProgramPoint> modifiesPointsToGraph;
    private final StatementRegistrar registrar;
    private final ReferenceVariableFactory rvFactory;

    public ComputeProgramPointsDataflow(IR ir, StatementRegistrar registrar,
                                        ReferenceVariableFactory rvFactory) {
        super(true);
        this.ir = ir;
        this.registrar = registrar;
        this.rvFactory = rvFactory;

        this.memoizedProgramPoints = new HashMap<>();
        this.mostRecentProgramPoint = new HashMap<>();
        this.modifiesPointsToGraph = new HashSet<>();
    }

    /**
     * Perform the dataflow
     */
    protected void dataflow() {
        dataflow(ir);
    }

    @Override
    protected void post(IR ir) {
        // record the program point successors.

        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
        // iterate over every basic block
        for (ISSABasicBlock bb : cfg) {
            // go other every instruction in the basic block, and add a successor edge.
            int firstInst = bb.getFirstInstructionIndex();
            if (firstInst >= 0) {
                // XXX ANDREW: is this how the instruction indices work?
                for (int i = firstInst; i < bb.getLastInstructionIndex(); i++) {
                    // add a successor edge from i to i+1, if needed
                    OrderedPair<ISSABasicBlock, SSAInstruction> memoKey = new OrderedPair<>(bb, ir.getInstructions()[i]);
                    ProgramPoint pp = this.mostRecentProgramPoint.get(memoKey);
                    ProgramPoint nextPP = this.mostRecentProgramPoint.get(new OrderedPair<>(bb,
                                                                                            ir.getInstructions()[i + 1]));
                    // add nextPP as a successor to pp
                    addSucc(pp, nextPP);
                }
            }

            // add successor edges from the program point of the last instruction of the basic block to
            // the program point of the first instruction of the successor basic blocks.
            ProgramPoint lastPPofBB;
            int lastInst = bb.getLastInstructionIndex();
            if (lastInst >= 0) {
                lastPPofBB = this.mostRecentProgramPoint.get(new OrderedPair<>(bb, ir.getInstructions()[lastInst]));
            }
            else {
                lastPPofBB = this.mostRecentProgramPoint.get(new OrderedPair<>(bb, null));
            }
            for (ISSABasicBlock succBB : cfg.getNormalSuccessors(bb)) {
                addSucc(lastPPofBB, bbEntryProgramPoint(succBB, true));
            }
            for (ISSABasicBlock succBB : cfg.getExceptionalSuccessors(bb)) {
                addSucc(lastPPofBB, bbEntryProgramPoint(succBB, false));
            }
        }

    }

    private void addSucc(ProgramPoint pp, ProgramPoint succPP) {
        if (pp.equals(succPP)) {
            // nothing to do
            return;
        }
        // XXX TODO add the actual successor relation, once the interface of ProgramPoint has been determined.
    }

    /**
     * Get the ProgramPoint for the first instruction for bb. If bb is the unique exit basic block, then this method
     * returns either the normal exit ProgramPoint or the exception exit ProgramPoint from the method summary, depending
     * on the value of isExceptionEdge.
     *
     * @param bb
     * @param isExceptionEdge
     * @return
     */
    private ProgramPoint bbEntryProgramPoint(ISSABasicBlock bb, boolean isExceptionEdge) {
        int firstInst = bb.getFirstInstructionIndex();
        if (firstInst >= 0) {
            return this.mostRecentProgramPoint.get(new OrderedPair<>(bb, ir.getInstructions()[firstInst]));
        }
        else if (bb.isExitBlock()) {
            MethodSummaryNodes summary = this.registrar.findOrCreateMethodSummary(this.ir.getMethod(),
                                                                                  this.rvFactory);
            if (isExceptionEdge) {
                return summary.getExceptionExitPP();
            }
            else {
                return summary.getNormalExitPP();
            }
        }
        else {
            // an empty non-exit block. Can this case actually happen? XXX
            return this.mostRecentProgramPoint.get(new OrderedPair<>(bb, null));
        }

    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flow(Set<ProgramPoint> inItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        // if we are the entry block, then the program point
        // is the entry ProgramPoint from the method summary.
        if (current.isEntryBlock()) {
            assert inItems.isEmpty();
            MethodSummaryNodes summary = this.registrar.findOrCreateMethodSummary(this.ir.getMethod(),
                                                                                  this.rvFactory);
            inItems = Collections.singleton(summary.getEntryPP());
        }
        return super.flow(inItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowEmptyBlock(Set<ProgramPoint> inItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        assert !inItems.isEmpty();

        // we have an empty block, so we can't associate a ProgramPoint with an instruction.
        // We will associate it with a "null" instruction.
        ProgramPoint pp = flowImpl(false, null, inItems, cfg, current);
        return factToMap(pp, current, cfg);
    }

    @Override
    protected ProgramPoint flowBinaryOp(SSABinaryOpInstruction i, Set<ProgramPoint> previousItems,
                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPoint flowComparison(SSAComparisonInstruction i, Set<ProgramPoint> previousItems,
                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPoint flowConversion(SSAConversionInstruction i, Set<ProgramPoint> previousItems,
                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPoint flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<ProgramPoint> previousItems,
                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                  ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPoint flowGetStatic(SSAGetInstruction i, Set<ProgramPoint> previousItems,
                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPoint flowInstanceOf(SSAInstanceofInstruction i, Set<ProgramPoint> previousItems,
                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPoint flowPhi(SSAPhiInstruction i, Set<ProgramPoint> previousItems,
                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPoint flowPutStatic(SSAPutInstruction i, Set<ProgramPoint> previousItems,
                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPoint flowUnaryNegation(SSAUnaryOpInstruction i, Set<ProgramPoint> previousItems,
                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                             ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowArrayLength(SSAArrayLengthInstruction i,
                                                                Set<ProgramPoint> previousItems,
                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowArrayLoad(SSAArrayLoadInstruction i,
                                                              Set<ProgramPoint> previousItems,
                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                              ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowArrayStore(SSAArrayStoreInstruction i,
                                                               Set<ProgramPoint> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                                                          Set<ProgramPoint> previousItems,
                                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                          ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowCheckCast(SSACheckCastInstruction i,
                                                              Set<ProgramPoint> previousItems,
                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                              ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                                                      Set<ProgramPoint> previousItems,
                                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                      ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowGetField(SSAGetInstruction i, Set<ProgramPoint> previousItems,
                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                             ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowInvokeInterface(SSAInvokeInstruction i,
                                                                    Set<ProgramPoint> previousItems,
                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                    ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowInvokeSpecial(SSAInvokeInstruction i,
                                                                  Set<ProgramPoint> previousItems,
                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                  ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowInvokeStatic(SSAInvokeInstruction i,
                                                                 Set<ProgramPoint> previousItems,
                                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                 ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowInvokeVirtual(SSAInvokeInstruction i,
                                                                  Set<ProgramPoint> previousItems,
                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                  ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowGoto(SSAGotoInstruction i, Set<ProgramPoint> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowLoadMetadata(SSALoadMetadataInstruction i,
                                                                 Set<ProgramPoint> previousItems,
                                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                 ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowMonitor(SSAMonitorInstruction i, Set<ProgramPoint> previousItems,
                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                            ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowNewArray(SSANewInstruction i, Set<ProgramPoint> previousItems,
                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                             ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowNewObject(SSANewInstruction i, Set<ProgramPoint> previousItems,
                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                              ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowPutField(SSAPutInstruction i, Set<ProgramPoint> previousItems,
                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                             ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowReturn(SSAReturnInstruction i, Set<ProgramPoint> previousItems,
                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                           ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowSwitch(SSASwitchInstruction i, Set<ProgramPoint> previousItems,
                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                           ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPoint> flowThrow(SSAThrowInstruction i, Set<ProgramPoint> previousItems,
                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                          ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        return false;
    }

    protected Map<ISSABasicBlock, ProgramPoint> flowBranchImpl(boolean mayChangePointsToGraph, SSAInstruction i,
                                                         Set<ProgramPoint> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        return factToMap(flowImpl(mayChangePointsToGraph, i, previousItems, cfg, current), current, cfg);

    }

    protected ProgramPoint flowImpl(boolean mayChangePointsToGraph, SSAInstruction i,
                                             Set<ProgramPoint> previousItems,
                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                             ISSABasicBlock current) {
        assert !previousItems.isEmpty() : "Empty facts for BB" + current.getNumber() + " IN "
                + PrettyPrinter.methodString(ir.getMethod());

        // i may be null.
        OrderedPair<ISSABasicBlock, SSAInstruction> memoKey = new OrderedPair<>(current, i);

        ProgramPoint pp;
        if (mayChangePointsToGraph || previousItems.size() > 1
                || (previousItems.size() == 1 && this.modifiesPointsToGraph.contains(previousItems.iterator().next()))) {
            // we need a new program point: either this instruction may change the points to graph, or
            // the previous instruction changed the points to graph, or there are multiple distinct
            // predecessor program points.
            pp = getOrCreateProgramPoint(memoKey);
        }
        else {
            assert previousItems.size() == 1;
            pp = previousItems.iterator().next();
        }

        mostRecentProgramPoint.put(memoKey, pp);

        if (mayChangePointsToGraph) {
            this.modifiesPointsToGraph.add(pp);
        }

        return pp;

    }

    private ProgramPoint getOrCreateProgramPoint(OrderedPair<ISSABasicBlock, SSAInstruction> memoKey) {
        ProgramPoint pp;
        if (memoizedProgramPoints.containsKey(memoKey)) {
            pp = memoizedProgramPoints.get(memoKey);
        }
        else {
            pp = new ProgramPoint(this.ir.getMethod(), PrettyPrinter.methodString(this.ir.getMethod()) + ":"
                    + memoKey.fst().getNumber() + ":" + memoKey.snd());
            memoizedProgramPoints.put(memoKey, pp);
        }
        return pp;
    }

}
