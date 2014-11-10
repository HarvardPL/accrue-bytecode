package analysis.pointer.registrar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.ClassInitFinder;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.pointer.statements.ProgramPoint;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
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
 * Data-flow that computes program points. We also compute intraprocedurally the set of classes that have definitely
 * been initalized at certain instructions, so that we can reduce the number of class initialization statements we have
 * to generate.
 */
public class ComputeProgramPointsDataflow extends InstructionDispatchDataFlow<ProgramPointFacts> {

    private final IR ir;
    private final Map<OrderedPair<ISSABasicBlock, SSAInstruction>, ProgramPoint> memoizedProgramPoint;
    private final Map<OrderedPair<ISSABasicBlock, SSAInstruction>, ProgramPointFacts> mostRecentProgramPointFacts;
    private final StatementRegistrar registrar;
    private final ReferenceVariableFactory rvFactory;

    private boolean finishedDataflow = false;
    public ComputeProgramPointsDataflow(IR ir, StatementRegistrar registrar,
                                        ReferenceVariableFactory rvFactory) {
        super(true);
        this.ir = ir;
        this.registrar = registrar;
        this.rvFactory = rvFactory;

        this.memoizedProgramPoint = new HashMap<>();
        this.mostRecentProgramPointFacts = new HashMap<>();
    }

    /**
     * Perform the dataflow
     */
    protected void dataflow() {
        dataflow(ir);
        this.finishedDataflow = true;
    }

    @Override
    protected void post(IR ir) {

        // record the program point successors.

        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
        // iterate over every basic block
        for (ISSABasicBlock bb : cfg) {
            // go other every instruction in the basic block, and add a successor edge.
            ProgramPointFacts previousPP = null;
            for (SSAInstruction current : bb){
                ProgramPointFacts currentPP = this.mostRecentProgramPointFacts.get(new OrderedPair<>(bb, current));
                if (previousPP != null) {
                    addSucc(previousPP.pp, currentPP.pp);
                }
                previousPP = currentPP;
            }

            // add successor edges from the program point of the last instruction of the basic block to
            // the program point of the first instruction of the successor basic blocks.
            SSAInstruction last = getLastInstruction(bb);
            ProgramPoint lastPPofBB = this.mostRecentProgramPointFacts.get(new OrderedPair<>(bb, last)).pp;
            for (ISSABasicBlock succBB : cfg.getNormalSuccessors(bb)) {
                addSucc(lastPPofBB, bbEntryProgramPoint(succBB, false));
            }
            for (ISSABasicBlock succBB : cfg.getExceptionalSuccessors(bb)) {
                addSucc(lastPPofBB, bbEntryProgramPoint(succBB, true));
            }
        }

    }

    private static void addSucc(ProgramPoint pp, ProgramPoint succPP) {
        if (pp.equals(succPP)) {
            // nothing to do
            return;
        }
        pp.addSucc(succPP);
    }

