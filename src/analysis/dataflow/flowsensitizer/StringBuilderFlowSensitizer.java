package analysis.dataflow.flowsensitizer;

import java.util.Collection;
import java.util.HashMap;
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
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;

public class StringBuilderFlowSensitizer extends MostlyBoringDataFlow<SensitizerFact> {
    private final Map<SSAInstruction, Integer> subscriptForInstruction;
    private int prevSubscript = 0;
    private final IMethod method;
    private final TypeRepository typeRepository;

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
     * Super's Interface
     */

    @Override
    protected SensitizerFact joinTs(Collection<SensitizerFact> c) {
        for (SensitizerFact fact : c) {
            assert LatticeJoinMethods.joinCollection(SensitizerFact.makeBottom(), c).upperBounds(fact);
        }
        return LatticeJoinMethods.joinCollection(SensitizerFact.makeBottom(), c);
    }

    //    `x = phi(a,b,c) : ℓ`
    //    PointsTo[x ↦ PointsTo(a) ⊔ PointsTo(b) ⊔ PointsTo(c)]
    @Override
    protected SensitizerFact flowPhi(SSAPhiInstruction i, Set<SensitizerFact> previousItems,
                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return joinTs(previousItems);
    }

    //            `x = a : ℓ`
    //            PointsTo[x ↦ PointsTo(a)]
    // does that actually happen in SSA?

    private Map<ISSABasicBlock, SensitizerFact> flowGenericInvoke(SSAInvokeInstruction i,
                                                                  Iterator<ISSABasicBlock> succNodes,
                                                                  SensitizerFact fact) {
        IMethod im = StringAndReflectiveUtil.methodReferenceToIMethod(i.getCallSite().getDeclaredTarget());
        //            `x = a.append(b) : ℓ`
        //            PointsTo[x ↦ locs, a ↦ locs]
        //            where locs = (fmap (tick ℓ) PointsTo(a))
        if (StringAndReflectiveUtil.isStringBuilderAppend(im)) {
            // o2 = o0.append(o1)
            int o0 = i.getUse(0);
            int o2 = i.getDef();
            SensitizerFact ticked = fact.tick(o0, getSubscriptForInstruction(i));
            SensitizerFact tickedAndAliased = ticked.mustAlias(o2, o0);
            return sameForAllSuccessors(succNodes, tickedAndAliased);
        }
        else if (StringAndReflectiveUtil.isStringBuilderInit0Method(im)) {
            // o.<init>()
            int o = i.getUse(0);
            return sameForAllSuccessors(succNodes, fact.tick(o, getSubscriptForInstruction(i)));
        }
        else if (StringAndReflectiveUtil.isStringBuilderInit1Method(im)) {
            // o.<init>(a)
            int o = i.getUse(0);
            return sameForAllSuccessors(succNodes, fact.tick(o, getSubscriptForInstruction(i)));
        }
        else /* the string builders escape */{
            //            `x = o.foo(a) : ℓ`
            //            PointsTo[x ↦ Escaped, a ↦ Escaped]
            //
            //            `x = Class.foo(a) : ℓ`
            //            PointsTo[x ↦ Escaped, a ↦ Escaped]
            SensitizerFact newfact = fact;
            if (i.getNumberOfReturnValues() != 0
                    && StringAndReflectiveUtil.isStringBuilderType(this.typeRepository.getType(i.getDef()))) {
                newfact = newfact.addEscapee(i.getDef());
            }
            // this loop also escapes the object of a virtual method, but that's OK. Any methods we care about will be caught above and handled appropriately.
            for (int j = 0; j < i.getNumberOfParameters(); ++j) {
                if (StringAndReflectiveUtil.isStringBuilderType(this.typeRepository.getType(i.getUse(j)))) {
                    newfact = newfact.addEscapee(i.getUse(j));
                }
            }
            return sameForAllSuccessors(succNodes, newfact);
        }
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
    protected Map<ISSABasicBlock, SensitizerFact> flowNewObject(SSANewInstruction i, Set<SensitizerFact> previousItems,
                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                ISSABasicBlock current) {
        //            `x = new() : ℓ`
        //            PointsTo[x ↦ (ℓ, ℓ)]
        //
        //            `x = new(y) : ℓ`
        //            PointsTo[x ↦ (ℓ, ℓ)]
        return sameForAllSuccessors(cfg.getSuccNodes(current),
                                    joinTs(previousItems).initTime(i.getDef(), getSubscriptForInstruction(i)));
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowGetField(SSAGetInstruction i, Set<SensitizerFact> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        //            `x = o.f : ℓ`
        //            PointsTo[x ↦ Escaped]
        assert !i.isStatic();
        int escapee = i.getDef();
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems).addEscapee(escapee));
    }

