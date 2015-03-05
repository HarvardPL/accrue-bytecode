package analysis.dataflow.flowsensitizer;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import analysis.AnalysisUtil;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
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
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.strings.Atom;

public class StringBuilderFlowSensitizer extends
        FunctionalInstructionDispatchDataFlow<FlowSensitizedVariableMap<Integer>> {

    private FlowSensitizedVariableMapFactory<Integer> factory;
    private static final TypeReference JavaLangStringBuilderTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Primordial,
                                                                                                       TypeName.string2TypeName("Ljava/lang/StringBuilder"));
    public final static Atom appendAtom = Atom.findOrCreateUnicodeAtom("append");
    public final static Descriptor appendStringBuilderDesc = Descriptor.findOrCreateUTF8(Language.JAVA,
                                                                                         "(Ljava/lang/StringBuilder;)Ljava/lang/StringBuilder;");
    public final static Descriptor appendStringDesc = Descriptor.findOrCreateUTF8(Language.JAVA,
                                                                                  "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
    public final static Descriptor appendObjectDesc = Descriptor.findOrCreateUTF8(Language.JAVA,
                                                                                  "(Ljava/lang/Object;)Ljava/lang/StringBuilder;");
    private static final MethodReference stringBuilderAppendStringBuilderMethod = MethodReference.findOrCreate(JavaLangStringBuilderTypeReference,
                                                                                                               appendAtom,
                                                                                                               appendStringBuilderDesc);
    private static final MethodReference stringBuilderAppendStringMethod = MethodReference.findOrCreate(JavaLangStringBuilderTypeReference,
                                                                                                        appendAtom,
                                                                                                        appendStringDesc);
    private static final MethodReference stringBuilderAppendObjectMethod = MethodReference.findOrCreate(JavaLangStringBuilderTypeReference,
                                                                                                        appendAtom,
                                                                                                        appendObjectDesc);

    /*
     * Factory Methods
     */

    public static StringBuilderFlowSensitizer make(boolean forward) {
        return new StringBuilderFlowSensitizer(forward, FlowSensitizedVariableMapFactory.<Integer> make());
    }

    /*
     * Constructors
     */
    private StringBuilderFlowSensitizer(boolean forward, FlowSensitizedVariableMapFactory<Integer> factory) {
        super(forward);
        this.factory = factory;
    }

    /*
     * Helper Methods
     */

    private FlowSensitizedVariableMap<Integer> joinMaps(Collection<FlowSensitizedVariableMap<Integer>> maps) {
        return this.factory.joinCollection(maps);
    }

    private Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> sameForAllSuccessors(Iterator<ISSABasicBlock> succNodes,
                                                                                         FlowSensitizedVariableMap<Integer> fact) {
        Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> m = Collections.emptyMap();

        while (succNodes.hasNext()) {
            ISSABasicBlock succ = succNodes.next();
            m.put(succ, fact);
        }

        return m;
    }

    private static boolean isNonStaticStringBuilderAppendMethod(MethodReference m) {
        return m.equals(stringBuilderAppendStringBuilderMethod) || m.equals(stringBuilderAppendStringMethod)
                || m.equals(stringBuilderAppendObjectMethod);
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

    /* (non-Javadoc)
     * @see analysis.pointer.reflective.dataflow.FunctionalInstructionDispatchDataFlow#runDataFlowAnalysis(com.ibm.wala.classLoader.IMethod)
     */
    @Override
    public Map<SSAInstruction, AnalysisRecord<FlowSensitizedVariableMap<Integer>>> runDataFlowAnalysis(IMethod m) {
        IR ir = AnalysisUtil.getIR(m);
        this.dataflow(ir);
        Map<SSAInstruction, AnalysisRecord<FlowSensitizedVariableMap<Integer>>> map = new HashMap<>();
        Iterator<SSAInstruction> it = ir.iterateAllInstructions();
        while (it.hasNext()) {
            SSAInstruction i = it.next();
            map.put(i, this.getAnalysisRecord(i));
        }
        return map;
    }

    public OrderedPair<Map<SSAInstruction, Map<Integer, Integer>>, Map<SSAInstruction, Map<Integer, Integer>>> runDataFlowAnalysisAndReturnDefUseMaps(IMethod m) {
        IR ir = AnalysisUtil.getIR(m);
        this.dataflow(ir);
        Map<SSAInstruction, Map<Integer, Integer>> useMap = new HashMap<>();
        Map<SSAInstruction, Map<Integer, Integer>> defMap = new HashMap<>();
        Iterator<SSAInstruction> it = ir.iterateAllInstructions();
        while (it.hasNext()) {
            SSAInstruction i = it.next();
            useMap.put(i, joinMaps(this.getAnalysisRecord(i).getInput()).getInsensitiveToFlowSensistiveMap());
            defMap.put(i, joinMaps(this.getAnalysisRecord(i).getOutput().values()).getInsensitiveToFlowSensistiveMap());
        }
        return new OrderedPair<>(defMap, useMap);
    }

    /*
     * Actual logic of the data flow (as opposed to rote method overrides)
     *
     * NOTE: don't mutate `fact`!!
     */

    private Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowGenericInvoke(SSAInvokeInstruction i,
                                                                                      Iterator<ISSABasicBlock> succNodes,
                                                                                      FlowSensitizedVariableMap<Integer> fact) {
        if (isNonStaticStringBuilderAppendMethod(i.getCallSite().getDeclaredTarget())) {
            // o1.append(o2)
            int arg = i.getUse(1);
            return sameForAllSuccessors(succNodes, fact.freshFlowSensitive(arg));
        }

        return sameForAllSuccessors(succNodes, fact);
    }

    /*
     * Overrides
     */

    @Override
    protected FlowSensitizedVariableMap<Integer> flowBinaryOp(SSABinaryOpInstruction i,
                                                              Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                              ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap<Integer> flowComparison(SSAComparisonInstruction i,
                                                                Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap<Integer> flowConversion(SSAConversionInstruction i,
                                                                Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap<Integer> flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                                                        Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                        ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap<Integer> flowGetStatic(SSAGetInstruction i,
                                                               Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap<Integer> flowInstanceOf(SSAInstanceofInstruction i,
                                                                Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap<Integer> flowPhi(SSAPhiInstruction i,
                                                         Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap<Integer> flowPutStatic(SSAPutInstruction i,
                                                               Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap<Integer> flowUnaryNegation(SSAUnaryOpInstruction i,
                                                                   Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                   ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowArrayLength(SSAArrayLengthInstruction i,
                                                                                      Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                      ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowArrayLoad(SSAArrayLoadInstruction i,
                                                                                    Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                    ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowArrayStore(SSAArrayStoreInstruction i,
                                                                                     Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                     ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                                                                                Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                                ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowCheckCast(SSACheckCastInstruction i,
                                                                                    Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                    ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                                                                            Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                            ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowGetField(SSAGetInstruction i,
                                                                                   Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                   ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowInvokeInterface(SSAInvokeInstruction i,
                                                                                          Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                          ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowInvokeSpecial(SSAInvokeInstruction i,
                                                                                        Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                        ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowInvokeStatic(SSAInvokeInstruction i,
                                                                                       Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                       ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowInvokeVirtual(SSAInvokeInstruction i,
                                                                                        Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                        ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowGoto(SSAGotoInstruction i,
                                                                               Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                               ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowLoadMetadata(SSALoadMetadataInstruction i,
                                                                                       Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                       ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowMonitor(SSAMonitorInstruction i,
                                                                                  Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                  ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowNewArray(SSANewInstruction i,
                                                                                   Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                   ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowNewObject(SSANewInstruction i,
                                                                                    Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                    ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowPutField(SSAPutInstruction i,
                                                                                   Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                   ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowReturn(SSAReturnInstruction i,
                                                                                 Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                 ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowSwitch(SSASwitchInstruction i,
                                                                                 Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                 ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowThrow(SSAThrowInstruction i,
                                                                                Set<FlowSensitizedVariableMap<Integer>> previousItems,
                                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap<Integer>> flowEmptyBlock(Set<FlowSensitizedVariableMap<Integer>> inItems,
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

}
