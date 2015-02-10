package analysis.dataflow.interprocedural.interval;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.IntraproceduralDataFlow;
import analysis.dataflow.util.AbstractLocation;
import analysis.dataflow.util.VarContext;
import analysis.pointer.statements.ProgramPoint.InterProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction;
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

public class IntervalDataFlow extends IntraproceduralDataFlow<VarContext<IntervalAbsVal>> {

    public IntervalDataFlow(CGNode currentNode, InterproceduralDataFlow<VarContext<IntervalAbsVal>> interProc) {
        super(currentNode, interProc);
        // TODO Auto-generated constructor stub
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> call(SSAInvokeInstruction i,
                                                                   Set<VarContext<IntervalAbsVal>> inItems,
                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                   ISSABasicBlock bb) {
        // TODO Auto-generated method stub
        return null;
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
        IntervalAbsVal interval1 = in.getLocal(i.getUse(0));
        IntervalAbsVal interval2 = in.getLocal(i.getUse(1));
        IBinaryOpInstruction.Operator op = (IBinaryOpInstruction.Operator) i.getOperator();
        IntervalAbsVal joined = IntervalAbsVal.TOP_ELEMENT;
        switch (op) {
        case ADD:
            joined = new IntervalAbsVal(interval1.min + interval2.min, interval1.max + interval2.max);
            break;
        case SUB:
            joined = new IntervalAbsVal(interval1.min - interval2.max, interval1.max - interval2.min);
            break;
        case MUL:
            double[] possible = { interval1.min * interval2.min, interval1.min * interval2.max,
                    interval1.max * interval2.min, interval1.max * interval2.max };
            Arrays.sort(possible);
            joined = new IntervalAbsVal(possible[0], possible[3]);
            break;
        case DIV:
            if (interval1.containsZero()) {
                if (interval2.containsZero()) {
                    joined = new IntervalAbsVal(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                }
                else if (interval2.min > 0) {
                    joined = new IntervalAbsVal(interval1.min / interval2.min, interval1.max / interval2.min);
                }
                else {
                    joined = new IntervalAbsVal(interval1.max / interval2.max, interval1.min / interval2.max);
                }
            }
            else if (interval1.min > 0) {
                if (interval2.containsZero()) {
                    double minBoundary = interval2.min == 0. ? interval1.min / interval2.max : Double.NEGATIVE_INFINITY;
                    double maxBoundary = interval2.max == 0. ? interval1.min / interval2.min : Double.POSITIVE_INFINITY;
                    joined = new IntervalAbsVal(minBoundary, maxBoundary);
                }
                else if (interval2.min > 0) {
                    joined = new IntervalAbsVal(interval1.min / interval2.max, interval1.max / interval2.min);
                }
                else {
                    joined = new IntervalAbsVal(interval1.max / interval2.max, interval1.min / interval2.min);
                }
            }
            else {
                if (interval2.containsZero()) {
                    double minBoundary = interval2.max == 0. ? interval1.max / interval2.min : Double.NEGATIVE_INFINITY;
                    double maxBoundary = interval2.min == 0. ? interval1.max / interval2.max : Double.POSITIVE_INFINITY;
                    joined = new IntervalAbsVal(minBoundary, maxBoundary);
                }
                else if (interval2.min > 0) {
                    joined = new IntervalAbsVal(interval1.min / interval2.min, interval1.max / interval2.max);
                }
                else {
                    joined = new IntervalAbsVal(interval1.max / interval2.min, interval1.min / interval2.max);
                }
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
        default:
            break;
        }

        return in.setLocal(i.getDef(), joined);
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
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<IntervalAbsVal> flowInstanceOf(SSAInstanceofInstruction i,
                                                        Set<VarContext<IntervalAbsVal>> previousItems,
                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                        ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<IntervalAbsVal> flowPhi(SSAPhiInstruction i, Set<VarContext<IntervalAbsVal>> previousItems,
                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                 ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<IntervalAbsVal> flowPutStatic(SSAPutInstruction i,
                                                       Set<VarContext<IntervalAbsVal>> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<IntervalAbsVal> flowUnaryNegation(SSAUnaryOpInstruction i,
                                                           Set<VarContext<IntervalAbsVal>> previousItems,
                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                           ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowArrayLength(SSAArrayLengthInstruction i,
                                                                              Set<VarContext<IntervalAbsVal>> previousItems,
                                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                              ISSABasicBlock current) {
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);
        // XXX the result is definitely positive?
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
        VarContext<IntervalAbsVal> in = confluence(previousItems, current);
        VarContext<IntervalAbsVal> norm = in;
        if (i.getElementType().isPrimitiveType()) {
            norm = norm.setLocal(i.getValue(), IntervalAbsVal.TOP_ELEMENT);
        }
        return factsToMapWithExceptions(norm, in, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                                                                        Set<VarContext<IntervalAbsVal>> previousItems,
                                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                        ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
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
        // TODO Auto-generated method stub
        return null;
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

        if (i.getConcreteType().isPrimitiveType()) {
            assert i.getNumberOfUses() == 1;
            IR ir = currentNode.getIR();
            Value v = ir.getSymbolTable().getValue(i.getUse(0));
            if (v instanceof ConstantValue) {
                ConstantValue cv = (ConstantValue) v;
                if (cv.getValue() instanceof Number) {
                    double num = (double) cv.getValue();
                    normalOut = in.setLocal(i.getDef(), new IntervalAbsVal(num, num));
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
        // TODO
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<IntervalAbsVal>> flowReturn(SSAReturnInstruction i,
                                                                         Set<VarContext<IntervalAbsVal>> previousItems,
                                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                         ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
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

}
