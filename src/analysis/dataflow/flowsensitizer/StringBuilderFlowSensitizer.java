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

public class StringBuilderFlowSensitizer extends FunctionalInstructionDispatchDataFlow<FlowSensitizedVariableMap> {

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
        return new StringBuilderFlowSensitizer(forward);
    }

    /*
     * Constructors
     */
    private StringBuilderFlowSensitizer(boolean forward) {
        super(forward);
    }

    /*
     * Helper Methods
     */

    private FlowSensitizedVariableMap joinMaps(Collection<FlowSensitizedVariableMap> c) {
        return FlowSensitizedVariableMap.joinCollection(c);
    }

    private Map<ISSABasicBlock, FlowSensitizedVariableMap> sameForAllSuccessors(Iterator<ISSABasicBlock> succNodes,
                                                                                FlowSensitizedVariableMap fact) {
        Map<ISSABasicBlock, FlowSensitizedVariableMap> m = new HashMap<>();

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
    public Map<SSAInstruction, AnalysisRecord<FlowSensitizedVariableMap>> runDataFlowAnalysis(IMethod m) {
        IR ir = AnalysisUtil.getIR(m);
        this.dataflow(ir);
        Map<SSAInstruction, AnalysisRecord<FlowSensitizedVariableMap>> map = new HashMap<>();
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
            Map<ISSABasicBlock, FlowSensitizedVariableMap> outputOrNull = this.getAnalysisRecord(i).getOutput();
            defMap.put(i,
                       outputOrNull == null ? Collections.<Integer, Integer> emptyMap()
                               : joinMaps(outputOrNull.values()).getInsensitiveToFlowSensistiveMap());
        }
        return new OrderedPair<>(defMap, useMap);
    }

    /*
     * Actual logic of the data flow (as opposed to rote method overrides)
     *
     * NOTE: don't mutate `fact`!!
     */

    private Map<ISSABasicBlock, FlowSensitizedVariableMap> flowGenericInvoke(SSAInvokeInstruction i,
                                                                             Iterator<ISSABasicBlock> succNodes,
                                                                             FlowSensitizedVariableMap fact) {
        System.err.println("flowGenericInvoke(" + i + ", " + succNodes + ", " + fact + ")");
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
    protected FlowSensitizedVariableMap flowBinaryOp(SSABinaryOpInstruction i,
                                                     Set<FlowSensitizedVariableMap> previousItems,
                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                     ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap flowComparison(SSAComparisonInstruction i,
                                                       Set<FlowSensitizedVariableMap> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap flowConversion(SSAConversionInstruction i,
                                                       Set<FlowSensitizedVariableMap> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                                               Set<FlowSensitizedVariableMap> previousItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap flowGetStatic(SSAGetInstruction i,
                                                      Set<FlowSensitizedVariableMap> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap flowInstanceOf(SSAInstanceofInstruction i,
                                                       Set<FlowSensitizedVariableMap> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap flowPhi(SSAPhiInstruction i, Set<FlowSensitizedVariableMap> previousItems,
                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap flowPutStatic(SSAPutInstruction i,
                                                      Set<FlowSensitizedVariableMap> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected FlowSensitizedVariableMap flowUnaryNegation(SSAUnaryOpInstruction i,
                                                          Set<FlowSensitizedVariableMap> previousItems,
                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                          ISSABasicBlock current) {
        return joinMaps(previousItems);
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowArrayLength(SSAArrayLengthInstruction i,
                                                                             Set<FlowSensitizedVariableMap> previousItems,
                                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                             ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowArrayLoad(SSAArrayLoadInstruction i,
                                                                           Set<FlowSensitizedVariableMap> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowArrayStore(SSAArrayStoreInstruction i,
                                                                            Set<FlowSensitizedVariableMap> previousItems,
                                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                            ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                                                                       Set<FlowSensitizedVariableMap> previousItems,
                                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                       ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowCheckCast(SSACheckCastInstruction i,
                                                                           Set<FlowSensitizedVariableMap> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                                                                   Set<FlowSensitizedVariableMap> previousItems,
                                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                   ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowGetField(SSAGetInstruction i,
                                                                          Set<FlowSensitizedVariableMap> previousItems,
                                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                          ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowInvokeInterface(SSAInvokeInstruction i,
                                                                                 Set<FlowSensitizedVariableMap> previousItems,
                                                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                 ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowInvokeSpecial(SSAInvokeInstruction i,
                                                                               Set<FlowSensitizedVariableMap> previousItems,
                                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                               ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowInvokeStatic(SSAInvokeInstruction i,
                                                                              Set<FlowSensitizedVariableMap> previousItems,
                                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                              ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowInvokeVirtual(SSAInvokeInstruction i,
                                                                               Set<FlowSensitizedVariableMap> previousItems,
                                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                               ISSABasicBlock current) {
        return flowGenericInvoke(i, cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowGoto(SSAGotoInstruction i,
                                                                      Set<FlowSensitizedVariableMap> previousItems,
                                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                      ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowLoadMetadata(SSALoadMetadataInstruction i,
                                                                              Set<FlowSensitizedVariableMap> previousItems,
                                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                              ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowMonitor(SSAMonitorInstruction i,
                                                                         Set<FlowSensitizedVariableMap> previousItems,
                                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                         ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowNewArray(SSANewInstruction i,
                                                                          Set<FlowSensitizedVariableMap> previousItems,
                                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                          ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowNewObject(SSANewInstruction i,
                                                                           Set<FlowSensitizedVariableMap> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowPutField(SSAPutInstruction i,
                                                                          Set<FlowSensitizedVariableMap> previousItems,
                                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                          ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowReturn(SSAReturnInstruction i,
                                                                        Set<FlowSensitizedVariableMap> previousItems,
                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                        ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowSwitch(SSASwitchInstruction i,
                                                                        Set<FlowSensitizedVariableMap> previousItems,
                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                        ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowThrow(SSAThrowInstruction i,
                                                                       Set<FlowSensitizedVariableMap> previousItems,
                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                       ISSABasicBlock current) {
        return sameForAllSuccessors(cfg.getSuccNodes(current), joinMaps(previousItems));
    }

    @Override
    protected Map<ISSABasicBlock, FlowSensitizedVariableMap> flowEmptyBlock(Set<FlowSensitizedVariableMap> inItems,
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
