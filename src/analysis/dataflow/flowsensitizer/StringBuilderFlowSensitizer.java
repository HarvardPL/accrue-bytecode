package analysis.dataflow.flowsensitizer;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import analysis.AnalysisUtil;
import analysis.StringAndReflectiveUtil;
import analysis.dataflow.InstructionDispatchDataFlow;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IMethod;
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
import com.ibm.wala.types.MethodReference;

public class StringBuilderFlowSensitizer extends InstructionDispatchDataFlow<Map<Integer, Integer>> {

    private final Map<Integer, Set<Integer>> confluences;
    private final Map<SSAInstruction, Integer> subscriptForInstruction;
    private int prevSubscript = 0;

    /*
     * Factory Methods
     */

    public static StringBuilderFlowSensitizer make(boolean forward) {
        return new StringBuilderFlowSensitizer(forward);
    }

    /*
     * Constructors
     */
    private StringBuilderFlowSensitizer(boolean forward) {
        super(forward);
        this.confluences = new HashMap<>();
        this.subscriptForInstruction = new HashMap<>();
    }

    /*
     * Helper Methods
     */

    private Map<Integer, Integer> definedAt(Map<Integer, Integer> fact, Integer varNum, SSAInstruction i) {
        Map<Integer, Integer> m = new HashMap<>(fact);
        m.put(varNum, getSubscriptForInstruction(i));
        return m;
    }

    private int getSubscriptForInstruction(SSAInstruction i) {
        if (this.subscriptForInstruction.containsKey(i)) {
            return this.subscriptForInstruction.get(i);
        }
        else {
            ++prevSubscript;
            this.subscriptForInstruction.put(i, prevSubscript);
            return prevSubscript;
        }
    }

    private Map<Integer, Integer> joinMaps(Collection<Map<Integer, Integer>> c) {
        Map<Integer, Integer> m = new HashMap<>();
        Map<Integer, Set<Integer>> flattened = flatten(c);

        for (Entry<Integer, Set<Integer>> kv : flattened.entrySet()) {
            Integer k = kv.getKey();
            Set<Integer> v = kv.getValue();

            if (v.size() == 0) {
                throw new RuntimeException("Unexpected empty set, check the implementation of flatten");
            }
            else if (v.size() == 1) {
                m.put(k, v.iterator().next());
            }
            else {
                ++this.prevSubscript;
                this.confluences.put(this.prevSubscript, v);
                m.put(k, this.prevSubscript);
            }
        }

        return m;
    }

    private static Map<Integer, Set<Integer>> flatten(Collection<Map<Integer, Integer>> c) {
        Map<Integer, Set<Integer>> r = new HashMap<>();

        for (Map<Integer, Integer> m : c) {
            for (Entry<Integer, Integer> kv : m.entrySet()) {
                Integer k = kv.getKey();
                Integer v = kv.getValue();
                if (r.containsKey(k)) {
                    r.get(k).add(v);
                }
                else {
                    Set<Integer> s = new HashSet<>();
                    s.add(v);
                    r.put(k, s);
                }
            }
        }

        return r;
    }

    private static Map<ISSABasicBlock, Map<Integer, Integer>> sameForAllSuccessors(Iterator<ISSABasicBlock> succNodes,
                                                                                   Map<Integer, Integer> fact) {
        Map<ISSABasicBlock, Map<Integer, Integer>> m = new HashMap<>();

        while (succNodes.hasNext()) {
            ISSABasicBlock succ = succNodes.next();
            m.put(succ, fact);
        }

        return m;
    }

    private static boolean isNonStaticStringBuilderAppendMethod(MethodReference m) {
        return StringAndReflectiveUtil.isStringMethod(m) || StringAndReflectiveUtil.isStringInitMethod(m);
    }

    /*
     * Implementation of flow-sensitizing StringBuilder method calls follows.
     *
     * For irrelevant, non branching SSA Instructions, we simply:
     *
     *    joinMaps(previousItems)
     *
     * For irrelevant, branching SSA Instructions, we
     *
     *     sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems)
     *
     */

    public Solution runDataFlowAnalysisAndReturnDefUseMaps(IMethod m) {
        IR ir = AnalysisUtil.getIR(m);
        this.dataflow(ir);

        final Map<Integer, Map<Set<Integer>, Integer>> confluences = new HashMap<>();
        int nextConfluenceSensitizer = -1;

        final Map<SSAInstruction, Map<Integer, Integer>> useMap = new HashMap<>();
        final Map<SSAInstruction, Map<Integer, Integer>> defMap = new HashMap<>();
        Iterator<SSAInstruction> it = ir.iterateAllInstructions();

        while (it.hasNext()) {
            SSAInstruction i = it.next();

            Map<Integer, Integer> useSensitizer = joinMaps(this.getAnalysisRecord(i).getInput());
            useMap.put(i, useSensitizer);

            Map<ISSABasicBlock, Map<Integer, Integer>> outputOrNull = this.getAnalysisRecord(i).getOutput();
            Map<Integer, Integer> defSensitizer = outputOrNull == null ? Collections.<Integer, Integer> emptyMap()
                    : joinMaps(outputOrNull.values());

            defMap.put(i, defSensitizer);
        }

        return new Solution() {

            @Override
            public Map<SSAInstruction, Map<Integer, Integer>> getUseMap() {
                return useMap;
            }

            @Override
            public Map<SSAInstruction, Map<Integer, Integer>> getDefMap() {
                return defMap;
            }

            @Override
            public Map<Integer, Map<Set<Integer>, Integer>> getSensitizerDependencies() {
                return confluences;
            }
        };
    }

