package analysis.dataflow.flowsensitizer;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import types.TypeRepository;
import util.OrderedPair;
import analysis.AnalysisUtil;
import analysis.StringAndReflectiveUtil;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.types.MethodReference;

public class StringBuilderFlowSensitizer extends MostlyBoringDataFlow<SensitizerFact> {

    private final Map<SSAInstruction, Integer> subscriptForInstruction;
    private int prevSubscript = 0;
    private final IMethod method;
    private final TypeRepository typeRepository;

    /*
     * Factory Methods
     */

    public static Solution analyze(IMethod m, TypeRepository typeRepository) {
        return new StringBuilderFlowSensitizer(m, typeRepository).runDataFlowAnalysisAndReturnDefUseMaps();
    }

    /*
     * Constructors
     */
    private StringBuilderFlowSensitizer(IMethod m, TypeRepository typeRepository) {
        super(true);
        this.method = m;
        this.subscriptForInstruction = new HashMap<>();
        this.typeRepository = typeRepository;
    }

    /*
     * Helper Methods
     */

    private SensitizerFact definedAt(SensitizerFact fact, Integer varNum, SSAInstruction i) {
        if (fact.isEscaped(varNum)) {
            return fact;
        }
        else {
            Set<Integer> s = new HashSet<>();
            s.add(getSubscriptForInstruction(i));
            SensitizerFact fact2 = fact.replaceSetAt(varNum, s);
            return fact2;
        }
    }

    private SensitizerFact manyDefinedAt(SensitizerFact fact, Set<Integer> varNums, SSAInstruction i) {
        Set<OrderedPair<Integer, Set<Integer>>> updates = new HashSet<>();

        for (Integer varNum : varNums) {
            if (!fact.isEscaped(varNum)) {
                Set<Integer> s = new HashSet<>();
                s.add(getSubscriptForInstruction(i));
                OrderedPair<Integer, Set<Integer>> update = new OrderedPair<>(varNum, s);
                updates.add(update);
            }
        }

        return fact.replaceSetsAt(updates);
    }

    SensitizerFact escapesIfStringBuilder(SensitizerFact fact, int varNum) {
        if (StringAndReflectiveUtil.isStringBuilderType(this.typeRepository.getType(varNum))) {
            return fact.addEscapee(varNum);
        }
        else {
            return fact;
        }
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

    @Override
    protected SensitizerFact joinTs(Collection<SensitizerFact> c) {
        return LatticeJoinMethods.joinCollection(SensitizerFact.makeBottom(), c);
    }

    private static boolean isNonStaticStringBuilderAppendMethod(MethodReference m) {
        return StringAndReflectiveUtil.isStringMutatingMethod(m);
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

    private Solution runDataFlowAnalysisAndReturnDefUseMaps() {
        IR ir = AnalysisUtil.getIR(method);
        this.dataflow(ir);

        final Map<Integer, Map<Set<Integer>, Integer>> confluences = new HashMap<>();
        int nextConfluenceSensitizer = -1;

        final Map<SSAInstruction, Map<Integer, Integer>> useMap = new HashMap<>();
        final Map<SSAInstruction, Map<Integer, Integer>> defMap = new HashMap<>();
        Iterator<SSAInstruction> it = ir.iterateAllInstructions();

        while (it.hasNext()) {
            SSAInstruction i = it.next();

            Map<Integer, Integer> useSensitizer = unitizeSets(joinTs(this.getAnalysisRecord(i).getInput()),
                                                              confluences,
                                                              nextConfluenceSensitizer);
            useMap.put(i, useSensitizer);

            Map<ISSABasicBlock, SensitizerFact> outputOrNull = this.getAnalysisRecord(i).getOutput();
            Map<Integer, Integer> defSensitizer = outputOrNull == null ? Collections.<Integer, Integer> emptyMap()
                    : unitizeSets(joinTs(outputOrNull.values()), confluences, nextConfluenceSensitizer);

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

    Map<ISSABasicBlock, SensitizerFact> flowGenericInvoke(SSAInvokeInstruction i, Iterator<ISSABasicBlock> succNodes,
                                                          SensitizerFact fact) {
        //        System.err.println("flowGenericInvoke(" + i.getCallSite().getDeclaredTarget().getName() + ", " + succNodes
        //                + ", " + fact + ")");
        if (isNonStaticStringBuilderAppendMethod(i.getCallSite().getDeclaredTarget())) {
            // o0.append(o1)
            int arg = i.getUse(0);
            return sameForAllSuccessors(succNodes, definedAt(fact, arg, i));
        }
        else {
            SensitizerFact newfact = fact;
            if (i.getNumberOfReturnValues() != 0
                    && StringAndReflectiveUtil.isStringLikeType(this.typeRepository.getType(i.getDef()))) {
                newfact = definedAt(newfact, i.getDef(), i);
            }
            for (int j = 0; j < i.getNumberOfParameters(); ++j) {
                if (StringAndReflectiveUtil.isStringBuilderType(this.typeRepository.getType(i.getUse(j)))) {
                    newfact = newfact.addEscapee(i.getUse(j));
                }
            }
            return sameForAllSuccessors(succNodes, newfact);
        }
    }

    /*
     * Overrides
     */

    @Override
    protected SensitizerFact flowGetStatic(SSAGetInstruction i, Set<SensitizerFact> previousItems,
                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        SensitizerFact fact = joinTs(previousItems);
        return escapesIfStringBuilder(fact, i.getDef());
    }

    @Override
    protected SensitizerFact flowPutStatic(SSAPutInstruction i, Set<SensitizerFact> previousItems,
                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        SensitizerFact fact = joinTs(previousItems);
        return escapesIfStringBuilder(fact, i.getUse(i.getNumberOfUses() - 1));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowGetField(SSAGetInstruction i, Set<SensitizerFact> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        SensitizerFact fact = joinTs(previousItems);
        fact = escapesIfStringBuilder(fact, i.getDef());
        return sameForAllSuccessors(cfg.getSuccNodes(current), fact);
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowInvokeInterface(SSAInvokeInstruction i,
                                                                      Set<SensitizerFact> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowInvokeSpecial(SSAInvokeInstruction i,
                                                                    Set<SensitizerFact> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowInvokeStatic(SSAInvokeInstruction i,
                                                                   Set<SensitizerFact> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowInvokeVirtual(SSAInvokeInstruction i,
                                                                    Set<SensitizerFact> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinTs(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowPutField(SSAPutInstruction i, Set<SensitizerFact> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        SensitizerFact fact = joinTs(previousItems);
        fact = escapesIfStringBuilder(fact, i.getUse(i.getNumberOfUses() - 1));
        return sameForAllSuccessors(cfg.getSuccNodes(current), fact);
    }

    public interface Solution {
        Map<SSAInstruction, Map<Integer, Integer>> getUseMap();

        Map<SSAInstruction, Map<Integer, Integer>> getDefMap();

        Map<Integer, Map<Set<Integer>, Integer>> getSensitizerDependencies();
    }
}