    /**
     * Get the ProgramPointFacts for the first instruction for bb. If bb is the unique exit basic block, then this method
     * returns either the normal exit ProgramPoint or the exception exit ProgramPoint from the method summary, depending
     * on the value of isExceptionEdge.
     *
     * @param bb
     * @param isExceptionEdge
     * @return
     */
    private ProgramPoint bbEntryProgramPoint(ISSABasicBlock bb, boolean isExceptionEdge) {
        if (bb.isExitBlock()) {
            MethodSummaryNodes summary = this.registrar.findOrCreateMethodSummary(this.ir.getMethod(),
                                                                                  this.rvFactory);
            if (isExceptionEdge) {
                return summary.getExceptionExitPP();
            }
            return summary.getNormalExitPP();
        }

        // Not the exit block
        SSAInstruction firstInst;
        if (!bb.iterator().hasNext()) {
            // Empty block, pass in null as the instruction @see flowEmptyBlock
            firstInst = null;
        }
        else {
            firstInst = bb.iterator().next();
        }
        return this.mostRecentProgramPointFacts.get(new OrderedPair<>(bb, firstInst)).pp;
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flow(Set<ProgramPointFacts> inItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        // if we are the entry block, then the program point
        // is the entry ProgramPoint from the method summary.
        if (current.isEntryBlock()) {
            assert inItems.isEmpty();
            IMethod meth = this.ir.getMethod();
            MethodSummaryNodes summary = this.registrar.findOrCreateMethodSummary(meth,
                                                                                  this.rvFactory);
            ProgramPointFacts initialFacts = new ProgramPointFacts(summary.getEntryPP(),
                                                                   Collections.<IClass> emptySet(),
                                                                   initializedClassesForMethod(meth));
            inItems = Collections.singleton(initialFacts);
        }
        return super.flow(inItems, cfg, current);
    }

    /**
     * Return the classes that we know must have been initialized by the time this method is called.
     */
    private static Set<IClass> initializedClassesForMethod(IMethod meth) {
        Set<IClass> s = new HashSet<>();
        IClass declaringClass = meth.getDeclaringClass();
        if (!meth.isInit() && !meth.isStatic()) {
            // a non-static non-intialization method,
            // so all the superclasses and superinterfaces are initialized.
            ArrayList<IClass> q = new ArrayList<>();
            q.add(declaringClass);
            while (!q.isEmpty()) {
                IClass c = q.remove(q.size() - 1);
                if (s.add(c)) {
                    // add the superclasses and interfaces to the q
                    IClass sup = c.getSuperclass();
                    if (sup != null) {
                        q.add(sup);
                    }
                    for (IClass in : c.getAllImplementedInterfaces()) {
                        q.add(in);
                    }
                }
            }
        }
        s.add(declaringClass);
        if (!declaringClass.equals(AnalysisUtil.getObjectClass())) {
            // object is initialized
            s.add(AnalysisUtil.getObjectClass());
        }
        return s;
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowEmptyBlock(Set<ProgramPointFacts> inItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        assert !inItems.isEmpty();

        // we have an empty block, so we can't associate a ProgramPoint with an instruction.
        // We will associate it with a "null" instruction.
        ProgramPointFacts pp = flowImpl(false, null, inItems, cfg, current);
        return factToMap(pp, current, cfg);
    }

    @Override
    protected ProgramPointFacts flowBinaryOp(SSABinaryOpInstruction i, Set<ProgramPointFacts> previousItems,
                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPointFacts flowComparison(SSAComparisonInstruction i, Set<ProgramPointFacts> previousItems,
                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPointFacts flowConversion(SSAConversionInstruction i, Set<ProgramPointFacts> previousItems,
                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPointFacts flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                                       Set<ProgramPointFacts> previousItems,
                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                  ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPointFacts flowGetStatic(SSAGetInstruction i, Set<ProgramPointFacts> previousItems,
                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPointFacts flowInstanceOf(SSAInstanceofInstruction i, Set<ProgramPointFacts> previousItems,
                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPointFacts flowPhi(SSAPhiInstruction i, Set<ProgramPointFacts> previousItems,
                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPointFacts flowPutStatic(SSAPutInstruction i, Set<ProgramPointFacts> previousItems,
                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected ProgramPointFacts flowUnaryNegation(SSAUnaryOpInstruction i, Set<ProgramPointFacts> previousItems,
                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                             ISSABasicBlock current) {
        return flowImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowArrayLength(SSAArrayLengthInstruction i,
                                                                     Set<ProgramPointFacts> previousItems,
                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowArrayLoad(SSAArrayLoadInstruction i,
                                                                   Set<ProgramPointFacts> previousItems,
                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                              ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowArrayStore(SSAArrayStoreInstruction i,
                                                                    Set<ProgramPointFacts> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                                                               Set<ProgramPointFacts> previousItems,
                                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                          ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowCheckCast(SSACheckCastInstruction i,
                                                                   Set<ProgramPointFacts> previousItems,
                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                              ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                                                           Set<ProgramPointFacts> previousItems,
                                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                      ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowGetField(SSAGetInstruction i,
                                                                  Set<ProgramPointFacts> previousItems,
                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                             ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowInvokeInterface(SSAInvokeInstruction i,
                                                                         Set<ProgramPointFacts> previousItems,
                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                    ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowInvokeSpecial(SSAInvokeInstruction i,
                                                                       Set<ProgramPointFacts> previousItems,
                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                  ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowInvokeStatic(SSAInvokeInstruction i,
                                                                      Set<ProgramPointFacts> previousItems,
                                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                 ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowInvokeVirtual(SSAInvokeInstruction i,
                                                                       Set<ProgramPointFacts> previousItems,
                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                  ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowGoto(SSAGotoInstruction i,
                                                              Set<ProgramPointFacts> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowLoadMetadata(SSALoadMetadataInstruction i,
                                                                      Set<ProgramPointFacts> previousItems,
                                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                 ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowMonitor(SSAMonitorInstruction i,
                                                                 Set<ProgramPointFacts> previousItems,
                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                            ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowNewArray(SSANewInstruction i,
                                                                  Set<ProgramPointFacts> previousItems,
                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                             ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowNewObject(SSANewInstruction i,
                                                                   Set<ProgramPointFacts> previousItems,
                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                              ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowPutField(SSAPutInstruction i,
                                                                  Set<ProgramPointFacts> previousItems,
                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                             ISSABasicBlock current) {
        return flowBranchImpl(true, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowReturn(SSAReturnInstruction i,
                                                                Set<ProgramPointFacts> previousItems,
                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                           ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowSwitch(SSASwitchInstruction i,
                                                                Set<ProgramPointFacts> previousItems,
                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                           ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ProgramPointFacts> flowThrow(SSAThrowInstruction i,
                                                               Set<ProgramPointFacts> previousItems,
                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                          ISSABasicBlock current) {
        return flowBranchImpl(false, i, previousItems, cfg, current);
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        return false;
    }

    protected Map<ISSABasicBlock, ProgramPointFacts> flowBranchImpl(boolean mayChangeFlowSensPointsToGraph,
                                                                    SSAInstruction i,
                                                                    Set<ProgramPointFacts> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        return factToMap(flowImpl(mayChangeFlowSensPointsToGraph, i, previousItems, cfg, current), current, cfg);

    }

    protected ProgramPointFacts flowImpl(boolean mayChangePointsToGraph, SSAInstruction i,
                                         Set<ProgramPointFacts> previousItems,
                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                             ISSABasicBlock current) {
        assert !previousItems.isEmpty() : "Empty facts for BB" + current.getNumber() + " IN "
                + PrettyPrinter.methodString(ir.getMethod());

        // i may be null.
        OrderedPair<ISSABasicBlock, SSAInstruction> memoKey = new OrderedPair<>(current, i);

        // Compute the set of classes that are definitely initialized.
        Set<IClass> definitelyInitializedClassesBeforeIns;
        if (previousItems.size() > 1) {
            // compute the intersection
            Set<IClass> s = null;
            for (ProgramPointFacts it : previousItems) {
                if (s == null) {
                    s = new HashSet<>();
                    s.addAll(it.definitelyInitClassesAfterIns);
                }
                else {
                    s.retainAll(it.definitelyInitClassesAfterIns);
                }
            }
            definitelyInitializedClassesBeforeIns = s;
        }
        else {
            definitelyInitializedClassesBeforeIns = new HashSet<>(previousItems.iterator().next().definitelyInitClassesAfterIns);
        }

        // now this instruction itself may cause some classes to be initialized.
        Set<IClass> definitelyInitializedClassesAfterIns;

        boolean callsClassInitializer = false;
        IClass reqInit = ClassInitFinder.getRequiredInitializedClasses(i);
        if (reqInit != null && !definitelyInitializedClassesBeforeIns.contains(reqInit)) {
            definitelyInitializedClassesAfterIns = new HashSet<>(definitelyInitializedClassesBeforeIns);
            definitelyInitializedClassesAfterIns.add(reqInit);
            if (!ClassInitFinder.getClassInitializersForClass(reqInit).isEmpty()) {
                // because this instruction will definitely cause a class initializer method to be called,
                // it can change the points to graph
                mayChangePointsToGraph = true;
                callsClassInitializer = true;
            }
        }
        else {
            definitelyInitializedClassesAfterIns = definitelyInitializedClassesBeforeIns;
        }

        if (i != null) {
            if (!mayChangePointsToGraph) {
                // see if there are string literals
                for (int j = 0; j < i.getNumberOfUses(); j++) {
                    int use = i.getUse(j);
                    if (ir.getSymbolTable().isStringConstant(use) || ir.getSymbolTable().isNullConstant(use)) {
                        mayChangePointsToGraph = true;
                        break;
                    }
                }
            }
            if (!mayChangePointsToGraph) {
                // see if there are generated exceptions
                if (!PreciseExceptionResults.implicitExceptions(i).isEmpty()) {
                    mayChangePointsToGraph = true;
                }
            }
        }

        // Compute the program point.
        ProgramPoint pp;
        if (mayChangePointsToGraph || previousItems.size() > 1) {
            // we need a new program point: either this instruction may change the points to graph, or
            // the previous instruction changed the points to graph, or there are multiple distinct
            // predecessor program points.
            pp = getOrCreateProgramPoint(memoKey);
        }
        else {
            assert previousItems.size() == 1;
            pp = previousItems.iterator().next().pp;
        }

        ProgramPointFacts ppf = new ProgramPointFacts(pp,
                                                      definitelyInitializedClassesBeforeIns,
                                                      definitelyInitializedClassesAfterIns);
        this.mostRecentProgramPointFacts.put(memoKey, ppf);

        return ppf;

    }

    private ProgramPoint getOrCreateProgramPoint(OrderedPair<ISSABasicBlock, SSAInstruction> memoKey) {
        ProgramPoint pp;
        if (memoizedProgramPoint.containsKey(memoKey)) {
            pp = memoizedProgramPoint.get(memoKey);
        }
        else {
            String debugString = PrettyPrinter.methodString(this.ir.getMethod()) + ":" + memoKey.fst().getNumber()
                    + ":" + memoKey.snd();
            pp = new ProgramPoint(this.ir.getMethod(), debugString);

            memoizedProgramPoint.put(memoKey, pp);
        }
        return pp;
    }

    public ProgramPoint getProgramPoint(SSAInstruction ins, ISSABasicBlock bb) {
        assert finishedDataflow;
        return this.mostRecentProgramPointFacts.get(new OrderedPair<>(bb, ins)).pp;
    }

    public Set<IClass> getInitializedClassesBeforeIns(SSAInstruction ins, ISSABasicBlock bb) {
        assert finishedDataflow;
        return this.mostRecentProgramPointFacts.get(new OrderedPair<>(bb, ins)).definitelyInitClassesBeforeIns;
    }

    /**
     * Try to condense program points pp and pp' if pp' is the only successor to pp, and pp is the only predecessor to
     * pp' and neither of them have a statement that may change the flow-sensitive part of the points to graph.
     *
     * @return int array where ret[0] is the total number of program points before removing, and ret[1] is the number of
     *         program points removed.
     */
    public int[] cleanUpProgramPoints() {
        // try to clean up the program points. Let's first get a reverse mapping, and then check to see if there are any we can merge
        Map<ProgramPoint, Set<ProgramPoint>> preds = new HashMap<>();
        MethodSummaryNodes summary = this.registrar.findOrCreateMethodSummary(this.ir.getMethod(), this.rvFactory);
        int totalProgramPoints = 0;
        {
            Set<ProgramPoint> visited = new HashSet<>();
            ArrayList<ProgramPoint> q = new ArrayList<>();
            q.add(summary.getEntryPP());
            while (!q.isEmpty()) {
                ProgramPoint pp = q.remove(q.size() - 1);
                if (visited.contains(pp)) {
                    continue;
                }
                visited.add(pp);
                for (ProgramPoint succ : pp.succs()) {
                    Set<ProgramPoint> predsForSucc = preds.get(succ);
                    if (predsForSucc == null) {
                        predsForSucc = new HashSet<>();
                        preds.put(succ, predsForSucc);
                    }
                    predsForSucc.add(pp);
                    if (!visited.contains(succ)) {
                        q.add(succ);
                    }
                }
            }
            totalProgramPoints = visited.size();
        }
        int removedPPs = 0;

        // we now have the pred relation.
        // Go through and collapse non-modifying PPs that have only one successor or predecessor
        Set<ProgramPoint> removed = new HashSet<>();
        for (ProgramPoint pp : preds.keySet()) {
            if (this.registrar.getStmtAtPP(pp) != null) {
                // this pp may modify the flow-sensitive part of the points to graph
                continue;
            }
            if (removed.contains(pp)) {
                // this program point has already been removed.
                continue;
            }
            if (pp.isEntrySummaryNode() || pp.isExceptionExitSummaryNode() || pp.isNormalExitSummaryNode()) {
                // don't try to remove summary nodes
                continue;
            }

            Set<ProgramPoint> predSet = preds.get(pp);
            if (predSet.size() == 1) {
                // we have one predecessor
                // merge pp with the predecessor
                ProgramPoint predPP = predSet.iterator().next();
                assert !removed.contains(predPP);
                predSet.clear();

                assert predPP.succs().contains(pp);
                predPP.removeSucc(pp);
                for (ProgramPoint ppSucc : pp.succs()) {
                    assert !removed.contains(ppSucc);
                    // for each successor of pp, remove pp as a predecessor, and add ppPred.
                    assert preds.get(ppSucc) != null && preds.get(ppSucc).contains(pp);
                    preds.get(ppSucc).remove(pp);
                    preds.get(ppSucc).add(predPP);
                    predPP.addSucc(ppSucc);
                }

                removedPPs++;
                removed.add(pp);
                pp.setIsDiscardedProgramPoint();
                predSet.clear();
                assert pp.succs().isEmpty();
                assert preds.get(pp).isEmpty();
                continue;
            }
            if (pp.succs().size() == 1) {
                // we have one successor
                // merge pp with the successor
                ProgramPoint succPP = pp.succs().iterator().next();
                assert !removed.contains(succPP);


                Set<ProgramPoint> succPPpreds = preds.get(succPP);
                assert succPPpreds.contains(pp);
                succPPpreds.remove(pp);
                for (ProgramPoint ppPred : predSet) {
                    assert !removed.contains(ppPred);
                    // for each predecessor of pp, remove pp as a successor, and add ppSucc.
                    ppPred.removeSucc(pp);

                    ppPred.addSucc(succPP);
                    succPPpreds.add(ppPred);
                }

                pp.removeSucc(succPP);
                predSet.clear();
                assert pp.succs().isEmpty();
                assert preds.get(pp).isEmpty();

                removedPPs++;
                removed.add(pp);
                continue;
            }

        }
        return new int[] { totalProgramPoints, removedPPs };
    }

}

class ProgramPointFacts {
    ProgramPoint pp;
    Set<IClass> definitelyInitClassesBeforeIns;
    Set<IClass> definitelyInitClassesAfterIns;

    public ProgramPointFacts(ProgramPoint pp,
                             Set<IClass> definitelyInitClassesBeforeIns,
                             Set<IClass> definitelyInitClassesAfterIns) {
        this.pp = pp;
        this.definitelyInitClassesBeforeIns = definitelyInitClassesBeforeIns;
        this.definitelyInitClassesAfterIns = definitelyInitClassesAfterIns;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((definitelyInitClassesAfterIns == null) ? 0 : definitelyInitClassesAfterIns.hashCode());
        result = prime * result
                + ((definitelyInitClassesBeforeIns == null) ? 0 : definitelyInitClassesBeforeIns.hashCode());
        result = prime * result + ((pp == null) ? 0 : pp.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof ProgramPointFacts)) {
            return false;
        }
        ProgramPointFacts other = (ProgramPointFacts) obj;
        if (definitelyInitClassesAfterIns == null) {
            if (other.definitelyInitClassesAfterIns != null) {
                return false;
            }
        }
        else if (!definitelyInitClassesAfterIns.equals(other.definitelyInitClassesAfterIns)) {
            return false;
        }
        if (definitelyInitClassesBeforeIns == null) {
            if (other.definitelyInitClassesBeforeIns != null) {
                return false;
            }
        }
        else if (!definitelyInitClassesBeforeIns.equals(other.definitelyInitClassesBeforeIns)) {
            return false;
        }
        if (pp == null) {
            if (other.pp != null) {
                return false;
            }
        }
        else if (!pp.equals(other.pp)) {
            return false;
        }
        return true;
    }
}
