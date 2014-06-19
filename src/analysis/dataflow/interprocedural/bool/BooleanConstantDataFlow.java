package analysis.dataflow.interprocedural.bool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.util.VarContext;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction.IOperator;
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
import com.ibm.wala.types.TypeReference;

/**
 * Analysis that uses the results of the pointer analysis to determine which booleans are constants. Used to find dead
 * code branches.
 * <p>
 * This is a special case of a constant determination analysis. WALA does constant propagation so most constants are
 * literals and this kind of analysis is trivial. This analysis also tells us when the results of an instanceof check
 * are known statically (using the points-to results), and propagates those results as well as any boolean constant
 * literals (although those are turned into integer 0 and 1 by WALA). Unless this becomes an inter-procedural analysis
 * there is not much benefit to tracking other constants (although the results of this analysis may mean that some
 * non-constants become constants).
 */
public class BooleanConstantDataFlow extends InstructionDispatchDataFlow<VarContext<BooleanAbsVal>> {

    private final TypeRepository types;
    private final CGNode currentNode;
    private final PointsToGraph ptg;
    private final ReferenceVariableCache rvCache;
    private final BooleanConstantResults results;
    private final SymbolTable st;

    public BooleanConstantDataFlow(CGNode currentNode, PointsToGraph ptg, ReferenceVariableCache rvCache) {
        super(true);
        this.currentNode = currentNode;
        IR ir = currentNode.getIR();
        types = new TypeRepository(ir);
        st = ir.getSymbolTable();
        this.ptg = ptg;
        this.rvCache = rvCache;
        this.results = new BooleanConstantResults(currentNode);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flow(Set<VarContext<BooleanAbsVal>> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        if (current.isEntryBlock()) {
            inItems = Collections.<VarContext<BooleanAbsVal>> singleton(new BooleanConstantVarContext());
        }
        return super.flow(inItems, cfg, current);
    }

    private static VarContext<BooleanAbsVal> confluence(Set<VarContext<BooleanAbsVal>> facts) {
        // TODO can do a bit better when merging if we track impossible branches
        // Not sure if this is simple since the results of this analysis are unsound until it completes
        return VarContext.join(facts);
    }

    /**
     * Run the analysis on the code in the call graph node passed into the constructor
     * 
     * @return results of the analysis
     */
    public BooleanConstantResults run() {
        this.dataflow(currentNode.getIR());
        return results;
    }

    @Override
    protected VarContext<BooleanAbsVal> flowBinaryOp(SSABinaryOpInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<BooleanAbsVal> in = confluence(previousItems);

        if (types.getType(i.getDef()).equals(TypeReference.Boolean)) {
            IOperator op = i.getOperator();
            switch (op.toString()) {
            case "and":
                BooleanAbsVal left = getLocal(i.getUse(0), in);
                BooleanAbsVal right = getLocal(i.getUse(1), in);
                return in.setLocal(i.getDef(), BooleanAbsVal.and(left, right));
            case "or":
                left = getLocal(i.getUse(0), in);
                right = getLocal(i.getUse(1), in);
                return in.setLocal(i.getDef(), BooleanAbsVal.or(left, right));
            case "xor":
                left = getLocal(i.getUse(0), in);
                right = getLocal(i.getUse(1), in);
                return in.setLocal(i.getDef(), BooleanAbsVal.xor(left, right));
            default:
                // Non-boolean binary operation
                break;
            }
        }

        return in;
    }

    @Override
    protected VarContext<BooleanAbsVal> flowComparison(SSAComparisonInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        int left = i.getUse(0);
        int right = i.getUse(1);

        BooleanAbsVal result = null;
        if (left == right) {
            // Same value on both sides of the comparison
            result = BooleanAbsVal.TRUE;
        } else if (st.isConstant(left) && st.isConstant(right)) {
            // Both sides of the comparison are constant
            Object leftC = st.getConstantValue(left);
            Object rightC = st.getConstantValue(right);
            if (leftC.equals(rightC)) {
                result = BooleanAbsVal.TRUE;
            } else {
                result = BooleanAbsVal.FALSE;
            }
        } else {
            result = BooleanAbsVal.UNKNOWN;
        }

        return confluence(previousItems).setLocal(i.getDef(), result);
    }

    @Override
    protected VarContext<BooleanAbsVal> flowConversion(SSAConversionInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems);
    }

