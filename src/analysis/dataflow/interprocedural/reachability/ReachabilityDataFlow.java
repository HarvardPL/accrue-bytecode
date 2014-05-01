package analysis.dataflow.interprocedural.reachability;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.IntraproceduralDataFlow;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
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
 * Intra-procedural part of an inter-procedural reachability analysis
 */
public class ReachabilityDataFlow extends IntraproceduralDataFlow<ReachabilityAbsVal> {

    public ReachabilityDataFlow(CGNode n, ReachabilityInterProceduralDataFlow interProc) {
        super(n, interProc);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> call(SSAInvokeInstruction i, Set<ReachabilityAbsVal> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb) {

        ReachabilityAbsVal in = confluence(inItems);
        // Start out assuming successors are unreachable, if
        ReachabilityAbsVal normal = ReachabilityAbsVal.UNREACHABLE;
        ReachabilityAbsVal exceptional = ReachabilityAbsVal.UNREACHABLE;
        for (CGNode callee : cg.getPossibleTargets(currentNode, i.getCallSite())) {
            Map<ExitType, ReachabilityAbsVal> out = interProc.getResults(currentNode, callee, in);
            normal = normal.join(out.get(ExitType.NORMAL));
            exceptional = exceptional.join(out.get(ExitType.EXCEPTIONAL));
        }
        // If non-static assume exceptional successors are reachable at least
        // via NPE
        return factsToMapWithExceptions(normal, i.isStatic() ? exceptional : ReachabilityAbsVal.REACHABLE, bb, cfg);
    }

    @Override
    protected void postBasicBlock(Set<ReachabilityAbsVal> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock justProcessed,
                                    Map<ISSABasicBlock, ReachabilityAbsVal> outItems) {

        for (ISSABasicBlock bb : getNormalSuccs(justProcessed, cfg)) {
            if (outItems.get(bb) == null) {
                throw new RuntimeException("Missing fact on normal edge.");
            }
        }

        for (ISSABasicBlock bb : getExceptionalSuccs(justProcessed, cfg)) {
            if (outItems.get(bb) == null) {
                throw new RuntimeException("Missing fact on exception edge.");
            }
        }

        super.postBasicBlock(inItems, cfg, justProcessed, outItems);
    }

    @Override
    protected void post(IR ir) {
        Set<OrderedPair<ISSABasicBlock, ISSABasicBlock>> unreachable = new LinkedHashSet<>();
        for (ISSABasicBlock source : ir.getControlFlowGraph()) {
            Map<ISSABasicBlock, ReachabilityAbsVal> output = getAnalysisRecord(source).getOutput();
            for (ISSABasicBlock target : output.keySet()) {
                if (output.get(target).isUnreachable()) {
                    unreachable.add(new OrderedPair<>(source, target));
                }
            }
        }
        ((ReachabilityInterProceduralDataFlow) interProc).getAnalysisResults().replaceUnreachable(unreachable,
                                        currentNode);

        super.post(ir);
    }

    @Override
    protected ReachabilityAbsVal confluence(Set<ReachabilityAbsVal> facts) {
        ReachabilityAbsVal result = null;
        for (ReachabilityAbsVal fact : facts) {
            result = fact.join(result);
        }
        return result;
    }

    @Override
    protected ReachabilityAbsVal flowBinaryOp(SSABinaryOpInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems);
    }

    @Override
    protected ReachabilityAbsVal flowComparison(SSAComparisonInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems);
    }

    @Override
    protected ReachabilityAbsVal flowConversion(SSAConversionInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems);
    }

    @Override
    protected ReachabilityAbsVal flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems);
    }

    @Override
    protected ReachabilityAbsVal flowGetStatic(SSAGetInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems);
    }

    @Override
    protected ReachabilityAbsVal flowInstanceOf(SSAInstanceofInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems);
    }

    @Override
    protected ReachabilityAbsVal flowPhi(SSAPhiInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems);
    }

    @Override
    protected ReachabilityAbsVal flowPutStatic(SSAPutInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems);
    }

    @Override
    protected ReachabilityAbsVal flowUnaryNegation(SSAUnaryOpInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowArrayLength(SSAArrayLengthInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowArrayLoad(SSAArrayLoadInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowArrayStore(SSAArrayStoreInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowCheckCast(SSACheckCastInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowGetField(SSAGetInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowInvokeInterface(SSAInvokeInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowInvokeSpecial(SSAInvokeInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowInvokeStatic(SSAInvokeInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowInvokeVirtual(SSAInvokeInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowGoto(SSAGotoInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowLoadMetadata(SSALoadMetadataInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowMonitor(SSAMonitorInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowNewArray(SSANewInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowNewObject(SSANewInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // There is an error from new Object() but we are not tracking errors
        return factsToMapWithExceptions(confluence(previousItems), ReachabilityAbsVal.UNREACHABLE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowPutField(SSAPutInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowReturn(SSAReturnInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowSwitch(SSASwitchInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowThrow(SSAThrowInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // This cannot terminate normally, but there is no normal termination
        // edge either
        return mergeAndCreateMap(previousItems, cfg, current);
    }
}
