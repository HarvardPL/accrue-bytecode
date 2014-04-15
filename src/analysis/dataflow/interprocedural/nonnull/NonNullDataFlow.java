package analysis.dataflow.interprocedural.nonnull;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.dataflow.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.VarContext;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptions;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.shrikeBT.IConditionalBranchInstruction.Operator;
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
import com.ibm.wala.types.TypeReference;

/**
 * Inter-procedural analysis that determines which local variable can be null at a particular program point
 */
public class NonNullDataFlow extends InterproceduralDataFlow<VarContext<NonNullAbsVal>> {

    /**
     * Results of the analysis, namely which local variables are null and when are put here
     */
    public static final NonNullResults results = new NonNullResults();
    /**
     * Results of a precise exceptions analysis
     */
    private final PreciseExceptions preciseEx;
    /**
     * WALA classes
     */
    private final WalaAnalysisUtil util;
    /**
     * Call graph nodes currently under analysis (callers of the current call
     * graph node), used to discover recursive calls
     */
    private Set<CGNode> currentlyProcessing;

    public NonNullDataFlow(CGNode currentNode, CallGraph cg, PointsToGraph ptg, PreciseExceptions preciseEx,
            WalaAnalysisUtil util, Set<CGNode> currentlyProcessing) {
        super(currentNode, cg, ptg);
        this.preciseEx = preciseEx;
        this.util = util;
        this.currentlyProcessing = currentlyProcessing;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> call(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock bb) {
        boolean isVoid = i.getNumberOfReturnValues() == 0;
        
        VarContext<NonNullAbsVal> in = confluence(inItems);

        // If receiver could be null
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getReceiver(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NOT_NULL);
        
        // Receiver is not null
        VarContext<NonNullAbsVal> nonNull = in.setLocal(i.getReceiver(), NonNullAbsVal.NOT_NULL);
        VarContext<NonNullAbsVal> normalResult = null;
        VarContext<NonNullAbsVal> exceptionResult = null;
        for (CGNode n : cg.getPossibleTargets(currentNode, i.getCallSite())) {
            Map<ExitType, VarContext<NonNullAbsVal>> out;
            if (currentlyProcessing.contains(n) || currentNode.equals(n)) {
                // TODO handle recursive calls
                out = null;
            } else {
                Set<CGNode> processing = new HashSet<>(currentlyProcessing);
                processing.add(currentNode);
                NonNullDataFlow df = new NonNullDataFlow(n, cg, ptg, preciseEx, util, processing);
                VarContext<NonNullAbsVal> initial = nonNull.clearLocalsAndExits();
                int[] formals = n.getIR().getParameterValueNumbers();
                if (!i.isStatic()) {
                    // Receiver is not null
                    // for non-static calls the first formal is "this"
                    initial.setLocal(formals[0], NonNullAbsVal.NOT_NULL);
                } else if (formals.length > 0) {
                    // for static calls the first formal is a parameter
                    initial.setLocal(formals[0], nonNull.getLocal(i.getUse(0)));
                }
                for (int j = 1; j < formals.length; j++) {
                    initial.setLocal(formals[j], nonNull.getLocal(i.getUse(j)));
                }
                out = df.dataflow(initial);
            }

            normalResult = confluence(normalResult, out.get(ExitType.NORM_TERM));
            exceptionResult = confluence(exceptionResult, out.get(ExitType.EXCEPTION));

        }
        
        NonNullAbsVal returnResult = normalResult.getReturnResult();
        NonNullAbsVal exception = exceptionResult.getException();
        
        Map<Integer, VarContext<NonNullAbsVal>> ret = new LinkedHashMap<>();
        
        // Normal return
        VarContext<NonNullAbsVal> normal;
        if (!isVoid) {
            normal = nonNull.setLocal(i.getReturnValue(0), returnResult);
        } else {
            normal = nonNull;
        }
        
        for (ISSABasicBlock normalSucc : cfg.getNormalSuccessors(bb)) {
            ret.put(normalSucc.getGraphNodeId(), normal);
        }
        
        // Exceptional return
        Set<ISSABasicBlock> npeSuccs = getSuccessorsForExceptionType(TypeReference.JavaLangNullPointerException, cfg, bb, util.getClassHierarchy());
        for (ISSABasicBlock exSucc : cfg.getExceptionalSuccessors(bb)) {
            if (npeSuccs.contains(exSucc)) {
                ret.put(exSucc.getGraphNodeId(), npe.setLocal(i.getException(), exception));
            } else {
                ret.put(exSucc.getGraphNodeId(), nonNull.setExceptionValue(exception)).setLocal(i.getException(), exception);
            }
            
        }
        
        return ret;
    }

    @Override
    protected VarContext<NonNullAbsVal> confluence(Set<VarContext<NonNullAbsVal>> items) {
        return VarContext.join(items);
    }

    @Override
    protected void postBasicBlock(Set<VarContext<NonNullAbsVal>> inItems,
            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock justProcessed,
            Map<Integer, VarContext<NonNullAbsVal>> outItems) {
        Iterator<ISSABasicBlock> nextBlocks = cfg.getSuccNodes(justProcessed);
        SSAInstruction last = justProcessed.getLastInstruction();
        while (nextBlocks.hasNext()) {
            ISSABasicBlock next = nextBlocks.next();
            if (outItems.get(next.getGraphNodeId()) == null
                    && !preciseEx.getImpossibleSuccessors(last, justProcessed, currentNode).contains(next)) {
                throw new RuntimeException("No item for successor of "
                        + PrettyPrinter.instructionString(currentNode.getIR(), last) + " from BB"
                        + justProcessed.getGraphNodeId() + " to BB" + next.getGraphNodeId());
            }
        }
    }
    
    @Override
    protected void post(IR ir) {
        for (SSAInstruction i : inputItems.keySet()) {
            VarContext<NonNullAbsVal> input = inputItems.get(i);
            Set<Integer> nonNulls = new HashSet<>();
            for (Integer j : input.getLocals()) {
                if (input.getLocal(j).isNonnull()) {
                    nonNulls.add(j);
                }
            }
            results.replaceNonNull(nonNulls, i, currentNode);
        }
        super.post(ir);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowBinaryOp(SSABinaryOpInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // no pointers
        return confluence(previousItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowComparison(SSAComparisonInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // no pointers
        return confluence(previousItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowConversion(SSAConversionInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // no pointers
        return confluence(previousItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        return in.setLocal(i.getException(), NonNullAbsVal.NOT_NULL);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowGetStatic(SSAGetInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // Not flow-sensitive for fields, so we cannot determine whether they
        // are null or not

        return confluence(previousItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowInstanceOf(SSAInstanceofInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {

        // if the instanceof check is successful then the object cannot be null,
        // but that comparison isn't handled until there is a branch
        // TODO be smarter about instanceof in NonNull

        return confluence(previousItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowPhi(SSAPhiInstruction i, Set<VarContext<NonNullAbsVal>> previousItems,
            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);

        NonNullAbsVal val = NonNullAbsVal.NOT_NULL;
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            val = val.join(in.getLocal(i.getUse(j)));
        }

        return in.setLocal(i.getDef(), val);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowPutStatic(SSAPutInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {

        // Not flow-sensitive for fields, so we cannot determine whether they
        // are null or not

        return confluence(previousItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowUnaryNegation(SSAUnaryOpInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // no pointers
        return confluence(previousItems);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowArrayLength(SSAArrayLengthInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normal = in.setLocal(i.getArrayRef(), NonNullAbsVal.NOT_NULL);
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getArrayRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NOT_NULL);

        return itemToMapWithExceptions(normal, npe, preciseEx.getImpossibleSuccessors(i, current, currentNode),
                current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowArrayLoad(SSAArrayLoadInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);

        VarContext<NonNullAbsVal> normal = in.setLocal(i.getArrayRef(), NonNullAbsVal.NOT_NULL);
        Map<Integer, VarContext<NonNullAbsVal>> out = new LinkedHashMap<>();
        int normalSucc = cfg.getSuccNodeNumbers(current).intIterator().next();
        out.put(normalSucc, normal);

        // If not null then may throw an ArrayIndexOutOfBoundsException
        VarContext<NonNullAbsVal> indexOOB = normal.setExceptionValue(NonNullAbsVal.NOT_NULL);
        Set<ISSABasicBlock> possibleIOOB = getSuccessorsForExceptionType(
                TypeReference.JavaLangArrayIndexOutOfBoundsException, cfg, current, util.getClassHierarchy());
        possibleIOOB.removeAll(preciseEx.getImpossibleSuccessors(i, current, currentNode));
        for (ISSABasicBlock succ : possibleIOOB) {
            out.put(succ.getGraphNodeId(), indexOOB);
        }

        VarContext<NonNullAbsVal> npe = in.setLocal(i.getArrayRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NOT_NULL);
        Set<ISSABasicBlock> possibleNPE = getSuccessorsForExceptionType(TypeReference.JavaLangNullPointerException,
                cfg, current, util.getClassHierarchy());
        possibleNPE.removeAll(preciseEx.getImpossibleSuccessors(i, current, currentNode));
        for (ISSABasicBlock succ : possibleNPE) {
            // Note that if a successor can be reached another way in addition
            // to the NPE, the context is the NPE context (since the array may
            // be null) so we put the NPE context in last
            out.put(succ.getGraphNodeId(), npe);
        }

        return out;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowArrayStore(SSAArrayStoreInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);

        VarContext<NonNullAbsVal> normal = in.setLocal(i.getArrayRef(), NonNullAbsVal.NOT_NULL);
        Map<Integer, VarContext<NonNullAbsVal>> out = new LinkedHashMap<>();
        int normalSucc = cfg.getSuccNodeNumbers(current).intIterator().next();
        out.put(normalSucc, normal);

        // If not null then may throw an ArrayIndexOutOfBoundsException or
        // ArrayStoreException
        VarContext<NonNullAbsVal> otherEx = normal.setExceptionValue(NonNullAbsVal.NOT_NULL);
        Set<ISSABasicBlock> possibleOtherEx = getSuccessorsForExceptionType(
                TypeReference.JavaLangArrayIndexOutOfBoundsException, cfg, current, util.getClassHierarchy());
        possibleOtherEx.addAll(getSuccessorsForExceptionType(TypeReference.JavaLangArrayStoreException, cfg, current,
                util.getClassHierarchy()));
        possibleOtherEx.removeAll(preciseEx.getImpossibleSuccessors(i, current, currentNode));
        for (ISSABasicBlock succ : possibleOtherEx) {
            out.put(succ.getGraphNodeId(), otherEx);
        }

        VarContext<NonNullAbsVal> npe = in.setLocal(i.getArrayRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NOT_NULL);
        Set<ISSABasicBlock> possibleNPE = getSuccessorsForExceptionType(TypeReference.JavaLangNullPointerException,
                cfg, current, util.getClassHierarchy());
        possibleNPE.removeAll(preciseEx.getImpossibleSuccessors(i, current, currentNode));
        for (ISSABasicBlock succ : possibleNPE) {
            // Note that if a successor can be reached another way in addition
            // to the NPE, the context is the NPE context (since the array may
            // be null) so we put the NPE context in last
            out.put(succ.getGraphNodeId(), npe);
        }

        return out;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowCheckCast(SSACheckCastInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> exception = in.setExceptionValue(NonNullAbsVal.NOT_NULL);
        // "null" can be cast to anything so if we get a ClassCastException then
        // we know the casted object was not null
        exception = exception.setLocal(i.getUse(0), NonNullAbsVal.NOT_NULL);

        return itemToMapWithExceptions(in, exception, preciseEx.getImpossibleSuccessors(i, current, currentNode),
                current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowConditionalBranch(SSAConditionalBranchInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        assert getNumSuccs(current, cfg) == 2 : "Not two successors for a conditional branch: "
                + PrettyPrinter.instructionString(currentNode.getIR(), i) + " has " + getNumSuccs(current, cfg);

        VarContext<NonNullAbsVal> in = confluence(previousItems);

        // By default both branches are the same as the input
        Map<Integer, VarContext<NonNullAbsVal>> out = itemToModifiableMap(in, current, cfg);

        // Check if the comparison is an comparison against the null literal
        IR ir = currentNode.getIR();
        boolean fstNull = ir.getSymbolTable().getValue(i.getUse(0)).isNullConstant();
        boolean sndNull = ir.getSymbolTable().getValue(i.getUse(1)).isNullConstant();
        if (fstNull || sndNull) {
            VarContext<NonNullAbsVal> trueContext = in;
            VarContext<NonNullAbsVal> falseContext = in;
            if (fstNull && !sndNull && i.getOperator() == Operator.EQ) {
                // null == obj
                trueContext = in.setLocal(i.getUse(1), NonNullAbsVal.MAY_BE_NULL);
                falseContext = in.setLocal(i.getUse(1), NonNullAbsVal.NOT_NULL);
            }

            if (fstNull && !sndNull && i.getOperator() == Operator.NE) {
                // null != obj
                trueContext = in.setLocal(i.getUse(1), NonNullAbsVal.NOT_NULL);
                falseContext = in.setLocal(i.getUse(1), NonNullAbsVal.MAY_BE_NULL);
            }

            if (sndNull && !fstNull && i.getOperator() == Operator.EQ) {
                // obj == null
                trueContext = in.setLocal(i.getUse(0), NonNullAbsVal.MAY_BE_NULL);
                falseContext = in.setLocal(i.getUse(0), NonNullAbsVal.NOT_NULL);
            }

            if (sndNull && !fstNull && i.getOperator() == Operator.NE) {
                // obj != null
                trueContext = in.setLocal(i.getUse(0), NonNullAbsVal.NOT_NULL);
                falseContext = in.setLocal(i.getUse(0), NonNullAbsVal.MAY_BE_NULL);
            }

            out.put(getTrueSuccessor(current, cfg), trueContext);
            out.put(getFalseSuccessor(current, cfg), falseContext);
        }

        return out;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowGetField(SSAGetInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normal = in.setLocal(i.getRef(), NonNullAbsVal.NOT_NULL);
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NOT_NULL);

        // Not flow-sensitive for fields, so we cannot determine whether they
        // are null or not

        return itemToMapWithExceptions(normal, npe, preciseEx.getImpossibleSuccessors(i, current, currentNode),
                current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowGoto(SSAGotoInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeInterface(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeSpecial(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeStatic(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeVirtual(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowLoadMetadata(SSALoadMetadataInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normalOut = in.setLocal(i.getDef(), NonNullAbsVal.NOT_NULL);
        VarContext<NonNullAbsVal> exception = in.setExceptionValue(NonNullAbsVal.NOT_NULL);

        return itemToMapWithExceptions(normalOut, exception,
                preciseEx.getImpossibleSuccessors(i, current, currentNode), current, cfg);
    }
    
    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowMonitor(SSAMonitorInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normal = in.setLocal(i.getRef(), NonNullAbsVal.NOT_NULL);
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NOT_NULL);

        return itemToMapWithExceptions(normal, npe, preciseEx.getImpossibleSuccessors(i, current, currentNode),
                current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowNewArray(SSANewInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normalOut = in.setLocal(i.getDef(), NonNullAbsVal.NOT_NULL);
        VarContext<NonNullAbsVal> exception = in.setExceptionValue(NonNullAbsVal.NOT_NULL);

        return itemToMapWithExceptions(normalOut, exception,
                preciseEx.getImpossibleSuccessors(i, current, currentNode), current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowNewObject(SSANewInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normalOut = in.setLocal(i.getDef(), NonNullAbsVal.NOT_NULL);

        // The exception edge is actually for errors so no value needs to be
        // passed on that edge (it will always be impossible)
        return itemToMapWithExceptions(normalOut, null, preciseEx.getImpossibleSuccessors(i, current, currentNode),
                current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowPutField(SSAPutInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normal = in.setLocal(i.getRef(), NonNullAbsVal.NOT_NULL);
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NOT_NULL);

        // Not flow-sensitive for fields, so we cannot determine whether they
        // are null or not

        return itemToMapWithExceptions(normal, npe, preciseEx.getImpossibleSuccessors(i, current, currentNode),
                current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowReturn(SSAReturnInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> out = in.setReturnResult(in.getLocal(i.getResult()));

        return itemToMap(out, current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowSwitch(SSASwitchInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        // no pointers
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowThrow(SSAThrowInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);

        VarContext<NonNullAbsVal> normal = in.setReturnResult(null);
        normal = normal.setExceptionValue(NonNullAbsVal.NOT_NULL);
        normal = normal.setLocal(i.getException(), NonNullAbsVal.NOT_NULL);

        VarContext<NonNullAbsVal> npe = in.setReturnResult(null);
        npe = normal.setExceptionValue(NonNullAbsVal.NOT_NULL);

        return itemToMapWithExceptions(normal, npe, preciseEx.getImpossibleSuccessors(i, current, currentNode),
                current, cfg);
    }
}
