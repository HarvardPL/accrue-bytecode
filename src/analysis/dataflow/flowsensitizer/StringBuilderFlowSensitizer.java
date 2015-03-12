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

public class StringBuilderFlowSensitizer extends InstructionDispatchDataFlow<SensitizerFact> {

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
        this.subscriptForInstruction = new HashMap<>();
    }

    /*
     * Helper Methods
     */

    private SensitizerFact definedAt(SensitizerFact fact, Integer varNum, SSAInstruction i) {
        //        System.err.println("[definedAt] varNum: " + varNum);
        //        System.err.println("[definedAt] before: " + fact);
        Set<Integer> s = new HashSet<>();
        s.add(getSubscriptForInstruction(i));
        SensitizerFact fact2 = fact.replaceSetAt(varNum, s);
        //        System.err.println("[definedAt] after: " + fact2);
        return fact2;
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

    private SensitizerFact joinMaps(Collection<SensitizerFact> c) {
        return SensitizerFact.joinCollection(c);
    }

    private static Map<ISSABasicBlock, SensitizerFact> sameForAllSuccessors(Iterator<ISSABasicBlock> succNodes,
                                                                            SensitizerFact fact) {
        Map<ISSABasicBlock, SensitizerFact> m = new HashMap<>();

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

            Map<Integer, Integer> useSensitizer = unitizeSets(joinMaps(this.getAnalysisRecord(i).getInput()),
                                                              confluences,
                                                              nextConfluenceSensitizer);
            useMap.put(i, useSensitizer);

            Map<ISSABasicBlock, SensitizerFact> outputOrNull = this.getAnalysisRecord(i).getOutput();
            Map<Integer, Integer> defSensitizer = outputOrNull == null ? Collections.<Integer, Integer> emptyMap()
                    : unitizeSets(joinMaps(outputOrNull.values()), confluences, nextConfluenceSensitizer);

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

    private static Map<Integer, Integer> unitizeSets(SensitizerFact f,
                                                     Map<Integer, Map<Set<Integer>, Integer>> confluences,
                                                     int nextConfluenceSensitizer) {
        Map<Integer, Integer> result = new HashMap<>();
        Map<Integer, Set<Integer>> m = f.getMap();

        for (Entry<Integer, Set<Integer>> kv : m.entrySet()) {
            Integer var = kv.getKey();
            Set<Integer> s = kv.getValue();

            if (s.size() == 0) {
                throw new RuntimeException("Sensitizing sets should not be empty: " + m);
            }
            else if (s.size() == 1) {
                result.put(var, s.iterator().next());
            }
            else {
                if (confluences.containsKey(var)) {
                    if (confluences.get(var).containsKey(s)) {
                        result.put(var, confluences.get(var).get(s));
                    }
                    else {
                        Map<Set<Integer>, Integer> confluencesAtVar = confluences.get(var);
                        confluencesAtVar.put(s, nextConfluenceSensitizer);
                        result.put(var, nextConfluenceSensitizer);
                        --nextConfluenceSensitizer;
                    }
                }
                else {
                    Map<Set<Integer>, Integer> confluencesAtVar = new HashMap<>();
                    confluencesAtVar.put(s, nextConfluenceSensitizer);
                    result.put(var, nextConfluenceSensitizer);
                    confluences.put(var, confluencesAtVar);
                    --nextConfluenceSensitizer;
                }
            }
        }

        return result;
    }

    private Map<ISSABasicBlock, SensitizerFact> flowGenericInvoke(SSAInvokeInstruction i,
                                                                  Iterator<ISSABasicBlock> succNodes,
                                                                  SensitizerFact fact) {
        //        System.err.println("flowGenericInvoke(" + i.getCallSite().getDeclaredTarget().getName() + ", " + succNodes
        //                + ", " + fact + ")");
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
    protected SensitizerFact flowBinaryOp(SSABinaryOpInstruction i, Set<SensitizerFact> previousItems,
                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected SensitizerFact flowComparison(SSAComparisonInstruction i, Set<SensitizerFact> previousItems,
                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected SensitizerFact flowConversion(SSAConversionInstruction i, Set<SensitizerFact> previousItems,
                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected SensitizerFact flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                                    Set<SensitizerFact> previousItems,
                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                    ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected SensitizerFact flowGetStatic(SSAGetInstruction i, Set<SensitizerFact> previousItems,
                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected SensitizerFact flowInstanceOf(SSAInstanceofInstruction i, Set<SensitizerFact> previousItems,
                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected SensitizerFact flowPhi(SSAPhiInstruction i, Set<SensitizerFact> previousItems,
                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected SensitizerFact flowPutStatic(SSAPutInstruction i, Set<SensitizerFact> previousItems,
                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected SensitizerFact flowUnaryNegation(SSAUnaryOpInstruction i, Set<SensitizerFact> previousItems,
                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                               ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowArrayLength(SSAArrayLengthInstruction i,
                                                                  Set<SensitizerFact> previousItems,
                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                  ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowArrayLoad(SSAArrayLoadInstruction i,
                                                                Set<SensitizerFact> previousItems,
                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowArrayStore(SSAArrayStoreInstruction i,
                                                                 Set<SensitizerFact> previousItems,
                                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                 ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                                                            Set<SensitizerFact> previousItems,
                                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                            ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowCheckCast(SSACheckCastInstruction i,
                                                                Set<SensitizerFact> previousItems,
                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                                                        Set<SensitizerFact> previousItems,
                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                        ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowGetField(SSAGetInstruction i, Set<SensitizerFact> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowInvokeInterface(SSAInvokeInstruction i,
                                                                      Set<SensitizerFact> previousItems,
                                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                      ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowInvokeSpecial(SSAInvokeInstruction i,
                                                                    Set<SensitizerFact> previousItems,
                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                    ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowInvokeStatic(SSAInvokeInstruction i,
                                                                   Set<SensitizerFact> previousItems,
                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                   ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowInvokeVirtual(SSAInvokeInstruction i,
                                                                    Set<SensitizerFact> previousItems,
                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                    ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowGoto(SSAGotoInstruction i, Set<SensitizerFact> previousItems,
                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                           ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowLoadMetadata(SSALoadMetadataInstruction i,
                                                                   Set<SensitizerFact> previousItems,
                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                   ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowMonitor(SSAMonitorInstruction i,
                                                              Set<SensitizerFact> previousItems,
                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                              ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowNewArray(SSANewInstruction i, Set<SensitizerFact> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowNewObject(SSANewInstruction i, Set<SensitizerFact> previousItems,
                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowPutField(SSAPutInstruction i, Set<SensitizerFact> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowReturn(SSAReturnInstruction i, Set<SensitizerFact> previousItems,
                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                             ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowSwitch(SSASwitchInstruction i, Set<SensitizerFact> previousItems,
                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                             ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowThrow(SSAThrowInstruction i, Set<SensitizerFact> previousItems,
                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                            ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowEmptyBlock(Set<SensitizerFact> inItems,
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