    /*
     * Actual logic of the data flow (as opposed to rote method overrides)
     *
     * NOTE: don't mutate `fact`!!
     */

    private Map<ISSABasicBlock, Map<Integer, Integer>> flowGenericInvoke(SSAInvokeInstruction i,
                                                                         Iterator<ISSABasicBlock> succNodes,
                                                                         Map<Integer, Integer> fact) {
        System.err.println("flowGenericInvoke(" + i.getCallSite().getDeclaredTarget().getName() + ", " + succNodes
                + ", " + fact + ")");
        if (isNonStaticStringBuilderAppendMethod(i.getCallSite().getDeclaredTarget())) {
            // o0.append(o1)
            int arg = i.getUse(0);
            return sameForAllSuccessors(succNodes, definedAt(fact, arg, i));
        }

        return sameForAllSuccessors(succNodes, fact);
    }

    /*
     * Overrides
     */

    @Override
    protected Map<Integer, Integer> flowBinaryOp(SSABinaryOpInstruction i, Set<Map<Integer, Integer>> previousItems,
                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                 ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected Map<Integer, Integer> flowComparison(SSAComparisonInstruction i,
                                                   Set<Map<Integer, Integer>> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected Map<Integer, Integer> flowConversion(SSAConversionInstruction i,
                                                   Set<Map<Integer, Integer>> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected Map<Integer, Integer> flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                                           Set<Map<Integer, Integer>> previousItems,
                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                           ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected Map<Integer, Integer> flowGetStatic(SSAGetInstruction i, Set<Map<Integer, Integer>> previousItems,
                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                  ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected Map<Integer, Integer> flowInstanceOf(SSAInstanceofInstruction i,
                                                   Set<Map<Integer, Integer>> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected Map<Integer, Integer> flowPhi(SSAPhiInstruction i, Set<Map<Integer, Integer>> previousItems,
                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected Map<Integer, Integer> flowPutStatic(SSAPutInstruction i, Set<Map<Integer, Integer>> previousItems,
                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                  ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected Map<Integer, Integer> flowUnaryNegation(SSAUnaryOpInstruction i,
                                                      Set<Map<Integer, Integer>> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowArrayLength(SSAArrayLengthInstruction i,
                                                                         Set<Map<Integer, Integer>> previousItems,
                                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                         ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowArrayLoad(SSAArrayLoadInstruction i,
                                                                       Set<Map<Integer, Integer>> previousItems,
                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                       ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowArrayStore(SSAArrayStoreInstruction i,
                                                                        Set<Map<Integer, Integer>> previousItems,
                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                        ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                                                                   Set<Map<Integer, Integer>> previousItems,
                                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                   ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowCheckCast(SSACheckCastInstruction i,
                                                                       Set<Map<Integer, Integer>> previousItems,
                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                       ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                                                               Set<Map<Integer, Integer>> previousItems,
                                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                               ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowGetField(SSAGetInstruction i,
                                                                      Set<Map<Integer, Integer>> previousItems,
                                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                      ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowInvokeInterface(SSAInvokeInstruction i,
                                                                             Set<Map<Integer, Integer>> previousItems,
                                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                             ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowInvokeSpecial(SSAInvokeInstruction i,
                                                                           Set<Map<Integer, Integer>> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowInvokeStatic(SSAInvokeInstruction i,
                                                                          Set<Map<Integer, Integer>> previousItems,
                                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                          ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowInvokeVirtual(SSAInvokeInstruction i,
                                                                           Set<Map<Integer, Integer>> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowGoto(SSAGotoInstruction i,
                                                                  Set<Map<Integer, Integer>> previousItems,
                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                  ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowLoadMetadata(SSALoadMetadataInstruction i,
                                                                          Set<Map<Integer, Integer>> previousItems,
                                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                          ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowMonitor(SSAMonitorInstruction i,
                                                                     Set<Map<Integer, Integer>> previousItems,
                                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                     ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowNewArray(SSANewInstruction i,
                                                                      Set<Map<Integer, Integer>> previousItems,
                                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                      ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowNewObject(SSANewInstruction i,
                                                                       Set<Map<Integer, Integer>> previousItems,
                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                       ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowPutField(SSAPutInstruction i,
                                                                      Set<Map<Integer, Integer>> previousItems,
                                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                      ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowReturn(SSAReturnInstruction i,
                                                                    Set<Map<Integer, Integer>> previousItems,
                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                    ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowSwitch(SSASwitchInstruction i,
                                                                    Set<Map<Integer, Integer>> previousItems,
                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                    ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowThrow(SSAThrowInstruction i,
                                                                   Set<Map<Integer, Integer>> previousItems,
                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                   ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, Integer>> flowEmptyBlock(Set<Map<Integer, Integer>> inItems,
                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                        ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(inItems));
    }

    @Override
    protected void post(IR ir) {
        // XXX: Everyone else leaves this blank, so should we? What is it for?
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        // XXX: Everyone else returns false, so should we? How do I implement it? What is it for?
        return false;
    }

    public interface Solution {
        Map<SSAInstruction, Map<Integer, Integer>> getUseMap();

        Map<SSAInstruction, Map<Integer, Integer>> getDefMap();

        Map<Integer, Map<Set<Integer>, Integer>> getSensitizerDependencies();
    }

}
