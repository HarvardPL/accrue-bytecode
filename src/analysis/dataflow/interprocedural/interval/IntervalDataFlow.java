package analysis.dataflow.interprocedural.interval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.IntraproceduralDataFlow;
import analysis.dataflow.util.AbstractLocation;
import analysis.dataflow.util.VarContext;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.statements.ProgramPoint.InterProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction;
import com.ibm.wala.shrikeBT.IConditionalBranchInstruction;
import com.ibm.wala.shrikeBT.IConditionalBranchInstruction.Operator;
import com.ibm.wala.ssa.ConstantValue;
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

public class IntervalDataFlow extends IntraproceduralDataFlow<VarContext<IntervalAbsVal>> {

    /**
     * Type inference results
     */
    private final TypeRepository types;

    /**
     * Intra-procedural part of an inter-procedural interval analysis.
     *
     * @param currentNode
     * @param interProc
     */
    public IntervalDataFlow(CGNode currentNode, InterproceduralDataFlow<VarContext<IntervalAbsVal>> interProc) {
        super(currentNode, interProc);
        this.types = new TypeRepository(currentNode.getIR());
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> call(SSAInvokeInstruction i,
                                                                   Set<VarContext<IntervalAbsVal>> inItems,
                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                   ISSABasicBlock bb) {

        VarContext<IntervalAbsVal> in = confluence(inItems, bb);

        int numParams = i.getNumberOfParameters();
        List<Integer> actuals = new ArrayList<>(numParams);
        List<IntervalAbsVal> newNormalActualValues = new ArrayList<>(numParams);
        List<IntervalAbsVal> newExceptionActualValues = new ArrayList<>(numParams);
        for (int j = 0; j < numParams; j++) {
            actuals.add(i.getUse(j));
            newNormalActualValues.add(null);
            newExceptionActualValues.add(null);
        }

        IntervalAbsVal calleeReturn = null;
        boolean canThrowException = false;
        boolean canTerminateNormally = false;
        Set<CGNode> targets = cg.getPossibleTargets(currentNode, i.getCallSite());

        if (targets.isEmpty()) {
            return guessResultsForMissingReceiver(i, in, cfg, bb);
        }

        Map<AbstractLocation, IntervalAbsVal> normalLocations = new LinkedHashMap<>();
        Map<AbstractLocation, IntervalAbsVal> exceptionalLocations = new LinkedHashMap<>();

        for (CGNode callee : targets) {
            Map<ExitType, VarContext<IntervalAbsVal>> out;
            int[] formals;
            VarContext<IntervalAbsVal> initial = in.clearLocalsAndExits();
            boolean fakeFormals = callee.getMethod().isNative() && !AnalysisUtil.hasSignature(callee.getMethod());

            if (fakeFormals) {
                // Create fake formals so they can be restored at the end
                formals = new int[numParams];
                for (int j = 0; j < numParams; j++) {
                    formals[j] = j;
                }
            }
            else {
                formals = callee.getIR().getParameterValueNumbers();
            }

            for (int j = 0; j < numParams; j++) {
                IntervalAbsVal actualVal = getIntervalOfLocalOrNumber(in, actuals.get(j));
                if (actualVal != null) {
                    initial = initial.setLocal(formals[j], actualVal);
                }
            }

            out = interProc.getResults(currentNode, callee, initial);

            // join the formals to the previous formals
            VarContext<IntervalAbsVal> normal = out.get(ExitType.NORMAL);
            if (normal != null) {
                canTerminateNormally = true;
                if (normal.getReturnResult() != null) {
                    calleeReturn = normal.getReturnResult().join(calleeReturn);
                }
                for (int j = 0; j < formals.length; j++) {
                    if (types.getType(i.getUse(j)).isPrimitiveType() && !fakeFormals) {
                        IntervalAbsVal newVal = getIntervalOfLocalOrNumber(normal, formals[j]).join(newNormalActualValues.get(j));
                        newNormalActualValues.set(j, newVal);
                    }
                }
                joinLocations(normalLocations, normal);
            }

            VarContext<IntervalAbsVal> exception = out.get(ExitType.EXCEPTIONAL);
            if (exception != null) {
                canThrowException = true;
                // If the method can throw an exception record any changes to the arguments
                for (int j = 0; j < formals.length; j++) {
                    if (types.getType(i.getUse(j)).isPrimitiveType() && !fakeFormals) {
                        IntervalAbsVal newVal = getIntervalOfLocalOrNumber(exception, formals[j]).join(newExceptionActualValues.get(j));
                        newExceptionActualValues.set(j, newVal);
                    }
                }
                joinLocations(exceptionalLocations, exception);
            }
        } // End of handling single callee

        Map<ISSABasicBlock, VarContext<IntervalAbsVal>> ret = new LinkedHashMap<>();

        // Normal return
        if (canTerminateNormally) {
            VarContext<IntervalAbsVal> normal = null;
            if (i.getNumberOfReturnValues() == 1 && calleeReturn != null) {
                normal = in.setLocal(i.getReturnValue(0), calleeReturn);
                normal = updateActuals(newNormalActualValues, actuals, normal);
            }
            else {
                normal = updateActuals(newNormalActualValues, actuals, in);
            }
            normal = normal.setLocations(normalLocations);

            for (ISSABasicBlock normalSucc : getNormalSuccs(bb, cfg)) {
                if (!isUnreachable(bb, normalSucc)) {
                    assert normal != null : "Should be non-null if there is a normal successor.";
                    ret.put(normalSucc, normal);
                }
            }
        }

        // Exceptional return
        if (canThrowException) {
            VarContext<IntervalAbsVal> callerExContext = updateActuals(newExceptionActualValues, actuals, in);
            callerExContext = callerExContext.setLocations(exceptionalLocations);

            for (ISSABasicBlock exSucc : getExceptionalSuccs(bb, cfg)) {
                if (!isUnreachable(bb, exSucc)) {
                    ret.put(exSucc, in.join(callerExContext));
                }
            }
        }
        else {
            for (ISSABasicBlock exSucc : getExceptionalSuccs(bb, cfg)) {
                if (!isUnreachable(bb, exSucc)) {
                    ret.put(exSucc, in);
                }
            }
        }

        return ret;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> guessResultsForMissingReceiver(SSAInvokeInstruction i,
                                                                                             VarContext<IntervalAbsVal> input,
                                                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                             ISSABasicBlock bb) {
        VarContext<IntervalAbsVal> normal = input;
        if (!i.getDeclaredTarget().getReturnType().isPrimitiveType()) {
            normal = normal.setLocal(i.getReturnValue(0), IntervalAbsVal.TOP_ELEMENT);
        }
        return factsToMapWithExceptions(normal, input, bb, cfg);
    }

    @Override
    protected VarContext<IntervalAbsVal> confluence(Set<VarContext<IntervalAbsVal>> intvl, ISSABasicBlock bb) {
        assert !intvl.isEmpty();
        return VarContext.join(intvl);
    }

    @Override
    protected VarContext<IntervalAbsVal> flowBinaryOp(SSABinaryOpInstruction i,
                                                      Set<VarContext<IntervalAbsVal>> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);
        IntervalAbsVal interval1 = getIntervalOfLocalOrNumber(in, i.getUse(0));
        IntervalAbsVal interval2 = getIntervalOfLocalOrNumber(in, i.getUse(1));

        IBinaryOpInstruction.Operator op = (IBinaryOpInstruction.Operator) i.getOperator();
        IntervalAbsVal joined = null;

        switch (op) {
        case ADD:
            joined = new IntervalAbsVal(interval1.min + interval2.min, interval1.max + interval2.max);
            break;
        case SUB:
            joined = new IntervalAbsVal(interval1.min - interval2.max, interval1.max - interval2.min);
            break;
        case MUL:
            double[] possibleMul = { interval1.min * interval2.min, interval1.min * interval2.max,
                    interval1.max * interval2.min, interval1.max * interval2.max };
            Arrays.sort(possibleMul);
            joined = new IntervalAbsVal(possibleMul[0], possibleMul[3]);
            break;
        case DIV:
            if (interval2.containsZero()) {
                if (interval1.containsZero()) {
                    joined = new IntervalAbsVal(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                }
                else if (interval1.min > 0) {
                    double minBoundary = interval2.min == 0. ? interval1.min / interval2.max : Double.NEGATIVE_INFINITY;
                    double maxBoundary = interval2.max == 0. ? interval1.min / interval2.min : Double.POSITIVE_INFINITY;
                    joined = new IntervalAbsVal(minBoundary, maxBoundary);
                }
                else {
                    double minBoundary = interval2.max == 0. ? interval1.max / interval2.min : Double.NEGATIVE_INFINITY;
                    double maxBoundary = interval2.min == 0. ? interval1.max / interval2.max : Double.POSITIVE_INFINITY;
                    joined = new IntervalAbsVal(minBoundary, maxBoundary);
                }
            }
            else {
                double[] possibleDiv = { interval1.min / interval2.min, interval1.min / interval2.max,
                        interval1.max / interval2.min, interval1.max / interval2.max };
                Arrays.sort(possibleDiv);
                joined = new IntervalAbsVal(possibleDiv[0], possibleDiv[3]);
            }
            break;
        case REM:
            joined = new IntervalAbsVal(0., Math.max(Math.abs(interval2.min), Math.abs(interval2.max)));
            break;
        case AND:
        case OR:
        case XOR:
            joined = new IntervalAbsVal(0., 1.);
            break;
        }

        assert joined != null;
        return in.setLocal(i.getDef(), joined.join(in.getLocal(i.getDef())));
    }

    @Override
    protected VarContext<IntervalAbsVal> flowComparison(SSAComparisonInstruction i,
                                                        Set<VarContext<IntervalAbsVal>> previousItems,
                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                        ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected VarContext<IntervalAbsVal> flowConversion(SSAConversionInstruction i,
                                                        Set<VarContext<IntervalAbsVal>> previousItems,
                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                        ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected VarContext<IntervalAbsVal> flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                                                Set<VarContext<IntervalAbsVal>> previousItems,
                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected VarContext<IntervalAbsVal> flowGetStatic(SSAGetInstruction i,
                                                       Set<VarContext<IntervalAbsVal>> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);

        if (i.getDeclaredFieldType().isPrimitiveType()) {
            AbstractLocation loc = AbstractLocation.createStatic(i.getDeclaredField());
            in = in.setLocal(i.getDef(), in.getLocation(loc));
        }
        return in;
    }

    @Override
    protected VarContext<IntervalAbsVal> flowInstanceOf(SSAInstanceofInstruction i,
                                                        Set<VarContext<IntervalAbsVal>> previousItems,
                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                        ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected VarContext<IntervalAbsVal> flowPhi(SSAPhiInstruction i, Set<VarContext<IntervalAbsVal>> previousItems,
                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                 ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);

        if (!types.getType(i.getDef()).isPrimitiveType()) {
            return in;
        }

        IntervalAbsVal val = null;
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            val = VarContext.safeJoinValues(val, getIntervalOfLocalOrNumber(in, i.getUse(j)));
        }

        if (val == null) {
            return in;
        }
        return in.setLocal(i.getDef(), val);
    }

    @Override
    protected VarContext<IntervalAbsVal> flowPutStatic(SSAPutInstruction i,
                                                       Set<VarContext<IntervalAbsVal>> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);
        if (!i.getDeclaredFieldType().isPrimitiveType()) {
            return in;
        }

        AbstractLocation loc = AbstractLocation.createStatic(i.getDeclaredField());
        IntervalAbsVal inVal = getIntervalOfLocalOrNumber(in, i.getVal());

        // get program point
        InterProgramPoint ipp = ptg.getRegistrar().getInsToPP().get(i).pre();
        InterProgramPointReplica ippr = InterProgramPointReplica.create(currentNode.getContext(), ipp);

        IField f = AnalysisUtil.getClassHierarchy().resolveField(i.getDeclaredField());
        ReferenceVariable fieldRV = ptg.getRegistrar().getRvCache().getStaticField(f);
        PointsToGraphNode fieldRVR = new ReferenceVariableReplica(currentNode.getContext(), fieldRV, ptg.getHaf());
        Iterator<? extends InstanceKey> pti = ptg.pointsToIterator(fieldRVR, ippr);

        InstanceKeyRecency ikr = (InstanceKeyRecency) pti.next();
        if (pti.hasNext() || (!ikr.isRecent() && !ptg.isNullInstanceKey(ikr))) {
            // cannot do strong update
            return in.setLocation(loc, VarContext.safeJoinValues(inVal, in.getLocation(loc)));
        }

        // strong update
        return in.setLocation(loc, inVal);
    }

    @Override
    protected VarContext<IntervalAbsVal> flowUnaryNegation(SSAUnaryOpInstruction i,
                                                           Set<VarContext<IntervalAbsVal>> previousItems,
                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                           ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);
        return in.setLocal(i.getDef(), in.getLocal(i.getUse(0)).neg());
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowArrayLength(SSAArrayLengthInstruction i,
                                                                              Set<VarContext<IntervalAbsVal>> previousItems,
                                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                              ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);
        VarContext<IntervalAbsVal> norm = in.setLocal(i.getDef(), IntervalAbsVal.POSITIVE);
        return factsToMapWithExceptions(norm, in, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowArrayLoad(SSAArrayLoadInstruction i,
                                                                            Set<VarContext<IntervalAbsVal>> previousItems,
                                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                            ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);
        VarContext<IntervalAbsVal> norm = in;
        if (i.getElementType().isPrimitiveType()) {
            norm = norm.setLocal(i.getDef(), IntervalAbsVal.TOP_ELEMENT);
        }
        return factsToMapWithExceptions(norm, in, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowArrayStore(SSAArrayStoreInstruction i,
                                                                             Set<VarContext<IntervalAbsVal>> previousItems,
                                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                             ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                                                                        Set<VarContext<IntervalAbsVal>> previousItems,
                                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                        ISSABasicBlock current) {
        assert i.isPEI();

        VarContext<IntervalAbsVal> in = confluence(previousItems, current);
        VarContext<IntervalAbsVal> vc = flowBinaryOp(i, previousItems, cfg, current);
        VarContext<IntervalAbsVal> norm = in.setLocal(i.getDef(), vc.getLocal(i.getDef()));
        VarContext<IntervalAbsVal> ae = in;
        if (!isConstant(i.getUse(1))) {
            ae = in.setLocal(i.getUse(1), IntervalAbsVal.ZERO);
        }
        return factsToMapWithExceptions(norm, ae, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowCheckCast(SSACheckCastInstruction i,
                                                                            Set<VarContext<IntervalAbsVal>> previousItems,
                                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                            ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                                                                    Set<VarContext<IntervalAbsVal>> previousItems,
                                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                    ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);

        // By default both branches are the same as the input
        Map<ISSABasicBlock, VarContext<IntervalAbsVal>> out = factToModifiableMap(in, current, cfg);

        Double fst = getDoubleFromVar(i.getUse(0));
        Double snd = getDoubleFromVar(i.getUse(1));

        IConditionalBranchInstruction.Operator op = (Operator) i.getOperator();
        VarContext<IntervalAbsVal> trueContext = in;
        VarContext<IntervalAbsVal> falseContext = in;

        switch (op) {
        case EQ:
            if (fst != null && snd == null) {
                // first argument is constant
                trueContext = in.setLocal(i.getUse(1), new IntervalAbsVal(fst, fst));
            }
            else if (snd != null && fst == null) {
                // second argument is constant
                trueContext = in.setLocal(i.getUse(0), new IntervalAbsVal(snd, snd));
            }
            break;
        case GE:
        case GT:
            //
            if (fst != null && snd == null) {
                // first argument is constant
                trueContext = in.setLocal(i.getUse(1), in.getLocal(i.getUse(1)).le(fst));
                falseContext = in.setLocal(i.getUse(1), in.getLocal(i.getUse(1)).ge(fst));
            }
            else if (snd != null && fst == null) {
                // second argument is constant
                trueContext = in.setLocal(i.getUse(0), in.getLocal(i.getUse(0)).ge(snd));
                falseContext = in.setLocal(i.getUse(0), in.getLocal(i.getUse(0)).le(snd));
            }
            break;
        case LE:
        case LT:
            if (fst != null && snd == null) {
                // first argument is constant
                trueContext = in.setLocal(i.getUse(1), in.getLocal(i.getUse(1)).ge(fst));
                falseContext = in.setLocal(i.getUse(1), in.getLocal(i.getUse(1)).le(fst));
            }
            else if (snd != null && fst == null) {
                // second argument is constant
                trueContext = in.setLocal(i.getUse(0), in.getLocal(i.getUse(0)).le(snd));
                falseContext = in.setLocal(i.getUse(0), in.getLocal(i.getUse(0)).ge(snd));
            }
            break;
        case NE:
        default:
            break;

        }

        ISSABasicBlock trueSucc = getTrueSuccessor(current, cfg);
        ISSABasicBlock falseSucc = getFalseSuccessor(current, cfg);
        if (trueSucc != null) {
            out.put(trueSucc, trueContext);
        }
        if (falseSucc != null) {
            out.put(falseSucc, falseContext);
        }
        return out;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowGetField(SSAGetInstruction i,
                                                                           Set<VarContext<IntervalAbsVal>> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);
        VarContext<IntervalAbsVal> normal = in;
        VarContext<IntervalAbsVal> npe = in;

        if (i.getDeclaredFieldType().isPrimitiveType()) {
            IntervalAbsVal newValue = null;
            // Get the program point for this instruction
            InterProgramPoint ipp = ptg.getRegistrar().getInsToPP().get(i).pre();
            InterProgramPointReplica ippr = InterProgramPointReplica.create(currentNode.getContext(), ipp);

            for (AbstractLocation loc : interProc.getLocationsForNonStaticField(i.getRef(),
                                                                                i.getDeclaredField(),
                                                                                currentNode,
                                                                                ippr)) {
                assert loc.getField().getFieldTypeReference().isPrimitiveType();
                newValue = VarContext.safeJoinValues(newValue, in.getLocation(loc));
            }
            if (newValue == null) {
                assert false : "No locations found for field access " + i + " in "
                        + PrettyPrinter.cgNodeString(currentNode);
            }
            normal = normal.setLocal(i.getDef(), newValue);
        }

        return factsToMapWithExceptions(normal, npe, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowInvokeInterface(SSAInvokeInstruction i,
                                                                                  Set<VarContext<IntervalAbsVal>> previousItems,
                                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                  ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowInvokeSpecial(SSAInvokeInstruction i,
                                                                                Set<VarContext<IntervalAbsVal>> previousItems,
                                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowInvokeStatic(SSAInvokeInstruction i,
                                                                               Set<VarContext<IntervalAbsVal>> previousItems,
                                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                               ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowInvokeVirtual(SSAInvokeInstruction i,
                                                                                Set<VarContext<IntervalAbsVal>> previousItems,
                                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowGoto(SSAGotoInstruction i,
                                                                       Set<VarContext<IntervalAbsVal>> previousItems,
                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                       ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowLoadMetadata(SSALoadMetadataInstruction i,
                                                                               Set<VarContext<IntervalAbsVal>> previousItems,
                                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                               ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowMonitor(SSAMonitorInstruction i,
                                                                          Set<VarContext<IntervalAbsVal>> previousItems,
                                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                          ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowNewArray(SSANewInstruction i,
                                                                           Set<VarContext<IntervalAbsVal>> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowNewObject(SSANewInstruction i,
                                                                            Set<VarContext<IntervalAbsVal>> previousItems,
                                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                            ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);
        VarContext<IntervalAbsVal> normalOut = in;

        // Get the program point for this instruction
        InterProgramPoint ipp = ptg.getRegistrar().getInsToPP().get(i).post();
        InterProgramPointReplica ippr = InterProgramPointReplica.create(currentNode.getContext(), ipp);

        // Initiate all fields to zero
        TypeReference t = i.getConcreteType();
        Collection<IField> resolved = AnalysisUtil.getClassHierarchy().lookupClass(t).getAllInstanceFields();
        for (IField f : resolved) {
            if (f.getFieldTypeReference().isPrimitiveType()) {
                Set<AbstractLocation> locs = interProc.getLocationsForNonStaticField(i.getDef(),
                                                                                     f.getReference(),
                                                                                     currentNode,
                                                                                     ippr);
                for (AbstractLocation loc : locs) {
                    normalOut = normalOut.setLocation(loc, IntervalAbsVal.ZERO);
                }
            }
        }

        // The exception edge is actually for errors so no value needs to be
        // passed on that edge (it will always be impossible)
        return factsToMapWithExceptions(normalOut, null, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowPutField(SSAPutInstruction i,
                                                                           Set<VarContext<IntervalAbsVal>> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);
        VarContext<IntervalAbsVal> normal = in;

        if (!i.getDeclaredFieldType().isPrimitiveType()) {
            return factsToMapWithExceptions(normal, in, current, cfg);
        }

        // Get the program point for this instruction
        InterProgramPoint ipp = ptg.getRegistrar().getInsToPP().get(i).pre();
        InterProgramPointReplica ippr = InterProgramPointReplica.create(currentNode.getContext(), ipp);

        // Check whether the field can be strongly updated
        Set<AbstractLocation> locs = interProc.getLocationsForNonStaticField(i.getRef(),
                                                                             i.getDeclaredField(),
                                                                             currentNode,
                                                                             ippr);
        ReferenceVariable recRV = ptg.getRegistrar().getRvCache().getReferenceVariable(i.getRef(), cfg.getMethod());
        PointsToGraphNode recRVR = new ReferenceVariableReplica(currentNode.getContext(), recRV, ptg.getHaf());
        Iterator<? extends InstanceKey> receivers = ptg.pointsToIterator(recRVR, ippr);

        if (receivers.hasNext()) {
            receivers.next();
        }

        boolean strongUpdate = !receivers.hasNext();

        IntervalAbsVal inVal = getIntervalOfLocalOrNumber(in, i.getVal());

        for (AbstractLocation loc : locs) {
            if (strongUpdate) {
                // strong update
                normal = normal.setLocation(loc, inVal);
            }
            else {
                // cannot strong update
                IntervalAbsVal inLoc = in.getLocation(loc);
                normal = normal.setLocation(loc, VarContext.safeJoinValues(inLoc, inVal));
            }
        }

        return factsToMapWithExceptions(normal, in, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowReturn(SSAReturnInstruction i,
                                                                         Set<VarContext<IntervalAbsVal>> previousItems,
                                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                         ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);

        if (i.getNumberOfUses() > 0 && i.returnsPrimitiveType()) {
            in = in.setReturnResult(getIntervalOfLocalOrNumber(in, i.getResult()));
        }

        return factToMap(in, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowSwitch(SSASwitchInstruction i,
                                                                         Set<VarContext<IntervalAbsVal>> previousItems,
                                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                         ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowThrow(SSAThrowInstruction i,
                                                                        Set<VarContext<IntervalAbsVal>> previousItems,
                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                        ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    /**
     * Return if is a constant.
     */
    private boolean isConstant(int var) {
        IR ir = currentNode.getIR();
        Value v = ir.getSymbolTable().getValue(var);
        if (v instanceof ConstantValue) {
            return true;
        }
        return false;
    }

    /**
     * Return the double representation if the value is a number, else return null.
     *
     * @param var integer representation of local variable
     * @return
     */
    private Double getDoubleFromVar(int var) {
        IR ir = currentNode.getIR();
        Value v = ir.getSymbolTable().getValue(var);
        if (v instanceof ConstantValue) {
            ConstantValue cv = (ConstantValue) v;
            if (cv.getValue() instanceof Number) {
                Number n = (Number) cv.getValue();
                return n.doubleValue();
            }
        }
        return null;
    }

    /**
     * Given an integer representation of a local variable, return the known interval. Given an integer representation
     * of a number, return an interval with min and max set to the numeric value.
     *
     * @param in context to query local variables
     * @param var integer representation of local variable
     * @return
     */
    private IntervalAbsVal getIntervalOfLocalOrNumber(VarContext<IntervalAbsVal> in, int var) {
        Double d = getDoubleFromVar(var);
        if (d == null) {
            // not numeric value
            IntervalAbsVal itvl = in.getLocal(var);
            if (itvl == null && types.getType(var).isPrimitiveType()) {
                return IntervalAbsVal.BOTTOM_ELEMENT;
            }
            return itvl;
        }
        return new IntervalAbsVal(d, d);
    }

    /**
     * Join the locations in the context with those in the accumulator
     *
     * @param accumulated map from abstract location to accumulated abstract value
     * @param newContext new context to join into the accumulator
     */
    private static void joinLocations(Map<AbstractLocation, IntervalAbsVal> accumulated,
                                      VarContext<IntervalAbsVal> newContext) {
        for (AbstractLocation loc : newContext.getLocations()) {
            accumulated.put(loc, VarContext.safeJoinValues(newContext.getLocation(loc), accumulated.get(loc)));
        }
    }

    /**
     * The values for the local variables (in the caller) passed into the procedure can be changed by the callee. This
     * method copies the new value into the variable context.
     *
     * @param newActualValues abstract values for the local variables passed in after analyzing a procedure call
     * @param actuals value numbers for actual arguments
     * @param to context to copy into
     * @return new context with values copied in
     */
    private VarContext<IntervalAbsVal> updateActuals(List<IntervalAbsVal> newActualValues,
                                                            List<Integer> actuals, VarContext<IntervalAbsVal> to) {

        VarContext<IntervalAbsVal> out = to;
        for (int j = 1; j < actuals.size(); j++) {
            if (!isConstant(actuals.get(j)) && newActualValues.get(j) != null) {
                out = out.setLocal(actuals.get(j), newActualValues.get(j));
            }
        }
        return out;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowInstruction(SSAInstruction i,
                                                                              Set<VarContext<IntervalAbsVal>> inItems,
                                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                              ISSABasicBlock current) {
        // TODO Set up constant uses
        return super.flowInstruction(i, inItems, cfg, current);
    }

    @Override
    protected void postBasicBlock(ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock justProcessed,
                                  Map<ISSABasicBlock, VarContext<IntervalAbsVal>> outItems) {
        IntervalResults results = ((IntervalInterProceduralDataFlow) interProc).getAnalysisResults();
        for (SSAInstruction i : justProcessed) {
            assert getAnalysisRecord(i) != null : "No analysis record for " + i + " in "
                    + PrettyPrinter.cgNodeString(currentNode);
            VarContext<IntervalAbsVal> input = confluence(getAnalysisRecord(i).getInput(), justProcessed);
            Map<Integer, IntervalAbsVal> intervalMapLocals = new HashMap<>();
            Map<AbstractLocation, IntervalAbsVal> intervalMapLocations = new HashMap<>();
            for (Integer j : input.getLocals()) {
                intervalMapLocals.put(j, input.getLocal(j));
            }
            for (AbstractLocation loc : input.getLocations()) {
                intervalMapLocations.put(loc, input.getLocation(loc));
            }
            results.replaceIntervalMapForLocals(intervalMapLocals, i, currentNode);
            results.replaceIntervalMapForLocations(intervalMapLocations, i, currentNode);
        }
        super.postBasicBlock(cfg, justProcessed, outItems);
    }
}