    @Override
    protected SensitizerFact flowGetStatic(SSAGetInstruction i, Set<SensitizerFact> previousItems,
                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        //            `x = Class.f`
        //            PointsTo[x ↦ Escaped]
        assert i.isStatic();
        int escapee = i.getDef();
        return joinTs(previousItems).addEscapee(escapee);
    }

    @Override
    protected Map<ISSABasicBlock, SensitizerFact> flowPutField(SSAPutInstruction i, Set<SensitizerFact> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        //            `o.f = x : ℓ`
        //            PointsTo[x ↦ Escaped]
        assert !i.isStatic();
        int escapee = i.getUse(1);
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinTs(previousItems).addEscapee(escapee));
    }

    @Override
    protected SensitizerFact flowPutStatic(SSAPutInstruction i, Set<SensitizerFact> previousItems,
                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        //            `Class.f = x`
        //            PointsTo[x ↦ Escaped]
        assert i.isStatic();
        int escapee = i.getUse(0);
        return joinTs(previousItems).addEscapee(escapee);
    }

    private Solution runDataFlowAnalysisAndReturnDefUseMaps() {
        IR ir = AnalysisUtil.getIR(method);
        this.dataflow(ir);

        final Map<SSAInstruction, Map<Integer, StringBuilderLocation>> useMap = new HashMap<>();
        final Map<SSAInstruction, Map<Integer, StringBuilderLocation>> defMap = new HashMap<>();
        Iterator<SSAInstruction> it = ir.iterateAllInstructions();

        while (it.hasNext()) {
            SSAInstruction i = it.next();

            SensitizerFact useFact = joinTs(this.getAnalysisRecord(i).getInput());

            Relation<Integer, OrderedPair<Integer, Integer>> useSensitizer = useFact.getRelation();
            Map<Integer, StringBuilderLocation> uses = new HashMap<>();
            for (Entry<Integer, Set<OrderedPair<Integer, Integer>>> kv : useSensitizer.getEntrySet()) {
                Integer var = kv.getKey();
                if (useFact.isEscaped(var)) {
                    uses.put(var, StringBuilderEscaped.make());
                }
                else {
                    uses.put(var, StringBuilderVariable.make(this.method, kv.getValue()));
                }
            }
            useMap.put(i, uses);

            Map<ISSABasicBlock, SensitizerFact> outputOrNull = this.getAnalysisRecord(i).getOutput();
            Relation<Integer, OrderedPair<Integer, Integer>> defSensitizer = outputOrNull == null
                    ? Relation.<Integer, OrderedPair<Integer, Integer>> makeBottom()
                    : joinTs(outputOrNull.values()).getRelation();

            Map<Integer, StringBuilderLocation> defs = new HashMap<>();
            for (Entry<Integer, Set<OrderedPair<Integer, Integer>>> kv : defSensitizer.getEntrySet()) {
                Integer var = kv.getKey();
                if (useFact.isEscaped(var)) {
                    defs.put(var, StringBuilderEscaped.make());
                }
                else {
                    defs.put(var, StringBuilderVariable.make(method, kv.getValue()));
                }
            }
            defMap.put(i, defs);
        }

        return new Solution() {

            @Override
            public Map<SSAInstruction, Map<Integer, StringBuilderLocation>> getUseRelation() {
                return useMap;
            }

            @Override
            public Map<SSAInstruction, Map<Integer, StringBuilderLocation>> getDefRelation() {
                return defMap;
            }
        };
    }

    private SensitizerFact escapesIfStringBuilder(SensitizerFact fact, int varNum) {
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

    public interface Solution {
        Map<SSAInstruction, Map<Integer, StringBuilderLocation>> getUseRelation();

        Map<SSAInstruction, Map<Integer, StringBuilderLocation>> getDefRelation();
    }

}