    @Override
    protected VarContext<BooleanAbsVal> flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems);
    }

    @Override
    protected VarContext<BooleanAbsVal> flowGetStatic(SSAGetInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Don't track fields
        return confluence(previousItems);
    }

    @Override
    protected VarContext<BooleanAbsVal> flowInstanceOf(SSAInstanceofInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        IClass checkedClass = AnalysisUtil.getClassHierarchy().lookupClass(i.getCheckedType());

        VarContext<BooleanAbsVal> in = confluence(previousItems);
        if (st.isNullConstant(i.getRef())) {
            // null is an instance of anything
            return in.setLocal(i.getDef(), BooleanAbsVal.TRUE);
        }

        boolean castAlwaysSucceeds = true;
        boolean castAlwaysFails = true;
        for (InstanceKey hContext : ptg.getPointsToSet(getReplica(i.getRef(), currentNode))) {
            if (!(castAlwaysSucceeds || castAlwaysFails)) {
                // The cast sometimes succeeds and sometimes doesn't
                break;
            }

            IClass actual = hContext.getConcreteType();
            if (!AnalysisUtil.getClassHierarchy().isAssignableFrom(checkedClass, actual)) {
                // At least one cast will fail
                castAlwaysSucceeds = false;
            }
            if (AnalysisUtil.getClassHierarchy().isAssignableFrom(checkedClass, actual)) {
                // At least one cast will succeed
                castAlwaysFails = false;
            }
        }

        if (castAlwaysSucceeds && !castAlwaysFails) {
            return in.setLocal(i.getDef(), BooleanAbsVal.TRUE);
        }

        if (!castAlwaysSucceeds && castAlwaysFails) {
            return in.setLocal(i.getDef(), BooleanAbsVal.FALSE);
        }

        return in.setLocal(i.getDef(), BooleanAbsVal.UNKNOWN);
    }

    @Override
    protected VarContext<BooleanAbsVal> flowPhi(SSAPhiInstruction i, Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        BooleanAbsVal result = null;
        VarContext<BooleanAbsVal> in = confluence(previousItems);
        if (types.getType(i.getDef()).equals(TypeReference.Boolean)) {
            for (int j = 0; j < i.getNumberOfUses(); j++) {
                if (result == null) {
                    result = getLocal(i.getUse(j), in);
                } else {
                    result = result.join(getLocal(i.getUse(j), in));
                }
            }
            return in.setLocal(i.getDef(), result);
        }
        return in;
    }

    @Override
    protected VarContext<BooleanAbsVal> flowPutStatic(SSAPutInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Don't track fields
        return confluence(previousItems);
    }

    @Override
    protected VarContext<BooleanAbsVal> flowUnaryNegation(SSAUnaryOpInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return confluence(previousItems);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowArrayLength(SSAArrayLengthInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowArrayLoad(SSAArrayLoadInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowArrayStore(SSAArrayStoreInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowCheckCast(SSACheckCastInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        if (i.isIntegerComparison()) {
            // This may be the comparison between two booleans, lets see if one of them is constant
            // Note that constant booleans are turned into integers and that 0 is false
            // They are also normalized so that the constant is always on the right and always 0 (false)
            int left = i.getUse(0);
            if (types.getType(left).equals(TypeReference.Boolean)) {
                int right = i.getUse(1);
                if (st.isZeroOrFalse(right)) {
                    // This is a boolean compared with a constant (it must be zero which corresponds to false)
                    VarContext<BooleanAbsVal> in = confluence(previousItems);
                    in = in.setLocal(right, BooleanAbsVal.FALSE);
                    Map<ISSABasicBlock, VarContext<BooleanAbsVal>> out = new LinkedHashMap<>();
                    ISSABasicBlock trueBB = getTrueSuccessor(current, cfg);
                    ISSABasicBlock falseBB = getFalseSuccessor(current, cfg);
                    switch (i.getOperator().toString()) {
                    case "eq":
                        // if (b == false)
                        VarContext<BooleanAbsVal> trueBranch = in.setLocal(left, BooleanAbsVal.FALSE);
                        VarContext<BooleanAbsVal> falseBranch = in.setLocal(left, BooleanAbsVal.TRUE);
                        out.put(trueBB, trueBranch);
                        out.put(falseBB, falseBranch);
                        return out;
                    case "ne":
                        // if (b != false)
                        trueBranch = in.setLocal(left, BooleanAbsVal.TRUE);
                        falseBranch = in.setLocal(left, BooleanAbsVal.FALSE);
                        out.put(trueBB, trueBranch);
                        out.put(falseBB, falseBranch);
                        return out;
                    default:
                        assert false : "Boolean compared with constant using operator " + i.getOperator();
                        throw new RuntimeException("Boolean compared with constant using operator " + i.getOperator());
                    }

                }
            }
        }
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowGetField(SSAGetInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    /**
     * All invocations are treated the same
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input facts
     * @param cfg
     *            control flow graph
     * @param current
     *            curren basic block
     * @return facts on each output edge
     */
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowInvoke(SSAInvokeInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        if (i.getDeclaredTarget().getReturnType().equals(TypeReference.Boolean)) {
            // Assume the method could return true or false, this is imprecise. It is possible that we have enough
            // information to determine the return result statically. We could turn this into an inter-procedural
            // analysis and be more precise.
            VarContext<BooleanAbsVal> in = confluence(previousItems);
            return factsToMapWithExceptions(in.setLocal(i.getDef(), BooleanAbsVal.UNKNOWN), in, current, cfg);
        }
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowInvokeInterface(SSAInvokeInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowInvokeSpecial(SSAInvokeInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowInvokeStatic(SSAInvokeInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowInvokeVirtual(SSAInvokeInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowGoto(SSAGotoInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowLoadMetadata(SSALoadMetadataInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowMonitor(SSAMonitorInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowNewArray(SSANewInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowNewObject(SSANewInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowPutField(SSAPutInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Not tracking fields
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowReturn(SSAReturnInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowSwitch(SSASwitchInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowThrow(SSAThrowInstruction i,
                                    Set<VarContext<BooleanAbsVal>> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, VarContext<BooleanAbsVal>> flowEmptyBlock(Set<VarContext<BooleanAbsVal>> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(inItems, current, cfg);
    }

    @Override
    protected void postBasicBlock(ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock justProcessed,
                                    Map<ISSABasicBlock, VarContext<BooleanAbsVal>> outItems) {
        // Record the results
        for (SSAInstruction i : justProcessed) {
            assert getAnalysisRecord(i) != null : "No analysis record for " + i + " in "
                                            + PrettyPrinter.cgNodeString(currentNode);
            VarContext<BooleanAbsVal> input = confluence(getAnalysisRecord(i).getInput());
            for (Integer val : input.getLocals()) {
                if (getLocal(val, input) == BooleanAbsVal.TRUE) {
                    results.recordConstant(i, val, true);
                }
                if (getLocal(val, input) == BooleanAbsVal.FALSE) {
                    results.recordConstant(i, val, false);
                }
            }
        }
        super.postBasicBlock(cfg, justProcessed, outItems);
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        // Assume everything is reachable
        return false;
    }

    /**
     * Get the reference variable replica for the given local variable in the current context
     * 
     * @param local
     *            value number of the local variable
     * @param n
     *            call graph node giving the method and context for the local variable
     * @return Reference variable replica in the current context for the local
     */
    public ReferenceVariableReplica getReplica(int local, CGNode n) {
        ReferenceVariable rv = rvCache.getReferenceVariable(local, n.getMethod());
        return new ReferenceVariableReplica(n.getContext(), rv);
    }

    /**
     * Helper to combine input and put the results on each output edge.
     * 
     * @param inputFacts
     *            input to merge
     * @param current
     *            current basic block
     * @param cfg
     *            control flow graph
     * @return map with the merged {@link VarContext} mapped to each successor basic block
     */
    private Map<ISSABasicBlock, VarContext<BooleanAbsVal>> mergeAndCreateMap(Set<VarContext<BooleanAbsVal>> inputFacts,
                                    ISSABasicBlock current, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return factToMap(confluence(inputFacts), current, cfg);
    }

    @Override
    protected void post(IR ir) {
        // Intentionally left blank
    }

    /**
     * Get the abstract value for the given local variable
     * 
     * @param i
     *            local variable value number
     * @param c
     *            context to look up values in
     * @return abstract value for the local variable
     */
    private BooleanAbsVal getLocal(int i, VarContext<BooleanAbsVal> c) {
        BooleanAbsVal val = c.getLocal(i);
        if (val != null) {
            return val;
        }
        if (st.isOneOrTrue(i)) {
            // Literal true
            return BooleanAbsVal.TRUE;
        }
        if (st.isZeroOrFalse(i)) {
            // Literal false
            return BooleanAbsVal.FALSE;
        }
        // Variable is not assigned yet be conservative
        return BooleanAbsVal.UNKNOWN;
    }
}
