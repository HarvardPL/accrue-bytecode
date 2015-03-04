package analysis.dataflow.interprocedural.accessible;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.IntraproceduralDataFlow;
import analysis.dataflow.util.AbstractLocation;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
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

public class AccessibleLocationsDataFlow extends IntraproceduralDataFlow<AbstractLocationSet> {

    public AccessibleLocationsDataFlow(CGNode currentNode, InterproceduralDataFlow<AbstractLocationSet> interProc) {
        super(currentNode, interProc);
    }

    @Override
    public Map<ExitType, AbstractLocationSet> dataflow(AbstractLocationSet initial) {

        IMethod m = currentNode.getMethod();
        if (m.isInit()) {
            // Add the fields of the reciever
            Collection<IField> fields = currentNode.getMethod().getDeclaringClass().getAllFields();
            ProgramPoint entryPP = ptg.getRegistrar().getMethodSummary(m).getEntryPP();
            InterProgramPointReplica ippr = InterProgramPointReplica.create(currentNode.getContext(), entryPP.pre());
            int receiver = currentNode.getIR().getParameter(0);
            for (IField f : fields) {
                if (f.isStatic()) {
                    continue;
                }
                Set<AbstractLocation> locs = interProc.getLocationsForNonStaticField(receiver,
                                                                                     f.getReference(),
                                                                                     currentNode,
                                                                                     ippr);
                initial = initial.addAll(locs);
            }
        }
        if (currentNode.getMethod().isClinit()) {
            // Initialize the static fields of the class to MAYBE_NULL
            Collection<IField> fields = currentNode.getMethod().getDeclaringClass().getAllFields();
            for (IField f : fields) {
                if (f.isStatic()) {
                    initial = initial.add(AbstractLocation.createStatic(f.getReference()));
                }
            }
        }
        return super.dataflow(initial);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> call(SSAInvokeInstruction i,
                                                            Set<AbstractLocationSet> previousItems,
                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                            ISSABasicBlock current) {
        AbstractLocationSet in = confluence(previousItems, current);
        AbstractLocationSet normalOut = in;
        AbstractLocationSet exOut = in;
        for (CGNode callee : cg.getPossibleTargets(currentNode, i.getCallSite())) {
            Map<ExitType, AbstractLocationSet> res = interProc.getResults(currentNode,
                                                                          callee,
                                                                          AbstractLocationSet.EMPTY);
            AbstractLocationSet nt = res.get(ExitType.NORMAL);
            if (nt != null) {
                normalOut = normalOut.join(nt);
            }
            AbstractLocationSet ex = res.get(ExitType.EXCEPTIONAL);
            if (ex != null) {
                exOut = exOut.join(ex);
            }
        }
        return factsToMapWithExceptions(normalOut, exOut, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> guessResultsForMissingReceiver(SSAInvokeInstruction i,
                                                                                      AbstractLocationSet input,
                                                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                      ISSABasicBlock bb) {
        return factToMap(AbstractLocationSet.EMPTY, bb, cfg);
    }

    @Override
    protected AbstractLocationSet confluence(Set<AbstractLocationSet> facts, ISSABasicBlock bb) {
        return AbstractLocationSet.joinAll(facts);
    }

    @Override
    protected AbstractLocationSet flowBinaryOp(SSABinaryOpInstruction i, Set<AbstractLocationSet> previousItems,
                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                               ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected AbstractLocationSet flowComparison(SSAComparisonInstruction i, Set<AbstractLocationSet> previousItems,
                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                 ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected AbstractLocationSet flowConversion(SSAConversionInstruction i, Set<AbstractLocationSet> previousItems,
                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                 ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected AbstractLocationSet flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                                         Set<AbstractLocationSet> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected AbstractLocationSet flowGetStatic(SSAGetInstruction i, Set<AbstractLocationSet> previousItems,
                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                ISSABasicBlock current) {
        return confluence(previousItems, current).add(AbstractLocation.createStatic(i.getDeclaredField()));
    }

    @Override
    protected AbstractLocationSet flowInstanceOf(SSAInstanceofInstruction i, Set<AbstractLocationSet> previousItems,
                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                 ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected AbstractLocationSet flowPhi(SSAPhiInstruction i, Set<AbstractLocationSet> previousItems,
                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected AbstractLocationSet flowPutStatic(SSAPutInstruction i, Set<AbstractLocationSet> previousItems,
                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                ISSABasicBlock current) {
        return confluence(previousItems, current).add(AbstractLocation.createStatic(i.getDeclaredField()));
    }

    @Override
    protected AbstractLocationSet flowUnaryNegation(SSAUnaryOpInstruction i, Set<AbstractLocationSet> previousItems,
                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                    ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowArrayLength(SSAArrayLengthInstruction i,
                                                                       Set<AbstractLocationSet> previousItems,
                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                       ISSABasicBlock current) {
        Set<AbstractLocation> arrayLocs = interProc.getLocationsForArrayContents(i.getArrayRef(), currentNode);
        return factToMap(confluence(previousItems, current).addAll(arrayLocs), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowArrayLoad(SSAArrayLoadInstruction i,
                                                                     Set<AbstractLocationSet> previousItems,
                                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                     ISSABasicBlock current) {
        Set<AbstractLocation> arrayLocs = interProc.getLocationsForArrayContents(i.getArrayRef(), currentNode);
        return factToMap(confluence(previousItems, current).addAll(arrayLocs), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowArrayStore(SSAArrayStoreInstruction i,
                                                                      Set<AbstractLocationSet> previousItems,
                                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                      ISSABasicBlock current) {
        Set<AbstractLocation> arrayLocs = interProc.getLocationsForArrayContents(i.getArrayRef(), currentNode);
        return factToMap(confluence(previousItems, current).addAll(arrayLocs), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                                                                 Set<AbstractLocationSet> previousItems,
                                                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                 ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowCheckCast(SSACheckCastInstruction i,
                                                                     Set<AbstractLocationSet> previousItems,
                                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                     ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                                                             Set<AbstractLocationSet> previousItems,
                                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                             ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowGetField(SSAGetInstruction i,
                                                                    Set<AbstractLocationSet> previousItems,
                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                    ISSABasicBlock current) {
        // Get the program point for this instruction
        InterProgramPoint ipp = ptg.getRegistrar().getInsToPP().get(i).pre();
        InterProgramPointReplica ippr = InterProgramPointReplica.create(currentNode.getContext(), ipp);

        Set<AbstractLocation> locs = interProc.getLocationsForNonStaticField(i.getRef(),
                                                                             i.getDeclaredField(),
                                                                             currentNode,
                                                                             ippr);
        return factToMap(confluence(previousItems, current).addAll(locs), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowInvokeInterface(SSAInvokeInstruction i,
                                                                           Set<AbstractLocationSet> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowInvokeSpecial(SSAInvokeInstruction i,
                                                                         Set<AbstractLocationSet> previousItems,
                                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                         ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowInvokeStatic(SSAInvokeInstruction i,
                                                                        Set<AbstractLocationSet> previousItems,
                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                        ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowInvokeVirtual(SSAInvokeInstruction i,
                                                                         Set<AbstractLocationSet> previousItems,
                                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                         ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowGoto(SSAGotoInstruction i,
                                                                Set<AbstractLocationSet> previousItems,
                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowLoadMetadata(SSALoadMetadataInstruction i,
                                                                        Set<AbstractLocationSet> previousItems,
                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                        ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowMonitor(SSAMonitorInstruction i,
                                                                   Set<AbstractLocationSet> previousItems,
                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                   ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowNewArray(SSANewInstruction i,
                                                                    Set<AbstractLocationSet> previousItems,
                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                    ISSABasicBlock current) {
        Set<AbstractLocation> arrayLocs = interProc.getLocationsForArrayContents(i.getDef(), currentNode);
        return factToMap(confluence(previousItems, current).addAll(arrayLocs), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowNewObject(SSANewInstruction i,
                                                                     Set<AbstractLocationSet> previousItems,
                                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                     ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowPutField(SSAPutInstruction i,
                                                                    Set<AbstractLocationSet> previousItems,
                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                    ISSABasicBlock current) {
        // Get the program point for this instruction
        InterProgramPoint ipp = ptg.getRegistrar().getInsToPP().get(i).pre();
        InterProgramPointReplica ippr = InterProgramPointReplica.create(currentNode.getContext(), ipp);

        Set<AbstractLocation> locs = interProc.getLocationsForNonStaticField(i.getRef(),
                                                                             i.getDeclaredField(),
                                                                             currentNode,
                                                                             ippr);
        return factToMap(confluence(previousItems, current).addAll(locs), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowReturn(SSAReturnInstruction i,
                                                                  Set<AbstractLocationSet> previousItems,
                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                  ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowSwitch(SSASwitchInstruction i,
                                                                  Set<AbstractLocationSet> previousItems,
                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                  ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, AbstractLocationSet> flowThrow(SSAThrowInstruction i,
                                                                 Set<AbstractLocationSet> previousItems,
                                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                 ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected void post(IR ir) {
        BasicBlock exit = ir.getExitBlock();
        AnalysisRecord<AbstractLocationSet> ar = getAnalysisRecord(exit);
        AbstractLocationSet resultsForNode = confluence(ar.getInput(), exit);
        ((AccessibleLocationResults) interProc.getAnalysisResults()).setResults(currentNode, resultsForNode);
        super.post(ir);
    }
}
