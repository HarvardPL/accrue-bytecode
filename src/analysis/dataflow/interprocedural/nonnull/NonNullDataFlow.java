package analysis.dataflow.interprocedural.nonnull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
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
    private final PreciseExceptionResults preciseEx;
    /**
     * WALA classes
     */
    private final WalaAnalysisUtil util;

    public NonNullDataFlow(CGNode currentNode, NonNullManager manager, PreciseExceptionResults preciseEx,
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
        if (!i.isStatic() && in.getLocal(i.getReceiver()) == NonNullAbsVal.MAY_BE_NULL) {
            npe = in.setExceptionValue(NonNullAbsVal.NON_NULL);
            npe = in.setLocal(i.getReceiver(), NonNullAbsVal.MAY_BE_NULL);
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
        for (CGNode callee : cg.getPossibleTargets(currentNode, i.getCallSite())) {
            Map<ExitType, VarContext<NonNullAbsVal>> out;
            int[] formals;
            VarContext<NonNullAbsVal> initial = nonNull.clearLocalsAndExits();
            if (callee.getMethod().isNative()) {
                System.err.println(PrettyPrinter.parseMethod(callee.getMethod()) + " is NATIVE");
                // Create fake formals so they can be restored at the end
                formals = new int[numParams];
                for (int j = 0; j < numParams; j++) {
                    formals[j] = j;
                }
            } else {
                formals = callee.getIR().getParameterValueNumbers();
            }

            for (int j = 0; j < numParams; j++) {
                NonNullAbsVal actualVal = nonNull.getLocal(actuals.get(j));
                if (actualVal == null) {
                    if (TypeRepository.getType(actuals.get(j), currentNode.getIR()).isPrimitiveType()) {
                        actualVal = NonNullAbsVal.NON_NULL;
                    } else if (currentNode.getIR().getSymbolTable().isConstant(actuals.get(j))) {
                        if (currentNode.getIR().getSymbolTable().isNullConstant(actuals.get(j))) {
                            actualVal = NonNullAbsVal.MAY_BE_NULL;
                        } else if (currentNode.getIR().getSymbolTable().isStringConstant(actuals.get(j))) {
                            actualVal = NonNullAbsVal.NON_NULL;
                        }
                    } else {
                        throw new RuntimeException("null NonNullAbsValue for non-primitive non-constant.");
                    }
                }
                initial = initial.setLocal(formals[j], actualVal);
            }
            out = manager.getResults(currentNode, callee, initial);
            assert out != null : "Null data-flow results for: " + PrettyPrinter.parseCGNode(callee) + "\nFrom "
                                            + PrettyPrinter.parseCGNode(currentNode);

            // join the formals to the previous formals
            VarContext<NonNullAbsVal> normal = out.get(ExitType.NORM_TERM);
            assert normal != null;
            if (!isVoid) {
                calleeReturn = normal.getReturnResult().join(calleeReturn);
            }
            for (int j = 0; j < formals.length; j++) {
                NonNullAbsVal newVal = normal.getLocal(formals[j]).join(newNormalActualValues.get(j));
                newNormalActualValues.set(j, newVal);
            }

            VarContext<NonNullAbsVal> exception = out.get(ExitType.EXCEPTION);
            if (exception != null) {
                // If the method can throw an exception record any changes to
                // the arguments
                for (int j = 0; j < formals.length; j++) {
                    NonNullAbsVal newVal = exception.getLocal(formals[j]).join(newExceptionActualValues.get(j));
                    newExceptionActualValues.set(j, newVal);
                }
            }
        }

        Map<Integer, VarContext<NonNullAbsVal>> ret = new LinkedHashMap<>();

        // Normal return
        VarContext<NonNullAbsVal> normal = null;
        if (!isVoid) {
            normal = nonNull.setLocal(i.getReturnValue(0), calleeReturn);
            normal = updateActuals(newNormalActualValues, actuals, normal);
        } else {
            normal = updateActuals(newNormalActualValues, actuals, nonNull);
        }

        for (ISSABasicBlock normalSucc : cfg.getNormalSuccessors(bb)) {
            assert normal != null : "Should be non-null if there is a normal successor.";
            ret.put(normalSucc.getGraphNodeId(), normal);
        }

        // Exceptional return
        VarContext<NonNullAbsVal> callerExContext = nonNull.setLocal(i.getException(), NonNullAbsVal.NON_NULL)
                                        .setExceptionValue(NonNullAbsVal.NON_NULL);
        callerExContext = updateActuals(newExceptionActualValues, actuals, callerExContext);
        Set<ISSABasicBlock> npeSuccs = getSuccessorsForExceptionType(TypeReference.JavaLangNullPointerException, cfg,
                                        bb, util.getClassHierarchy(),
                                        preciseEx.getImpossibleExceptions(bb, currentNode));

        for (ISSABasicBlock exSucc : cfg.getExceptionalSuccessors(bb)) {
            if (!i.isStatic() && in.getLocal(i.getReceiver()) == NonNullAbsVal.MAY_BE_NULL && npeSuccs.contains(exSucc)) {
                ret.put(exSucc.getGraphNodeId(), npe.join(callerExContext));
            } else {
                ret.put(exSucc.getGraphNodeId(), nonNull.join(callerExContext));
            }
        }

        return ret;
    }

    /**
     * The values for the local variables (in the caller) passed into the
     * procedure can be changed by the callee. This method copies the new value
     * into the variable context.
     * 
     * @param newActualValues
     *            abstract values for the local variables passed in after
     *            analyzing a procedure call
     * @param actuals
     *            value numbers for actual arguments
     * @param to
     *            context to copy into
     * @return new context with values copied in
     */
    private VarContext<NonNullAbsVal> updateActuals(List<NonNullAbsVal> newActualValues, List<Integer> actuals,
                                    VarContext<NonNullAbsVal> to) {
        for (int j = 1; j < actuals.size(); j++) {
            to = to.setLocal(actuals.get(j), newActualValues.get(j));
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
                                            && !preciseEx.getImpossibleExceptions(justProcessed, currentNode).contains(
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
            ((NonNullManager) manager).getNonNullResults().replaceNonNull(nonNulls, i, currentNode);
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

        VarContext<NonNullAbsVal> in = confluence(previousItems);
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
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        if (i.getDeclaredFieldType().isPrimitiveType()) {
            return in;
        }
        VarContext<NonNullAbsVal> out = in.setLocation(AbstractLocation.createStatic(i.getDeclaredField()),
                                        in.getLocal(i.getVal()));
        return out;
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

        return factsToMapWithExceptions(normal, npe, preciseEx.getImpossibleExceptions(current, currentNode), current,
                                        cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowArrayLoad(SSAArrayLoadInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);

        VarContext<NonNullAbsVal> normal = in.setLocal(i.getArrayRef(), NonNullAbsVal.NON_NULL);
        NonNullAbsVal val = null;
        for (AbstractLocation loc : getLocationsForArrayContents(i.getArrayRef())) {
            val = VarContext.safeJoinValues(val, in.getLocation(loc));
        }
        normal = normal.setLocal(i.getDef(), val);

        Map<Integer, VarContext<NonNullAbsVal>> out = new LinkedHashMap<>();
        for (ISSABasicBlock normalSucc : cfg.getNormalSuccessors(current)) {
            out.put(normalSucc.getGraphNodeId(), normal);
        }

        // If not null then may throw an ArrayIndexOutOfBoundsException
        VarContext<NonNullAbsVal> indexOOB = normal.setExceptionValue(NonNullAbsVal.NON_NULL);
        Set<ISSABasicBlock> possibleIOOB = getSuccessorsForExceptionType(
                                        TypeReference.JavaLangArrayIndexOutOfBoundsException, cfg, current,
                                        util.getClassHierarchy(),
                                        preciseEx.getImpossibleExceptions(current, currentNode));
        possibleIOOB.removeAll(preciseEx.getImpossibleExceptions(current, currentNode));
        for (ISSABasicBlock succ : possibleIOOB) {
            out.put(succ.getGraphNodeId(), indexOOB);
        }

        VarContext<NonNullAbsVal> npe = in.setLocal(i.getArrayRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);
        Set<ISSABasicBlock> possibleNPE = getSuccessorsForExceptionType(TypeReference.JavaLangNullPointerException,
                                        cfg, current, util.getClassHierarchy(),
                                        preciseEx.getImpossibleExceptions(current, currentNode));
        possibleNPE.removeAll(preciseEx.getImpossibleExceptions(current, currentNode));
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

        NonNullAbsVal val = in.getLocal(i.getValue());
        for (AbstractLocation loc : getLocationsForArrayContents(i.getArrayRef())) {
            normal = normal.setLocation(loc, val);
        }

        Map<Integer, VarContext<NonNullAbsVal>> out = new LinkedHashMap<>();
        for (ISSABasicBlock normalSucc : cfg.getNormalSuccessors(current)) {
            out.put(normalSucc.getGraphNodeId(), normal);
        }

        // If not null then may throw an ArrayIndexOutOfBoundsException or
        // ArrayStoreException
        VarContext<NonNullAbsVal> otherEx = normal.setExceptionValue(NonNullAbsVal.NON_NULL);
        Set<ISSABasicBlock> possibleOtherEx = getSuccessorsForExceptionType(
                                        TypeReference.JavaLangArrayIndexOutOfBoundsException, cfg, current,
                                        util.getClassHierarchy(),
                                        preciseEx.getImpossibleExceptions(current, currentNode));
        possibleOtherEx.addAll(getSuccessorsForExceptionType(TypeReference.JavaLangArrayStoreException, cfg, current,
                                        util.getClassHierarchy(),
                                        preciseEx.getImpossibleExceptions(current, currentNode)));
        possibleOtherEx.removeAll(preciseEx.getImpossibleExceptions(current, currentNode));
        for (ISSABasicBlock succ : possibleOtherEx) {
            out.put(succ.getGraphNodeId(), otherEx);
        }

        VarContext<NonNullAbsVal> npe = in.setLocal(i.getArrayRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);
        Set<ISSABasicBlock> possibleNPE = getSuccessorsForExceptionType(TypeReference.JavaLangNullPointerException,
                                        cfg, current, util.getClassHierarchy(),
                                        preciseEx.getImpossibleExceptions(current, currentNode));
        possibleNPE.removeAll(preciseEx.getImpossibleExceptions(current, currentNode));
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

        return factsToMapWithExceptions(in, exception, preciseEx.getImpossibleExceptions(current, currentNode),
                                        current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert getNumSuccs(current, cfg) == 2 : "Not two successors for a conditional branch: "
                                        + PrettyPrinter.instructionString(i, currentNode.getIR()) + " has "
                                        + getNumSuccs(current, cfg);

        VarContext<NonNullAbsVal> in = confluence(previousItems);

        // By default both branches are the same as the input
        Map<Integer, VarContext<NonNullAbsVal>> out = factToModifiableMap(in, current, cfg);

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

        NonNullAbsVal newValue = null;
        for (AbstractLocation loc : getLocationsForNonStaticField(i.getRef(), i.getDeclaredField())) {
            newValue = VarContext.safeJoinValues(newValue, in.getLocation(loc));
        }

        VarContext<NonNullAbsVal> normal = in.setLocal(i.getRef(), NonNullAbsVal.NON_NULL);
        normal = normal.setLocal(i.getDef(), newValue);
        VarContext<NonNullAbsVal> npe = in.setLocal(i.getRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normal, npe, preciseEx.getImpossibleExceptions(current, currentNode), current,
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

        return factsToMapWithExceptions(normalOut, exception, preciseEx.getImpossibleExceptions(current, currentNode),
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

        return factsToMapWithExceptions(normal, npe, preciseEx.getImpossibleExceptions(current, currentNode), current,
                                        cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowNewArray(SSANewInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normalOut = in.setLocal(i.getDef(), NonNullAbsVal.NON_NULL);
        VarContext<NonNullAbsVal> exception = in.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normalOut, exception, preciseEx.getImpossibleExceptions(current, currentNode),
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
        return factsToMapWithExceptions(normalOut, null, preciseEx.getImpossibleExceptions(current, currentNode),
                                        current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowPutField(SSAPutInstruction i,
                                    Set<VarContext<NonNullAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normal = in.setLocal(i.getRef(), NonNullAbsVal.NON_NULL);
        NonNullAbsVal inVal = in.getLocal(i.getVal());
        for (AbstractLocation loc : getLocationsForNonStaticField(i.getRef(), i.getDeclaredField())) {
            normal = normal.setLocation(loc, inVal);
        }

        VarContext<NonNullAbsVal> npe = in.setLocal(i.getRef(), NonNullAbsVal.MAY_BE_NULL);
        npe = npe.setExceptionValue(NonNullAbsVal.NON_NULL);

        return factsToMapWithExceptions(normal, npe, preciseEx.getImpossibleExceptions(current, currentNode), current,
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

        return factsToMapWithExceptions(normal, npe, preciseEx.getImpossibleExceptions(current, currentNode), current,
                                        cfg);
    }
}
