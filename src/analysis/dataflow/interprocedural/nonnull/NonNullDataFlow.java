package analysis.dataflow.interprocedural.nonnull;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.WalaAnalysisUtil;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptions;
import analysis.dataflow.util.AbstractLocation;
import analysis.dataflow.util.ExitType;
import analysis.dataflow.util.VarContext;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
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
import com.ibm.wala.ssa.Value;
import com.ibm.wala.types.TypeReference;

/**
 * Inter-procedural analysis that determines which local variable can be null at
 * a particular program point
 */
public class NonNullDataFlow extends InterproceduralDataFlow<VarContext<NonNullAbsVal>> {

    /**
     * Results of a precise exceptions analysis
     */
    private final PreciseExceptions preciseEx;
    /**
     * WALA classes
     */
    private final WalaAnalysisUtil util;

    public NonNullDataFlow(CGNode currentNode, NonNullManager manager, PreciseExceptions preciseEx,
                                    WalaAnalysisUtil util) {
        super(currentNode, manager);
        this.preciseEx = preciseEx;
        this.util = util;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> call(SSAInvokeInstruction i,
                                    Set<VarContext<NonNullAbsVal>> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb) {
        boolean isVoid = i.getNumberOfReturnValues() == 0;

        VarContext<NonNullAbsVal> in = confluence(inItems);

        VarContext<NonNullAbsVal> npe = null;
        VarContext<NonNullAbsVal> nonNull = in;
        if (!i.isStatic()) {
            // TODO what if receiver is never null
            npe = in.setExceptionValue(NonNullAbsVal.NON_NULL);
            npe = in.setLocal(i.getReceiver(), NonNullAbsVal.MAY_BE_NULL);
            nonNull = in.setLocal(i.getReceiver(), NonNullAbsVal.NON_NULL);
        }

        VarContext<NonNullAbsVal> normalResult = null;
        VarContext<NonNullAbsVal> exceptionResult = null;
        int[] actuals = new int[i.getNumberOfUses()];
        for (int j = 0; j < actuals.length; j++) {
            actuals[j] = i.getUse(j);
        }

        int[] formals = new int[i.getNumberOfUses()];
        for (CGNode callee : cg.getPossibleTargets(currentNode, i.getCallSite())) {
            Map<ExitType, VarContext<NonNullAbsVal>> out;
            VarContext<NonNullAbsVal> initial = nonNull.clearLocalsAndExits();
            formals = callee.getIR().getParameterValueNumbers();
            int j;
            if (i.isStatic()) {
                // for static calls the first formal is a parameter
                j = 0;
            } else {
                // Receiver is not null
                // for non-static calls the first formal is "this"
                initial = initial.setLocal(formals[0], NonNullAbsVal.NON_NULL);
                j = 1;
            }
            for (; j < formals.length; j++) {
                NonNullAbsVal actualVal = nonNull.getLocal(actuals[j]);
                if (actualVal == null) {
                    if (TypeRepository.getType(actuals[0], currentNode.getIR()).isPrimitiveType()) {
                        actualVal = NonNullAbsVal.NON_NULL;
                    } else if (currentNode.getIR().getSymbolTable().isConstant(actuals[0])) {
                        if (currentNode.getIR().getSymbolTable().isNullConstant(actuals[0])) {
                            actualVal = NonNullAbsVal.MAY_BE_NULL;
                        } else if (currentNode.getIR().getSymbolTable().isStringConstant(actuals[0])) {
                            actualVal = NonNullAbsVal.NON_NULL;
                        }
                    } else {
                        throw new RuntimeException("Null NonNullAbsValue for non-primitive non-constant.");
                    }
                }
                initial = initial.setLocal(formals[j], actualVal);
            }
            out = manager.getResults(currentNode, callee, initial);
            assert out != null : "Null data-flow results for: " + PrettyPrinter.parseCGNode(callee) + "\nFrom "
                                            + PrettyPrinter.parseCGNode(currentNode);
            normalResult = confluence(normalResult, out.get(ExitType.NORM_TERM));
            exceptionResult = confluence(exceptionResult, out.get(ExitType.EXCEPTION));

        }

        Map<Integer, VarContext<NonNullAbsVal>> ret = new LinkedHashMap<>();

        // Normal return
        VarContext<NonNullAbsVal> normal;
        if (!isVoid) {
            normal = nonNull.setLocal(i.getReturnValue(0), normalResult.getReturnResult());
            normal = copyBackFormals(formals, actuals, normalResult, normal, i.isStatic());
        } else {
            normal = nonNull;
            normal = copyBackFormals(formals, actuals, normalResult, normal, i.isStatic());
        }

        for (ISSABasicBlock normalSucc : cfg.getNormalSuccessors(bb)) {
            ret.put(normalSucc.getGraphNodeId(), normal);
        }

        // Exceptional return
        VarContext<NonNullAbsVal> callerExContext = null;
        if (exceptionResult != null) {
            // If the exception result is null then there are no exception edges into the exit 
            // So the procedure cannot throw any exceptions
            callerExContext = nonNull.setLocal(i.getException(), NonNullAbsVal.NON_NULL).setExceptionValue(
                                            NonNullAbsVal.NON_NULL);
            callerExContext = copyBackFormals(formals, actuals, exceptionResult, callerExContext, i.isStatic());
        }
        Set<ISSABasicBlock> npeSuccs = getSuccessorsForExceptionType(TypeReference.JavaLangNullPointerException, cfg,
                                        bb, util.getClassHierarchy());
        
        for (ISSABasicBlock exSucc : cfg.getExceptionalSuccessors(bb)) {
            if (!i.isStatic() && npeSuccs.contains(exSucc)) {
                // TODO check whether any callee can throw an NPE and only join
                // if needed
                ret.put(exSucc.getGraphNodeId(), npe.join(callerExContext));
            } else {
                ret.put(exSucc.getGraphNodeId(), nonNull.join(callerExContext));
            }
        }

        return ret;
    }

    /**
     * Copy the values of the formals after the call into the caller context
     * 
     * @param formals
     *            value numbers in restoreFrom for the formals
     * @param actuals
     *            value numbers in restoreTo for the actuals
     * @param from
     *            context after analyzing the callee, copy the formals from here
     * @param to
     *            context before analyzing the caller copy the formals to the
     *            actuals here
     * @param isStatic
     *            true if this is a static method
     * @return new variable context that is the same as "to" but with the values
     *         of the formals copied from "from"
     */
    private VarContext<NonNullAbsVal> copyBackFormals(int[] formals, int[] actuals, VarContext<NonNullAbsVal> from,
                                    VarContext<NonNullAbsVal> to, boolean isStatic) {

        if (isStatic && formals.length > 0) {
            // for static calls the first formal is a parameter
            to.setLocal(actuals[0], from.getLocal(formals[0]));
        }
        for (int j = 1; j < formals.length; j++) {
            to.setLocal(actuals[j], from.getLocal(formals[j]));
        }
        return to;
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
        while (nextBlocks.hasNext()) {
            ISSABasicBlock next = nextBlocks.next();
            if (outItems.get(next.getGraphNodeId()) == null
                                            && !preciseEx.getImpossibleSuccessors(justProcessed, currentNode).contains(
                                                                            next)) {
                throw new RuntimeException("No item for successor of\n"
                                                + PrettyPrinter.basicBlockString(currentNode.getIR(), justProcessed,
                                                                                "\t", "\n") + " from BB"
                                                + justProcessed.getGraphNodeId() + " to BB" + next.getGraphNodeId());
            }
        }
        super.postBasicBlock(inItems, cfg, justProcessed, outItems);
    }

    @Override
    protected void post(IR ir) {
        for (SSAInstruction i : inputItems.keySet()) {
            VarContext<NonNullAbsVal> input = confluence(inputItems.get(i));
            Set<Integer> nonNulls = new HashSet<>();
            for (Integer j : input.getLocals()) {
                if (input.getLocal(j).isNonnull()) {
                    nonNulls.add(j);
                }
            }
            ((NonNullManager) manager).replaceNonNull(nonNulls, i, currentNode);
        }
        super.post(ir);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowBinaryOp(SSABinaryOpInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // no pointers
        return confluence(previousItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowComparison(SSAComparisonInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // no pointers
        return confluence(previousItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowConversion(SSAConversionInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // no pointers
        return confluence(previousItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        return in.setLocal(i.getException(), NonNullAbsVal.NON_NULL);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowGetStatic(SSAGetInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Not flow-sensitive for fields, so we cannot determine whether they
        // are null or not

        return confluence(previousItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowInstanceOf(SSAInstanceofInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {

        // if the instanceof check is successful then the object cannot be null,
        // but that comparison isn't handled until there is a branch
        // TODO be smarter about instanceof in NonNull

        return confluence(previousItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowPhi(SSAPhiInstruction i, Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);

        NonNullAbsVal val = NonNullAbsVal.NON_NULL;
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            val = val.join(in.getLocal(i.getUse(j)));
        }

        return in.setLocal(i.getDef(), val);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowPutStatic(SSAPutInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {

        // Not flow-sensitive for fields, so we cannot determine whether they
        // are null or not

        return confluence(previousItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowUnaryNegation(SSAUnaryOpInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // no pointers
        return confluence(previousItems);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowArrayLength(SSAArrayLengthInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normal = in.setLocal(i.getArrayRef(), NonNullAbsVal.NON_NULL);
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getArrayRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normal, npe, preciseEx.getImpossibleSuccessors(current, currentNode), current,
                                        cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowArrayLoad(SSAArrayLoadInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);

        VarContext<NonNullAbsVal> normal = in.setLocal(i.getArrayRef(), NonNullAbsVal.NON_NULL);
        Map<Integer, VarContext<NonNullAbsVal>> out = new LinkedHashMap<>();
        int normalSucc = cfg.getSuccNodeNumbers(current).intIterator().next();
        out.put(normalSucc, normal);

        // If not null then may throw an ArrayIndexOutOfBoundsException
        VarContext<NonNullAbsVal> indexOOB = normal.setExceptionValue(NonNullAbsVal.NON_NULL);
        Set<ISSABasicBlock> possibleIOOB = getSuccessorsForExceptionType(
                                        TypeReference.JavaLangArrayIndexOutOfBoundsException, cfg, current,
                                        util.getClassHierarchy());
        possibleIOOB.removeAll(preciseEx.getImpossibleSuccessors(current, currentNode));
        for (ISSABasicBlock succ : possibleIOOB) {
            out.put(succ.getGraphNodeId(), indexOOB);
        }

        VarContext<NonNullAbsVal> npe = in.setLocal(i.getArrayRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);
        Set<ISSABasicBlock> possibleNPE = getSuccessorsForExceptionType(TypeReference.JavaLangNullPointerException,
                                        cfg, current, util.getClassHierarchy());
        possibleNPE.removeAll(preciseEx.getImpossibleSuccessors(current, currentNode));
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
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);

        VarContext<NonNullAbsVal> normal = in.setLocal(i.getArrayRef(), NonNullAbsVal.NON_NULL);
        Map<Integer, VarContext<NonNullAbsVal>> out = new LinkedHashMap<>();
        int normalSucc = cfg.getSuccNodeNumbers(current).intIterator().next();
        out.put(normalSucc, normal);

        // If not null then may throw an ArrayIndexOutOfBoundsException or
        // ArrayStoreException
        VarContext<NonNullAbsVal> otherEx = normal.setExceptionValue(NonNullAbsVal.NON_NULL);
        Set<ISSABasicBlock> possibleOtherEx = getSuccessorsForExceptionType(
                                        TypeReference.JavaLangArrayIndexOutOfBoundsException, cfg, current,
                                        util.getClassHierarchy());
        possibleOtherEx.addAll(getSuccessorsForExceptionType(TypeReference.JavaLangArrayStoreException, cfg, current,
                                        util.getClassHierarchy()));
        possibleOtherEx.removeAll(preciseEx.getImpossibleSuccessors(current, currentNode));
        for (ISSABasicBlock succ : possibleOtherEx) {
            out.put(succ.getGraphNodeId(), otherEx);
        }

        VarContext<NonNullAbsVal> npe = in.setLocal(i.getArrayRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);
        Set<ISSABasicBlock> possibleNPE = getSuccessorsForExceptionType(TypeReference.JavaLangNullPointerException,
                                        cfg, current, util.getClassHierarchy());
        possibleNPE.removeAll(preciseEx.getImpossibleSuccessors(current, currentNode));
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
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> exception = in.setExceptionValue(NonNullAbsVal.NON_NULL);
        // "null" can be cast to anything so if we get a ClassCastException then
        // we know the casted object was not null
        exception = exception.setLocal(i.getUse(0), NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(in, exception, preciseEx.getImpossibleSuccessors(current, currentNode), current,
                                        cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert getNumSuccs(current, cfg) == 2 : "Not two successors for a conditional branch: "
                                        + PrettyPrinter.instructionString(currentNode.getIR(), i) + " has "
                                        + getNumSuccs(current, cfg);

        VarContext<NonNullAbsVal> in = confluence(previousItems);

        // By default both branches are the same as the input
        Map<Integer, VarContext<NonNullAbsVal>> out = itemToModifiableMap(in, current, cfg);

        // Check if the comparison is an comparison against the null literal
        IR ir = currentNode.getIR();
        Value fst = ir.getSymbolTable().getValue(i.getUse(0));
        Value snd = ir.getSymbolTable().getValue(i.getUse(1));
        boolean fstNull = fst != null && fst.isNullConstant();
        boolean sndNull = snd != null && snd.isNullConstant();
        if (fstNull || sndNull) {
            VarContext<NonNullAbsVal> trueContext = in;
            VarContext<NonNullAbsVal> falseContext = in;
            if (fstNull && !sndNull && i.getOperator() == Operator.EQ) {
                // null == obj
                trueContext = in.setLocal(i.getUse(1), NonNullAbsVal.MAY_BE_NULL);
                falseContext = in.setLocal(i.getUse(1), NonNullAbsVal.NON_NULL);
            }

            if (fstNull && !sndNull && i.getOperator() == Operator.NE) {
                // null != obj
                trueContext = in.setLocal(i.getUse(1), NonNullAbsVal.NON_NULL);
                falseContext = in.setLocal(i.getUse(1), NonNullAbsVal.MAY_BE_NULL);
            }

            if (sndNull && !fstNull && i.getOperator() == Operator.EQ) {
                // obj == null
                trueContext = in.setLocal(i.getUse(0), NonNullAbsVal.MAY_BE_NULL);
                falseContext = in.setLocal(i.getUse(0), NonNullAbsVal.NON_NULL);
            }

            if (sndNull && !fstNull && i.getOperator() == Operator.NE) {
                // obj != null
                trueContext = in.setLocal(i.getUse(0), NonNullAbsVal.NON_NULL);
                falseContext = in.setLocal(i.getUse(0), NonNullAbsVal.MAY_BE_NULL);
            }

            out.put(getTrueSuccessor(current, cfg), trueContext);
            out.put(getFalseSuccessor(current, cfg), falseContext);
        }

        return out;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowGetField(SSAGetInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normal = in.setLocal(i.getRef(), NonNullAbsVal.NON_NULL);
        
        NonNullAbsVal newValue = null;
        for (AbstractLocation loc : locationsForField(i.getRef(), i.getDeclaredField())) {
            newValue = VarContext.safeJoinValues(newValue, in.getLocation(loc));
        }
        normal = normal.setLocal(i.getDef(), newValue);
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);

        // Not flow-sensitive for fields, so we cannot determine whether they
        // are null or not

        return factsToMapWithExceptions(normal, npe, preciseEx.getImpossibleSuccessors(current, currentNode), current,
                                        cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowGoto(SSAGotoInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeInterface(SSAInvokeInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeSpecial(SSAInvokeInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeStatic(SSAInvokeInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeVirtual(SSAInvokeInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowLoadMetadata(SSALoadMetadataInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normalOut = in.setLocal(i.getDef(), NonNullAbsVal.NON_NULL);
        VarContext<NonNullAbsVal> exception = in.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normalOut, exception, preciseEx.getImpossibleSuccessors(current, currentNode),
                                        current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowMonitor(SSAMonitorInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normal = in.setLocal(i.getRef(), NonNullAbsVal.NON_NULL);
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normal, npe, preciseEx.getImpossibleSuccessors(current, currentNode), current,
                                        cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowNewArray(SSANewInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normalOut = in.setLocal(i.getDef(), NonNullAbsVal.NON_NULL);
        VarContext<NonNullAbsVal> exception = in.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normalOut, exception, preciseEx.getImpossibleSuccessors(current, currentNode),
                                        current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowNewObject(SSANewInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normalOut = in.setLocal(i.getDef(), NonNullAbsVal.NON_NULL);

        // The exception edge is actually for errors so no value needs to be
        // passed on that edge (it will always be impossible)
        return factsToMapWithExceptions(normalOut, null, preciseEx.getImpossibleSuccessors(current, currentNode),
                                        current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowPutField(SSAPutInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normal = in.setLocal(i.getRef(), NonNullAbsVal.NON_NULL);
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);

        // Not flow-sensitive for fields, so we cannot determine whether they
        // are null or not

        return factsToMapWithExceptions(normal, npe, preciseEx.getImpossibleSuccessors(current, currentNode), current,
                                        cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowReturn(SSAReturnInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> out = in.setReturnResult(in.getLocal(i.getResult()));

        return factToMap(out, current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowSwitch(SSASwitchInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // no pointers
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowThrow(SSAThrowInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);

        VarContext<NonNullAbsVal> normal = in.setReturnResult(null);
        normal = normal.setExceptionValue(NonNullAbsVal.NON_NULL);
        normal = normal.setLocal(i.getException(), NonNullAbsVal.NON_NULL);

        VarContext<NonNullAbsVal> npe = in.setReturnResult(null);
        npe = normal.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normal, npe, preciseEx.getImpossibleSuccessors(current, currentNode), current,
                                        cfg);
    }
}
