package analysis.dataflow.interprocedural.nonnull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.IntraproceduralDataFlow;
import analysis.dataflow.util.AbstractLocation;
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
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.ssa.Value;
import com.ibm.wala.types.TypeReference;

/**
 * Inter-procedural analysis that determines which local variable can be null at a particular program point
 */
public class NonNullDataFlow extends IntraproceduralDataFlow<VarContext<NonNullAbsVal>> {

    /**
     * Type inference results
     */
    private final SymbolTable st;

    /**
     * Intra-procedural part of an inter-procedural non-null analysis
     *
     * @param currentNode
     *            call graph node to analyze
     * @param interProc
     *            inter-procedural analysis this is a part of
     */
    public NonNullDataFlow(CGNode currentNode, NonNullInterProceduralDataFlow interProc) {
        super(currentNode, interProc);
        this.st = currentNode.getIR().getSymbolTable();
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> call(SSAInvokeInstruction i,
                                    Set<VarContext<NonNullAbsVal>> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb) {
        boolean returnAlwaysNonNull = i.getDeclaredTarget().getReturnType().isPrimitiveType();

        VarContext<NonNullAbsVal> in = confluence(inItems, bb);
        VarContext<NonNullAbsVal> nonNull = in;
        if (!i.isStatic()) {
            nonNull = in.setLocal(i.getReceiver(), NonNullAbsVal.NON_NULL);
        }

        int numParams = i.getNumberOfParameters();
        List<Integer> actuals = new ArrayList<>(numParams);
        List<NonNullAbsVal> newNormalActualValues = new ArrayList<>(numParams);
        List<NonNullAbsVal> newExceptionActualValues = new ArrayList<>(numParams);
        for (int j = 0; j < numParams; j++) {
            actuals.add(i.getUse(j));
            newNormalActualValues.add(null);
            newExceptionActualValues.add(null);
        }

        NonNullAbsVal calleeReturn = null;
        boolean canThrowException = false;
        boolean canTerminateNormally = false;
        Set<CGNode> targets = cg.getPossibleTargets(currentNode, i.getCallSite());

        if (targets.isEmpty()) {
            return guessResultsForMissingReceiver(i, nonNull, cfg, bb);
        }

        for (CGNode callee : targets) {
            Map<ExitType, VarContext<NonNullAbsVal>> out;
            int[] formals;
            VarContext<NonNullAbsVal> initial = nonNull.clearLocalsAndExits();
            if (callee.getMethod().isNative() && !AnalysisUtil.hasSignature(callee.getMethod())) {
                // Create fake formals so they can be restored at the end
                formals = new int[numParams];
                for (int j = 0; j < numParams; j++) {
                    formals[j] = j;
                }
            } else {
                formals = callee.getIR().getParameterValueNumbers();
            }

            for (int j = 0; j < numParams; j++) {
                NonNullAbsVal actualVal = getLocal(actuals.get(j), nonNull);
                if (actualVal == null) {
                    if (i.getDeclaredTarget().getParameterType(j).isPrimitiveType()) {
                        actualVal = NonNullAbsVal.NON_NULL;
                    } else if (currentNode.getIR().getSymbolTable().isConstant(actuals.get(j))) {
                        if (currentNode.getIR().getSymbolTable().isNullConstant(actuals.get(j))) {
                            actualVal = NonNullAbsVal.MAY_BE_NULL;
                        } else if (currentNode.getIR().getSymbolTable().isStringConstant(actuals.get(j))) {
                            actualVal = NonNullAbsVal.NON_NULL;
                        }
                    } else {
                        System.out.println(PrettyPrinter.methodString(callee.getMethod()));
                        throw new RuntimeException("null NonNullAbsValue for non-primitive non-constant.");
                    }
                }
                initial = initial.setLocal(formals[j], actualVal);
            }
            out = interProc.getResults(currentNode, callee, initial);
            assert out != null : "Null data-flow results for: " + PrettyPrinter.cgNodeString(callee) + "\nFrom "
                                            + PrettyPrinter.cgNodeString(currentNode);

            // join the formals to the previous formals
            VarContext<NonNullAbsVal> normal = out.get(ExitType.NORMAL);
            if (normal != null) {
                canTerminateNormally = true;
                if (!returnAlwaysNonNull) {
                    assert normal.getReturnResult() != null : "null return for "
                                                    + PrettyPrinter.methodString(i.getDeclaredTarget());
                    calleeReturn = normal.getReturnResult().join(calleeReturn);
                }
                for (int j = 0; j < formals.length; j++) {
                    NonNullAbsVal newVal = getLocal(formals[j], normal).join(newNormalActualValues.get(j));
                    newNormalActualValues.set(j, newVal);
                }
            }

            VarContext<NonNullAbsVal> exception = out.get(ExitType.EXCEPTIONAL);
            if (exception != null) {
                canThrowException = true;
                // If the method can throw an exception record any changes to
                // the arguments
                for (int j = 0; j < formals.length; j++) {
                    assert getLocal(formals[j], exception) != null;
                    NonNullAbsVal newVal = getLocal(formals[j], exception).join(newExceptionActualValues.get(j));
                    newExceptionActualValues.set(j, newVal);
                }
            }
        }

        Map<ISSABasicBlock, VarContext<NonNullAbsVal>> ret = new LinkedHashMap<>();

        // Normal return
        if (canTerminateNormally) {
            VarContext<NonNullAbsVal> normal = null;
            if (!returnAlwaysNonNull) {
                normal = nonNull.setLocal(i.getReturnValue(0), calleeReturn);
                normal = updateActuals(newNormalActualValues, actuals, normal);
            } else {
                normal = updateActuals(newNormalActualValues, actuals, nonNull);
            }

            for (ISSABasicBlock normalSucc : getNormalSuccs(bb, cfg)) {
                if (!isUnreachable(bb, normalSucc)) {
                    assert normal != null : "Should be non-null if there is a normal successor.";
                    ret.put(normalSucc, normal);
                }
            }
        }

        Set<ISSABasicBlock> npeSuccs = null;
        VarContext<NonNullAbsVal> npe = null;
        if (!i.isStatic()) {
            npeSuccs = getSuccessorsForExceptionType(TypeReference.JavaLangNullPointerException, cfg, bb);
            npe = in.setExceptionValue(NonNullAbsVal.NON_NULL);
            npe = in.setLocal(i.getReceiver(), NonNullAbsVal.MAY_BE_NULL);
        }

        // Exceptional return
        if (canThrowException) {
            VarContext<NonNullAbsVal> callerExContext = nonNull.setExceptionValue(NonNullAbsVal.NON_NULL);
            callerExContext = updateActuals(newExceptionActualValues, actuals, callerExContext);

            for (ISSABasicBlock exSucc : getExceptionalSuccs(bb, cfg)) {
                if (!isUnreachable(bb, exSucc)) {
                    if (npeSuccs != null && npeSuccs.contains(exSucc)) {
                        assert npe != null : "Null NPE context when an NPE can be thrown.";
                        // If this edge could be an NPE then join it with the
                        // callerEx
                        // TODO only join contexts if the callee could throw one
                        ret.put(exSucc, npe.join(callerExContext));
                    } else {
                        assert nonNull != null : "Null context when an NPE cannot be thrown.";
                        ret.put(exSucc, nonNull.join(callerExContext));
                    }
                }
            }
        } else {
            for (ISSABasicBlock exSucc : getExceptionalSuccs(bb, cfg)) {
                if (!isUnreachable(bb, exSucc)) {
                    ret.put(exSucc, npe);
                }
            }
        }

        return ret;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> guessResultsForMissingReceiver(SSAInvokeInstruction i,
                                    VarContext<NonNullAbsVal> input,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb) {
        if (outputLevel >= 1) {
            System.err.println("No calls to " + PrettyPrinter.methodString(i.getDeclaredTarget()) + " from "
                                            + PrettyPrinter.cgNodeString(currentNode));
        }
        // TODO This is unsound as some of the formals could be set to null inside the missing procedure
        VarContext<NonNullAbsVal> normal = input;
        if (!i.getDeclaredTarget().getReturnType().isPrimitiveType()) {
            normal = normal.setLocal(i.getReturnValue(0), NonNullAbsVal.MAY_BE_NULL);
        }
        VarContext<NonNullAbsVal> exception = input.setExceptionValue(NonNullAbsVal.NON_NULL);
        return factsToMapWithExceptions(normal, exception, bb, cfg);
    }

    /**
     * The values for the local variables (in the caller) passed into the procedure can be changed by the callee. This
     * method copies the new value into the variable context.
     *
     * @param newActualValues
     *            abstract values for the local variables passed in after analyzing a procedure call
     * @param actuals
     *            value numbers for actual arguments
     * @param to
     *            context to copy into
     * @return new context with values copied in
     */
    private static VarContext<NonNullAbsVal> updateActuals(List<NonNullAbsVal> newActualValues, List<Integer> actuals,
                                    VarContext<NonNullAbsVal> to) {

        VarContext<NonNullAbsVal> out = to;
        for (int j = 1; j < actuals.size(); j++) {
            out = out.setLocal(actuals.get(j), newActualValues.get(j));
        }
        return out;
    }

    @Override
    protected VarContext<NonNullAbsVal> confluence(Set<VarContext<NonNullAbsVal>> items, ISSABasicBlock bb) {
        assert !items.isEmpty();
        return VarContext.join(items);
    }

    @Override
    protected void postBasicBlock(ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock justProcessed,
                                    Map<ISSABasicBlock, VarContext<NonNullAbsVal>> outItems) {
        for (SSAInstruction i : justProcessed) {
            assert getAnalysisRecord(i) != null : "No analysis record for " + i + " in "
                                            + PrettyPrinter.cgNodeString(currentNode);
            VarContext<NonNullAbsVal> input = confluence(getAnalysisRecord(i).getInput(), justProcessed);
            Set<Integer> nonNulls = new HashSet<>();
            for (Integer j : input.getLocals()) {
                if (getLocal(j, input).isNonnull()) {
                    nonNulls.add(j);
                }
            }
            ((NonNullInterProceduralDataFlow) interProc).getAnalysisResults().replaceNonNull(nonNulls, i, currentNode);
        }
        super.postBasicBlock(cfg, justProcessed, outItems);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowBinaryOp(SSABinaryOpInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // no pointers
        return confluence(previousItems, current);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowComparison(SSAComparisonInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // no pointers
        return confluence(previousItems, current);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowConversion(SSAConversionInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // no pointers
        return confluence(previousItems, current);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);
        return in.setLocal(i.getException(), NonNullAbsVal.NON_NULL);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowGetStatic(SSAGetInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {

        VarContext<NonNullAbsVal> in = confluence(previousItems, current);
        if (i.getDeclaredFieldType().isPrimitiveType()) {
            return in.setLocal(i.getDef(), NonNullAbsVal.NON_NULL);
        }
        VarContext<NonNullAbsVal> out = in.setLocal(i.getDef(),
                                        in.getLocation(AbstractLocation.createStatic(i.getDeclaredField())));
        return out;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowInstanceOf(SSAInstanceofInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {

        // if the instanceof check is successful then the object cannot be null,
        // but that comparison isn't handled until there is a branch
        // TODO be smarter about instanceof in NonNull

        return confluence(previousItems, current);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowPhi(SSAPhiInstruction i, Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);

        NonNullAbsVal val = NonNullAbsVal.NON_NULL;
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            val = val.join(getLocal(i.getUse(j), in));
        }

        return in.setLocal(i.getDef(), val);
    }

    @Override
    protected VarContext<NonNullAbsVal> flowPutStatic(SSAPutInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);
        if (i.getDeclaredFieldType().isPrimitiveType()) {
            return in;
        }
        VarContext<NonNullAbsVal> out = in.setLocation(AbstractLocation.createStatic(i.getDeclaredField()),
                                        getLocal(i.getVal(), in));
        return out;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowUnaryNegation(SSAUnaryOpInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // no pointers
        return confluence(previousItems, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowArrayLength(SSAArrayLengthInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);
        VarContext<NonNullAbsVal> normal = in.setLocal(i.getArrayRef(), NonNullAbsVal.NON_NULL);
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getArrayRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowArrayLoad(SSAArrayLoadInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);

        VarContext<NonNullAbsVal> normal = in.setLocal(i.getArrayRef(), NonNullAbsVal.NON_NULL);
        NonNullAbsVal val = null;
        for (AbstractLocation loc : interProc.getLocationsForArrayContents(i.getArrayRef(), currentNode)) {
            val = VarContext.safeJoinValues(val, in.getLocation(loc));
        }
        if (val == null) {
            // TODO Array doesn't point to anything, this could be OK, this could be dead code
            // to be safe assume the contents could be null
            val = NonNullAbsVal.MAY_BE_NULL;
        }
        normal = normal.setLocal(i.getDef(), val);

        Map<ISSABasicBlock, VarContext<NonNullAbsVal>> out = new LinkedHashMap<>();
        for (ISSABasicBlock normalSucc : getNormalSuccs(current, cfg)) {
            if (!isUnreachable(current, normalSucc)) {
                out.put(normalSucc, normal);
            }
        }

        // If not null then may throw an ArrayIndexOutOfBoundsException
        VarContext<NonNullAbsVal> indexOOB = normal.setExceptionValue(NonNullAbsVal.NON_NULL);
        Set<ISSABasicBlock> possibleIOOB = getSuccessorsForExceptionType(
                                        TypeReference.JavaLangArrayIndexOutOfBoundsException, cfg, current);
        for (ISSABasicBlock succ : possibleIOOB) {
            out.put(succ, indexOOB);
        }

        VarContext<NonNullAbsVal> npe = in.setLocal(i.getArrayRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);
        Set<ISSABasicBlock> possibleNPE = getSuccessorsForExceptionType(TypeReference.JavaLangNullPointerException,
                                        cfg, current);
        for (ISSABasicBlock succ : possibleNPE) {
            // Note that if a successor can be reached another way in addition
            // to the NPE, the context is the NPE context (since the array may
            // be null) so we put the NPE context in last
            out.put(succ, npe);
        }

        return out;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowArrayStore(SSAArrayStoreInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);

        VarContext<NonNullAbsVal> normal = in.setLocal(i.getArrayRef(), NonNullAbsVal.NON_NULL);

        NonNullAbsVal val = getLocal(i.getValue(), in);
        for (AbstractLocation loc : interProc.getLocationsForArrayContents(i.getArrayRef(), currentNode)) {
            normal = normal.setLocation(loc, val);
        }

        Map<ISSABasicBlock, VarContext<NonNullAbsVal>> out = new LinkedHashMap<>();
        for (ISSABasicBlock normalSucc : getNormalSuccs(current, cfg)) {
            if (!isUnreachable(current, normalSucc)) {
                out.put(normalSucc, normal);
            }
        }

        // If not null then may throw an ArrayIndexOutOfBoundsException or
        // ArrayStoreException
        VarContext<NonNullAbsVal> otherEx = normal.setExceptionValue(NonNullAbsVal.NON_NULL);
        Set<ISSABasicBlock> possibleOtherEx = getSuccessorsForExceptionType(
                                        TypeReference.JavaLangArrayIndexOutOfBoundsException, cfg, current);
        possibleOtherEx.addAll(getSuccessorsForExceptionType(TypeReference.JavaLangArrayStoreException, cfg, current));

        for (ISSABasicBlock succ : possibleOtherEx) {
            out.put(succ, otherEx);
        }

        VarContext<NonNullAbsVal> npe = in.setLocal(i.getArrayRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);
        Set<ISSABasicBlock> possibleNPE = getSuccessorsForExceptionType(TypeReference.JavaLangNullPointerException,
                                        cfg, current);
        for (ISSABasicBlock succ : possibleNPE) {
            // Note that if a successor can be reached another way in addition
            // to the NPE, the context is the NPE context (since the array may
            // be null) so we put the NPE context in last
            out.put(succ, npe);
        }

        return out;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowCheckCast(SSACheckCastInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);
        VarContext<NonNullAbsVal> exception = in.setExceptionValue(NonNullAbsVal.NON_NULL);
        // "null" can be cast to anything so if we get a ClassCastException then
        // we know the casted object was not null
        exception = exception.setLocal(i.getUse(0), NonNullAbsVal.NON_NULL);

        VarContext<NonNullAbsVal> out = in.setLocal(i.getDef(), getLocal(i.getUse(0), in));

        return factsToMapWithExceptions(out, exception, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);

        // By default both branches are the same as the input
        Map<ISSABasicBlock, VarContext<NonNullAbsVal>> out = factToModifiableMap(in, current, cfg);

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

            ISSABasicBlock trueSucc = getTrueSuccessor(current, cfg);
            ISSABasicBlock falseSucc = getFalseSuccessor(current, cfg);
            if (trueSucc != null) {
                out.put(getTrueSuccessor(current, cfg), trueContext);
            }
            if (falseSucc != null) {
                out.put(getFalseSuccessor(current, cfg), falseContext);
            }
        }

        return out;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowGetField(SSAGetInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);

        NonNullAbsVal newValue = null;
        for (AbstractLocation loc : interProc.getLocationsForNonStaticField(i.getRef(), i.getDeclaredField(),
                                        currentNode)) {
            newValue = VarContext.safeJoinValues(newValue, in.getLocation(loc));
        }
        if (newValue == null) {
            // There were no locations found be conservative
            newValue = NonNullAbsVal.MAY_BE_NULL;
        }

        VarContext<NonNullAbsVal> normal = in.setLocal(i.getRef(), NonNullAbsVal.NON_NULL);
        normal = normal.setLocal(i.getDef(), newValue);
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowGoto(SSAGotoInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowInvokeInterface(SSAInvokeInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowInvokeSpecial(SSAInvokeInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowInvokeStatic(SSAInvokeInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowInvokeVirtual(SSAInvokeInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowLoadMetadata(SSALoadMetadataInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);
        VarContext<NonNullAbsVal> normalOut = in.setLocal(i.getDef(), NonNullAbsVal.NON_NULL);
        VarContext<NonNullAbsVal> exception = in.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normalOut, exception, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowMonitor(SSAMonitorInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);
        VarContext<NonNullAbsVal> normal = in.setLocal(i.getRef(), NonNullAbsVal.NON_NULL);
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowNewArray(SSANewInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);
        VarContext<NonNullAbsVal> normalOut = in.setLocal(i.getDef(), NonNullAbsVal.NON_NULL);
        VarContext<NonNullAbsVal> exception = in.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normalOut, exception, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowNewObject(SSANewInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);
        VarContext<NonNullAbsVal> normalOut = in.setLocal(i.getDef(), NonNullAbsVal.NON_NULL);

        // The exception edge is actually for errors so no value needs to be
        // passed on that edge (it will always be impossible)
        return factsToMapWithExceptions(normalOut, null, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowPutField(SSAPutInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);
        VarContext<NonNullAbsVal> normal = in.setLocal(i.getRef(), NonNullAbsVal.NON_NULL);
        NonNullAbsVal inVal = getLocal(i.getVal(), in);
        for (AbstractLocation loc : interProc.getLocationsForNonStaticField(i.getRef(), i.getDeclaredField(),
                                        currentNode)) {
            normal = normal.setLocation(loc, inVal);
        }

        VarContext<NonNullAbsVal> npe = in.setLocal(i.getRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowReturn(SSAReturnInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);
        VarContext<NonNullAbsVal> out = in;
        if (i.getNumberOfUses() > 0) {
            if (currentNode.getIR().getSymbolTable().isNullConstant(i.getResult())) {
                out = in.setReturnResult(NonNullAbsVal.MAY_BE_NULL);
            } else if (i.returnsPrimitiveType()) {
                out = in.setReturnResult(NonNullAbsVal.NON_NULL);
            } else {
                NonNullAbsVal res = getLocal(i.getResult(), in);
                assert res != null : "null NonNullAbsval for local " + i + " in "
                                                + PrettyPrinter.cgNodeString(currentNode);
                out = in.setReturnResult(getLocal(i.getResult(), in));
            }
        }

        return factToMap(out, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowSwitch(SSASwitchInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // no pointers
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<NonNullAbsVal>> flowThrow(SSAThrowInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems, current);

        VarContext<NonNullAbsVal> normal = in.setReturnResult(null);
        normal = normal.setExceptionValue(NonNullAbsVal.NON_NULL);
        normal = normal.setLocal(i.getException(), NonNullAbsVal.NON_NULL);

        VarContext<NonNullAbsVal> npe = in.setReturnResult(null);
        npe = normal.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        return super.isUnreachable(source, target);
    }

    /**
     * Get the abstract value for a local variable
     *
     * @param i
     *            value number for the local
     * @param c
     *            context to look up the value in
     * @return abstract value for the local
     */
    private NonNullAbsVal getLocal(int i, VarContext<NonNullAbsVal> c) {
        NonNullAbsVal val = c.getLocal(i);
        if (val != null) {
            return val;
        }
        if (st.isStringConstant(i)) {
            // Literal string
            return NonNullAbsVal.NON_NULL;
        }
        // Variable is not assigned yet be conservative
        return NonNullAbsVal.MAY_BE_NULL;
    }
}
