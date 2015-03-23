package analysis.dataflow.interprocedural.reachability;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.IntraproceduralDataFlow;
import analysis.dataflow.interprocedural.bool.BooleanConstantDataFlow;
import analysis.dataflow.interprocedural.bool.BooleanConstantResults;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;

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
import com.ibm.wala.ssa.SymbolTable;

/**
 * Intra-procedural part of an inter-procedural reachability analysis
 */
public class ReachabilityDataFlow extends IntraproceduralDataFlow<ReachabilityAbsVal> {

    private final BooleanConstantResults booleanResults;

    public ReachabilityDataFlow(CGNode n, ReachabilityInterProceduralDataFlow interProc) {
        super(n, interProc);
        BooleanConstantDataFlow bcdf = new BooleanConstantDataFlow(n, ptg, interProc.getRvCache());
        bcdf.setOutputLevel(interProc.getOutputLevel());
        this.booleanResults = bcdf.run();
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> call(SSAInvokeInstruction i, Set<ReachabilityAbsVal> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb) {
        ReachabilityAbsVal in = confluence(inItems, bb);
        if (in == ReachabilityAbsVal.UNREACHABLE) {
            // This call is unreachable so no need to analyze the callee
            // In fact if we do we could sat that exits are reachable due to imprecision (the same method can be
            // reachable and unreachable at different call sites, but in the same context).
            return factToMap(ReachabilityAbsVal.UNREACHABLE, bb, cfg);
        }

        // Start out assuming successors are unreachable, if one target can exit then it will change in the loop below
        ReachabilityAbsVal normal = ReachabilityAbsVal.UNREACHABLE;
        ReachabilityAbsVal exceptional = ReachabilityAbsVal.UNREACHABLE;

        Set<CGNode> targets = cg.getPossibleTargets(currentNode, i.getCallSite());
        if (targets.isEmpty()) {
            return guessResultsForMissingReceiver(i, in, cfg, bb);
        }

        for (CGNode callee : targets) {
            Map<ExitType, ReachabilityAbsVal> out = interProc.getResults(currentNode, callee, in);
            normal = normal.join(out.get(ExitType.NORMAL));
            exceptional = exceptional.join(out.get(ExitType.EXCEPTIONAL));
        }

        // If non-static assume exceptional successors are as reachable as the in item (reachable at least via NPE)
        return factsToMapWithExceptions(normal, i.isStatic() ? exceptional : in.join(exceptional), bb, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> guessResultsForMissingReceiver(SSAInvokeInstruction i,
                                    ReachabilityAbsVal input, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock bb) {
        if (outputLevel >= 1) {
            System.err.println("No calls to " + PrettyPrinter.methodString(i.getDeclaredTarget()) + " from "
                                            + PrettyPrinter.cgNodeString(currentNode));
        }
        return factToMap(input, bb, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowUnreachableBlock(Set<ReachabilityAbsVal> inItems,
                                                                             ISSABasicBlock current,
                                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> g) {
        // The only unreachable blocks are blocks with no predecessors
        assert getNumPreds(current, g) == 0;
        if (current.isEntryBlock()) {
            // Input blocks are handled elsewhere
            return null;
        }

        if (outputLevel >= 1) {
            System.err.println("WARNING: unreachable BB " + current + " in "
                    + PrettyPrinter.methodString(currentNode.getMethod()));
        }

        // If these blocks are not the input block then they are unnecessary catch blocks
        assert current.isCatchBlock();
        Map<ISSABasicBlock, ReachabilityAbsVal> out = new LinkedHashMap<>();
        Iterator<ISSABasicBlock> succs = getSuccs(current, g);
        while (succs.hasNext()) {
            // all successors are unreachable since this block is never reached
            out.put(succs.next(), ReachabilityAbsVal.UNREACHABLE);
        }
        return out;
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flow(Set<ReachabilityAbsVal> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        Map<ISSABasicBlock, ReachabilityAbsVal> out = super.flow(inItems, cfg, current);

        PreciseExceptionResults pe = ((ReachabilityInterProceduralDataFlow) interProc).getPreciseEx();
        if (pe == null) {
            return out;
        }

        Map<ISSABasicBlock, ReachabilityAbsVal> newOutItems = new LinkedHashMap<>();
        for (ISSABasicBlock bb : getExceptionalSuccs(current, cfg)) {
            // Modify outItems based on the precise exceptions analysis
            if (out.get(bb) == ReachabilityAbsVal.REACHABLE && pe.getExceptions(current, bb, currentNode).isEmpty()) {
                // This is an exception edge with no exceptions
                newOutItems.put(bb, ReachabilityAbsVal.UNREACHABLE);
            }
        }

        if (!newOutItems.isEmpty()) {
            for (ISSABasicBlock bb : out.keySet()) {
                if (!newOutItems.containsKey(bb)) {
                    newOutItems.put(bb, out.get(bb));
                }
            }
        } else {
            newOutItems = out;
        }
        return newOutItems;
    }

    @Override
    protected void postBasicBlock(ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock justProcessed,
                                    Map<ISSABasicBlock, ReachabilityAbsVal> outItems) {

        for (ISSABasicBlock bb : getNormalSuccs(justProcessed, cfg)) {
            if (outItems.get(bb) == null) {
                throw new RuntimeException("Missing fact on normal edge from " + justProcessed + " to " + bb);
            }
        }

        for (ISSABasicBlock bb : getExceptionalSuccs(justProcessed, cfg)) {
            if (outItems.get(bb) == null) {
                throw new RuntimeException("Missing fact on exception edge from " + justProcessed + " to " + bb);
            }
        }

        super.postBasicBlock(cfg, justProcessed, outItems);
    }

    @Override
    protected void post(IR ir) {
        Set<OrderedPair<ISSABasicBlock, ISSABasicBlock>> unreachable = new LinkedHashSet<>();
        for (ISSABasicBlock source : ir.getControlFlowGraph()) {
            if (getAnalysisRecord(source) == null) {
                // source is unreachable from the entry, it is an unneccessary catch block with no predecessors
                System.err.println("WARNING: null record for " + source + " in "
                        + PrettyPrinter.methodString(ir.getMethod()));
                assert ir.getControlFlowGraph().getPredNodeCount(source) == 0;
                assert source.isCatchBlock();
                continue;
            }
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
    protected ReachabilityAbsVal confluence(Set<ReachabilityAbsVal> facts, ISSABasicBlock bb) {
        ReachabilityAbsVal result = null;
        for (ReachabilityAbsVal fact : facts) {
            result = fact.join(result);
        }
        return result;
    }

    @Override
    protected ReachabilityAbsVal flowBinaryOp(SSABinaryOpInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ReachabilityAbsVal flowComparison(SSAComparisonInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ReachabilityAbsVal flowConversion(SSAConversionInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ReachabilityAbsVal flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ReachabilityAbsVal flowGetStatic(SSAGetInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ReachabilityAbsVal flowInstanceOf(SSAInstanceofInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Could keep track of results of instanceof to find dead branches
        return confluence(previousItems, current);
    }

    @Override
    protected ReachabilityAbsVal flowPhi(SSAPhiInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ReachabilityAbsVal flowPutStatic(SSAPutInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected ReachabilityAbsVal flowUnaryNegation(SSAUnaryOpInstruction i, Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowArrayLength(SSAArrayLengthInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowArrayLoad(SSAArrayLoadInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowArrayStore(SSAArrayStoreInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowCheckCast(SSACheckCastInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        ReachabilityAbsVal in = confluence(previousItems, current);
        if (in == ReachabilityAbsVal.UNREACHABLE) {
            // The branching statement is unreachable so both children are as well
            return factToMap(in, current, cfg);
        }

        int leftValNum = i.getUse(0);
        int rightValNum = i.getUse(1);
        SymbolTable st = currentNode.getIR().getSymbolTable();

        // See if both are constants
        Object left = null;
        Object right = null;
        Double leftValue = null;
        Double rightValue = null;
        if (st.isConstant(leftValNum) && st.isConstant(rightValNum)) {
            left = st.getConstantValue(leftValNum);
            right = st.getConstantValue(rightValNum);
            if (st.isNumberConstant(leftValNum)) {
                leftValue = st.getDoubleValue(leftValNum);
            }

            if (st.isNumberConstant(rightValNum)) {
                rightValue = st.getDoubleValue(rightValNum);
            }
        } else if (i.isIntegerComparison()) {
            // This may be the comparison between two booleans, lets see if both of them are constant
            // Note that literal constant booleans are turned into integers and that 0 is false
            if (booleanResults.isConstant(i, leftValNum)) {
                left = booleanResults.getConstant(i, leftValNum);
                if (left != null) {
                    leftValue = ((Boolean) left).booleanValue() ? 1.0 : 0;
                }
            }
            if (booleanResults.isConstant(i, rightValNum)) {
                right = booleanResults.getConstant(i, rightValNum);
                if (right != null) {
                    rightValue = ((Boolean) right).booleanValue() ? 1.0 : 0;
                }
            }
        }

        if (left != null && right != null) {
            // Results are statically known

            Map<ISSABasicBlock, ReachabilityAbsVal> out = new LinkedHashMap<>();
            boolean result;
            switch (i.getOperator().toString()) {
            case "eq":
                // if (left == right)
                result = left.equals(right);
                break;
            case "ne":
                // if (left != right)
                result = !left.equals(right);
                break;

            // The rest are numerical comparisons
            case "lt":
                // if (left < right)
                assert leftValue != null;
                assert rightValue != null;
                result = leftValue < rightValue;
                break;
            case "ge":
                // if (left >= right)
                assert leftValue != null;
                assert rightValue != null;
                result = leftValue >= rightValue;
                break;
            case "gt":
                // if (left > right)
                assert leftValue != null;
                assert rightValue != null;
                result = leftValue > rightValue;
                break;
            case "le":
                // if (left <= right)
                assert leftValue != null;
                assert rightValue != null;
                result = leftValue <= rightValue;
                break;
            default:
                throw new IllegalArgumentException("operator not found " + i.getOperator());
            }
            // We know the result so one brach is unreachable
            ISSABasicBlock trueBB = getTrueSuccessor(current, cfg);
            ISSABasicBlock falseBB = getFalseSuccessor(current, cfg);
            if (result) {
                // Statically true
                out.put(trueBB, ReachabilityAbsVal.REACHABLE);
                out.put(falseBB, ReachabilityAbsVal.UNREACHABLE);
            } else {
                // Statically false
                out.put(trueBB, ReachabilityAbsVal.UNREACHABLE);
                out.put(falseBB, ReachabilityAbsVal.REACHABLE);
            }
            return out;
        }

        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowGetField(SSAGetInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
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
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowLoadMetadata(SSALoadMetadataInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowMonitor(SSAMonitorInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowNewArray(SSANewInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowNewObject(SSANewInstruction i,
                                    Set<ReachabilityAbsVal> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // There is an error from new Object() but we are not tracking errors
        return factsToMapWithExceptions(confluence(inItems, current), ReachabilityAbsVal.UNREACHABLE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowPutField(SSAPutInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowReturn(SSAReturnInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowSwitch(SSASwitchInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, ReachabilityAbsVal> flowThrow(SSAThrowInstruction i,
                                    Set<ReachabilityAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // This cannot terminate normally, but there is no normal termination edge either
        return mergeAndCreateMap(previousItems, current, cfg);
    }
}
