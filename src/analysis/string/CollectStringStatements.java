package analysis.string;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.string.StringVariableFactory.StringVariable;
import analysis.string.statements.AppendStringStatement;
import analysis.string.statements.ConstantStringStatement;
import analysis.string.statements.CopyStringStatement;
import analysis.string.statements.MergeStringStatement;
import analysis.string.statements.StringStatement;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
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
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;

/**
 * Data-flow analysis that collects constraints for a intra-procedural string value analysis. When solved these
 * constraints conservatively estimate which possible string constants String objects and StringBuilder objects can be
 * at run-time.
 * <p>
 * Propagated data-flow facts are a maps from value number for StringBuilder objects to the most recent StringVariable
 * representing it
 */
public class CollectStringStatements extends InstructionDispatchDataFlow<Map<Integer, StringVariable>> {

    private final TypeRepository types;
    private final PrettyPrinter pp;
    private final Set<StringStatement> statements = new LinkedHashSet<>();
    private final StringVariableFactory factory;
    private final IMethod m;
    private final StringAnalysisResults results;
    private final IR ir;
    private static final TypeName STRING = TypeName.findOrCreate("Ljava/lang/String");
    private static final TypeName STRING_BUILDER = TypeName.findOrCreate("Ljava/lang/StringBuilder");
    private static final TypeName CHAR_SEQUENCE = TypeName.findOrCreate("Ljava/lang/CharSequence");
    private static final TypeName CLASS = TypeName.findOrCreate("Ljava/lang/Class");
    private static final String APPEND = "append";
    private static final String TO_STRING = "toString";
    private static final String VALUE_OF = "valueOf";
    private static final String GET_NAME = "getName";

    private CollectStringStatements(IR ir, StringVariableFactory factory, StringAnalysisResults results) {
        super(true);
        this.factory = factory;
        this.m = ir.getMethod();
        this.ir = ir;
        this.types = new TypeRepository(ir);
        this.pp = new PrettyPrinter(ir);
        this.results = results;
    }

    public static Set<StringStatement> collect(IR ir, StringVariableFactory factory, StringAnalysisResults results) {
        CollectStringStatements css = new CollectStringStatements(ir, factory, results);
        css.dataflow(ir);
        return css.statements;
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowInstruction(SSAInstruction i,
                                                                                Set<Map<Integer, StringVariable>> inItems,
                                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                ISSABasicBlock current) {
        // Look for string literals
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int use = i.getUse(j);
            String s = getStringLiteral(use);
            if (s != null) {
                addStatement(new ConstantStringStatement(getOrCreateLocal(i.getUse(j)), AbstractString.create(s)));
            }
        }
        if (getAnalysisRecord(i) != null) {
            // Already processed this instruction, probably we are in a loop
            return factToMap(confluence(inItems, current), current, cfg);
        }
        return super.flowInstruction(i, inItems, cfg, current);
    }

    private String getStringLiteral(int use) {
        if (!ir.getSymbolTable().isStringConstant(use)) {
            // Not a string constant
            return null;
        }
        return ir.getSymbolTable().getStringValue(use);
    }

    private Map<Integer, StringVariable> confluence(Set<Map<Integer, StringVariable>> toMerge, ISSABasicBlock bb) {
        assert toMerge != null;
        if (toMerge.size() == 1) {
            return new LinkedHashMap<>(toMerge.iterator().next());
        }

        Set<Integer> allKeys = new LinkedHashSet<>();
        for (Map<Integer, StringVariable> map : toMerge) {
            allKeys.addAll(map.keySet());
        }

        Map<Integer, StringVariable> newMap = new LinkedHashMap<>();
        for (Integer valueNumber : allKeys) {
            Set<StringVariable> values = new LinkedHashSet<>();
            for (Map<Integer, StringVariable> map : toMerge) {
                if (map.containsKey(valueNumber)) {
                    values.add(map.get(valueNumber));
                }
            }
            if (values.size() > 1) {
                // Create a variable for the merge point and add a statement that merges all the input values to
                // compute the value after the merge
                StringVariable newVar = factory.createStringBuilder(valueNumber,
                                                                    m,
                                                                    bb.getNumber(),
                                                                    StringBuilderVarType.CONFLUENCE,
                                                                    -1,
                                                                    pp);
                addStatement(new MergeStringStatement(newVar, values));
                newMap.put(valueNumber, newVar);
            }
            else {
                assert values.size() == 1;
                newMap.put(valueNumber, values.iterator().next());
            }
        }

        return newMap;
    }

    @Override
    protected Map<Integer, StringVariable> flowBinaryOp(SSABinaryOpInstruction i,
                                                        Set<Map<Integer, StringVariable>> previousItems,
                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                        ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected Map<Integer, StringVariable> flowComparison(SSAComparisonInstruction i,
                                                          Set<Map<Integer, StringVariable>> previousItems,
                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                          ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected Map<Integer, StringVariable> flowConversion(SSAConversionInstruction i,
                                                          Set<Map<Integer, StringVariable>> previousItems,
                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                          ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected Map<Integer, StringVariable> flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                                                  Set<Map<Integer, StringVariable>> previousItems,
                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                  ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected Map<Integer, StringVariable> flowGetStatic(SSAGetInstruction i,
                                                         Set<Map<Integer, StringVariable>> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        TypeName type = getTypeName(i.getDef());
        if (type.equals(STRING)) {
            IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
            IField field = cha.resolveField(i.getDeclaredField());
            StringVariable def = getOrCreateLocal(i.getDef());
            if (field.isFinal()) {
                // static final field
                // Get the results of the String analysis for the static initializer for the containing class
                IMethod clinit = getClassInitMethod(i.getDeclaredField().getDeclaringClass());
                Map<StringVariable, AbstractString> res = results.getResultsForMethod(clinit);
                StringVariable fieldVar = factory.getOrCreateStaticField(field.getReference());
                AbstractString s = res.get(fieldVar);
                addStatement(new ConstantStringStatement(def, s));
                return confluence(previousItems, current);
            }
        }

        return setDefToAny(i, previousItems, current);
    }

    /**
     * Get the static initialization method for the given class
     *
     * @param declaringClass class to get the init method for
     * @return resolved static initializer for the given class
     */
    private static IMethod getClassInitMethod(TypeReference declaringClass) {
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
        MethodReference mr = MethodReference.findOrCreate(declaringClass, MethodReference.clinitSelector);
        return cha.resolveMethod(mr);
    }

    @Override
    protected Map<Integer, StringVariable> flowInstanceOf(SSAInstanceofInstruction i,
                                                          Set<Map<Integer, StringVariable>> previousItems,
                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                          ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected Map<Integer, StringVariable> flowPhi(SSAPhiInstruction i,
                                                   Set<Map<Integer, StringVariable>> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        TypeName type = getTypeName(i.getDef());
        if (type.equals(STRING) || type.equals(CLASS)) {
            Set<StringVariable> vars = new LinkedHashSet<>();
            for (int j = 0; j < i.getNumberOfUses(); j++) {
                vars.add(getOrCreateLocal(i.getUse(j)));
            }
            StringVariable local = getOrCreateLocal(i.getDef());
            addStatement(new MergeStringStatement(local, vars));
        }
        else if (type.equals(STRING_BUILDER)) {
            Set<StringVariable> vars = new LinkedHashSet<>();
            Map<Integer, StringVariable> in = confluence(previousItems, current);
            for (int j = 0; j < i.getNumberOfUses(); j++) {
                vars.add(in.get(i.getUse(j)));
            }
            StringVariable local = factory.createStringBuilder(i.getDef(),
                                                               m,
                                                               current.getNumber(),
                                                               StringBuilderVarType.DEF,
                                                               -1,
                                                               pp);
            addStatement(new MergeStringStatement(local, vars));
            in.put(i.getDef(), local);
            return in;
        }
        return confluence(previousItems, current);
    }

    @Override
    protected Map<Integer, StringVariable> flowPutStatic(SSAPutInstruction i,
                                                         Set<Map<Integer, StringVariable>> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        TypeName type = getTypeName(i.getDef());
        if (type.equals(STRING)) {
            IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
            IField field = cha.resolveField(i.getDeclaredField());
            if (field.isFinal()) {
                // static final field
                IMethod clinit = getClassInitMethod(i.getDeclaredField().getDeclaringClass());
                Map<StringVariable, AbstractString> res = results.getResultsForMethod(clinit);
                StringVariable fieldVar = factory.getOrCreateStaticField(field.getReference());
                AbstractString s = res.get(fieldVar);

                StringVariable def = getOrCreateLocal(i.getDef());
                addStatement(new ConstantStringStatement(def, s));
            }
        }
        // If this is not a static final String don't keep track of the store, the access will handle it correctly

        return confluence(previousItems, current);
    }

    @Override
    protected Map<Integer, StringVariable> flowUnaryNegation(SSAUnaryOpInstruction i,
                                                             Set<Map<Integer, StringVariable>> previousItems,
                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                             ISSABasicBlock current) {
        return confluence(previousItems, current);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowArrayLength(SSAArrayLengthInstruction i,
                                                                                Set<Map<Integer, StringVariable>> previousItems,
                                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowArrayLoad(SSAArrayLoadInstruction i,
                                                                              Set<Map<Integer, StringVariable>> previousItems,
                                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                              ISSABasicBlock current) {
        return factToMap(setDefToAny(i, previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowArrayStore(SSAArrayStoreInstruction i,
                                                                               Set<Map<Integer, StringVariable>> previousItems,
                                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                               ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                                                                          Set<Map<Integer, StringVariable>> previousItems,
                                                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                          ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowCheckCast(SSACheckCastInstruction i,
                                                                              Set<Map<Integer, StringVariable>> previousItems,
                                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                              ISSABasicBlock current) {
        TypeName type = getTypeName(i.getDef());
        if (type.equals(STRING)) {
            StringVariable left = getOrCreateLocal(i.getDef());
            if (getTypeName(i.getUse(0)).equals(STRING)) {
                StringVariable right = getOrCreateLocal(i.getUse(0));
                addStatement(new CopyStringStatement(left, right));
            }
            else {
                // The right side might not be a string so is not a literal
                addStatement(new ConstantStringStatement(left, AbstractString.ANY));
            }

        }
        else if (type.equals(STRING_BUILDER)) {
            StringVariable left = getOrCreateLocal(i.getDef());
            if (getTypeName(i.getUse(0)).equals(STRING_BUILDER)) {
                StringVariable right = getOrCreateLocal(i.getUse(0));
                addStatement(new CopyStringStatement(left, right));
            }
            else {
                // The right side might not be a StringBuilder so is not a literal
                addStatement(new ConstantStringStatement(left, AbstractString.ANY));
            }
            Map<Integer, StringVariable> in = confluence(previousItems, current);
            in.put(i.getDef(), left);
            return factToMap(in, current, cfg);
        }
        else if (type.equals(CLASS)) {
            StringVariable left = getOrCreateLocal(i.getDef());
            if (getTypeName(i.getUse(0)).equals(CLASS)) {
                StringVariable right = getOrCreateLocal(i.getUse(0));
                addStatement(new CopyStringStatement(left, right));
            }
            else {
                // The right side might not be a Class so is not a no-op cast
                addStatement(new ConstantStringStatement(left, AbstractString.ANY));
            }
        }
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                                                                      Set<Map<Integer, StringVariable>> previousItems,
                                                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                      ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowGetField(SSAGetInstruction i,
                                                                             Set<Map<Integer, StringVariable>> previousItems,
                                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                             ISSABasicBlock current) {
        return factToMap(setDefToAny(i, previousItems, current), current, cfg);
    }

    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowInvoke(SSAInvokeInstruction i,
                                                                           Set<Map<Integer, StringVariable>> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        MethodReference mr = i.getDeclaredTarget();
        TypeName type = mr.getDeclaringClass().getName();
        Map<Integer, StringVariable> in = confluence(previousItems, current);
        if (type.equals(STRING)) {
            if (mr.isInit()) {
                if (mr.getNumberOfParameters() == 1) {
                    if (mr.getParameterType(0).getName().equals(STRING)) {
                        // String.<init>(String)
                        StringVariable left = getOrCreateLocal(i.getReceiver());
                        StringVariable right = getOrCreateLocal(i.getUse(1));
                        addStatement(new CopyStringStatement(left, right));
                        return factToMap(confluence(previousItems, current), current, cfg);
                    }
                    else if (mr.getParameterType(0).getName().equals(STRING_BUILDER)) {
                        // String.<init>String(StringBuilder)
                        StringVariable left = getOrCreateLocal(i.getReceiver());
                        StringVariable right = in.get(i.getUse(1));
                        addStatement(new CopyStringStatement(left, right));
                        return factToMap(in, current, cfg);
                    }
                }
                else if (mr.getNumberOfParameters() == 0) {
                    // String.<init>String()
                    StringVariable left = getOrCreateLocal(i.getReceiver());
                    addStatement(new ConstantStringStatement(left, AbstractString.create("")));
                    return factToMap(in, current, cfg);
                }
                // Some other String.<init>, just be conservative
                System.err.println("RECEIVER: " + i.getReceiver());
                StringVariable left = getOrCreateLocal(i.getReceiver());
                addStatement(new ConstantStringStatement(left, AbstractString.ANY));
                return factToMap(in, current, cfg);
            }
            else if (mr.getName().toString().equals(TO_STRING)) {
                // String.toString()
                if (i.hasDef()) {
                    StringVariable left = getOrCreateLocal(i.getDef());
                    StringVariable right = getOrCreateLocal(i.getReceiver());
                    addStatement(new CopyStringStatement(left, right));
                }
                return factToMap(confluence(previousItems, current), current, cfg);
            }
            else if (mr.getName().toString().equals(VALUE_OF)) {
                // String.valueOf(Object)
                if (i.hasDef() && getTypeName(i.getUse(0)).equals(STRING)) {
                    // String.valueOf(Object) with a String argument
                    StringVariable left = getOrCreateLocal(i.getDef());
                    StringVariable right = getOrCreateLocal(i.getUse(0));
                    addStatement(new CopyStringStatement(left, right));
                    return factToMap(confluence(previousItems, current), current, cfg);
                }
            }
            // TODO could model other String methods that return Strings (e.g. substring, replace, trim, etc.)
        }
        else if (type.equals(STRING_BUILDER)) {
            if (mr.isInit()) {
                if (mr.getNumberOfParameters() == 1 && mr.getParameterType(0).getName().equals(STRING)) {
                    // StringBuilder.<init>(String)
                    StringVariable newSB = factory.createStringBuilder(i.getReceiver(),
                                                                       m,
                                                                       current.getNumber(),
                                                                       StringBuilderVarType.INIT,
                                                                       i.getProgramCounter(),
                                                                       pp);
                    StringVariable arg = getOrCreateLocal(i.getUse(1));
                    addStatement(new CopyStringStatement(newSB, arg));
                    Map<Integer, StringVariable> out = confluence(previousItems, current);
                    out.put(i.getReceiver(), newSB);
                    return factToMap(out, current, cfg);
                }
                else if (mr.getNumberOfParameters() == 1 && mr.getParameterType(0).getName().equals(CHAR_SEQUENCE)) {
                    // StringBuilder.<init>(CharSequence)
                    StringVariable newSB = factory.createStringBuilder(i.getReceiver(),
                                                                       m,
                                                                       current.getNumber(),
                                                                       StringBuilderVarType.INIT,
                                                                       i.getProgramCounter(),
                                                                       pp);
                    addStatement(new ConstantStringStatement(newSB, AbstractString.ANY));
                    Map<Integer, StringVariable> out = confluence(previousItems, current);
                    out.put(i.getReceiver(), newSB);
                    return factToMap(out, current, cfg);
                }
                else {
                    // StringBuilder.<init>(int)
                    // or
                    // StringBuilder.<init>()
                    StringVariable newSB = factory.createStringBuilder(i.getReceiver(),
                                                                       m,
                                                                       current.getNumber(),
                                                                       StringBuilderVarType.INIT,
                                                                       i.getProgramCounter(),
                                                                       pp);
                    addStatement(new ConstantStringStatement(newSB, AbstractString.create("")));
                    Map<Integer, StringVariable> out = confluence(previousItems, current);
                    out.put(i.getReceiver(), newSB);
                    return factToMap(out, current, cfg);
                }
            }
            else if (mr.getName().toString().equals(APPEND) && mr.getParameterType(0).getName().equals(STRING)) {
                // StringBuilder.append(String)
                StringVariable sbBefore = in.get(i.getReceiver());
                StringVariable sbAfter = factory.createStringBuilder(i.getReceiver(),
                                                                     m,
                                                                     current.getNumber(),
                                                                     StringBuilderVarType.APPEND,
                                                                     i.getProgramCounter(),
                                                                     pp);
                StringVariable argument = getOrCreateLocal(i.getUse(1));
                addStatement(new AppendStringStatement(sbAfter, sbBefore, argument));
                Map<Integer, StringVariable> out = in;
                out.put(i.getReceiver(), sbAfter);
                if (i.hasDef()) {
                    // StringBuilder.append returns a reference to the receiver object
                    out.put(i.getDef(), sbAfter);
                }
                return factToMap(out, current, cfg);
            }
            else if (mr.getName().toString().equals(TO_STRING)) {
                // StringBuilder.toString()
                if (i.hasDef()) {
                    StringVariable left = getOrCreateLocal(i.getDef());
                    StringVariable right = in.get(i.getReceiver());
                    addStatement(new CopyStringStatement(left, right));
                }
                return factToMap(confluence(previousItems, current), current, cfg);
            }
        }
        else if (type.equals(CLASS)) {
            if (mr.getName().toString().equals(GET_NAME)) {
                // Class.getName()
                if (i.hasDef()) {
                    StringVariable left = getOrCreateLocal(i.getDef());
                    StringVariable right = getOrCreateLocal(i.getReceiver());
                    addStatement(new CopyStringStatement(left, right));
                }
                return factToMap(confluence(previousItems, current), current, cfg);
            }
        }

        // Model any other method that returns a String or StringBuilder as opaque, could be more precise if this was a
        // true inter-procedural analysis
        Map<Integer, StringVariable> out = setDefToAny(i, previousItems, current);

        // A StringBuilder argument might get changed (and probably will) so we have to be conservative
        for (int j = 0; j < mr.getNumberOfParameters(); j++) {
            Set<Integer> buildersFound = new HashSet<>();
            if (i.getDeclaredTarget().getParameterType(j).getName().equals(STRING_BUILDER)) {
                int argNum = i.getUse(j + (i.isStatic() ? 0 : 1));
                if (buildersFound.contains(argNum)) {
                    // We already handled this StringBuilder for this method call
                    continue;
                }
                buildersFound.add(argNum);
                StringVariable newVar = factory.createStringBuilder(argNum,
                                                                    m,
                                                                    current.getNumber(),
                                                                    StringBuilderVarType.METHOD_ARG,
                                                                    i.getProgramCounter(),
                                                                    pp);
                addStatement(new ConstantStringStatement(newVar, AbstractString.ANY));
                out.put(argNum, newVar);
            }
        }
        return factToMap(out, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowInvokeInterface(SSAInvokeInstruction i,
                                                                                    Set<Map<Integer, StringVariable>> previousItems,
                                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                    ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowInvokeSpecial(SSAInvokeInstruction i,
                                                                                  Set<Map<Integer, StringVariable>> previousItems,
                                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                  ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowInvokeStatic(SSAInvokeInstruction i,
                                                                                 Set<Map<Integer, StringVariable>> previousItems,
                                                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                 ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowInvokeVirtual(SSAInvokeInstruction i,
                                                                                  Set<Map<Integer, StringVariable>> previousItems,
                                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                  ISSABasicBlock current) {
        return flowInvoke(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowGoto(SSAGotoInstruction i,
                                                                         Set<Map<Integer, StringVariable>> previousItems,
                                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                         ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowLoadMetadata(SSALoadMetadataInstruction i,
                                                                                 Set<Map<Integer, StringVariable>> previousItems,
                                                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                 ISSABasicBlock current) {
        // loadmetadata is the instruction corresponding to Object.class, which is frequently used in Intent constructors
        // The only thing used from these Class objects is the name of the class so track the String corresponding to the name
        // Associate this String with the local variable assigned into by the loadmetadata instruction (i.e. the Class object)
        if (i.getType().getName().equals(CLASS)) {
            // This is creating a class object
            StringVariable var = getOrCreateLocal(i.getDef());
            if (i.getToken() instanceof TypeReference) {
                // We have the type of the class object being created
                String className = PrettyPrinter.typeString((TypeReference) i.getToken());
                addStatement(new ConstantStringStatement(var, AbstractString.create(className)));
            }
            else {
                addStatement(new ConstantStringStatement(var, AbstractString.ANY));
            }
            return factToMap(confluence(previousItems, current), current, cfg);
        }

        return factToMap(setDefToAny(i, previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowMonitor(SSAMonitorInstruction i,
                                                                            Set<Map<Integer, StringVariable>> previousItems,
                                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                            ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowNewArray(SSANewInstruction i,
                                                                             Set<Map<Integer, StringVariable>> previousItems,
                                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                             ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowNewObject(SSANewInstruction i,
                                                                              Set<Map<Integer, StringVariable>> previousItems,
                                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                              ISSABasicBlock current) {
        // Even if it is a String it is uninitialized and cannot be used until after the <init> which is handled in flowInvoke
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowPutField(SSAPutInstruction i,
                                                                             Set<Map<Integer, StringVariable>> previousItems,
                                                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                             ISSABasicBlock current) {
        // Since this is intraprocedural we do not track field puts
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowReturn(SSAReturnInstruction i,
                                                                           Set<Map<Integer, StringVariable>> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowSwitch(SSASwitchInstruction i,
                                                                           Set<Map<Integer, StringVariable>> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowThrow(SSAThrowInstruction i,
                                                                          Set<Map<Integer, StringVariable>> previousItems,
                                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                          ISSABasicBlock current) {
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Map<Integer, StringVariable>> flowEmptyBlock(Set<Map<Integer, StringVariable>> inItems,
                                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                               ISSABasicBlock current) {
        return factToMap(confluence(inItems, current), current, cfg);
    }

    @Override
    protected void post(IR ir) {
        // intentionally left blank
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        return false;
    }

    private void addStatement(StringStatement ss) {
        statements.add(ss);
    }

    /**
     * Create a StringVariable for the given local variable number, create if it does not exist
     *
     * @param local variable number for the local variable
     *
     * @return String variable for the given local
     */
    private StringVariable getOrCreateLocal(int local) {
        return factory.getOrCreateLocal(local, m, pp);
    }

    /**
     * If the return type is one of the tracked types, create a statement setting the def in the given instruction to
     * the constant StringStatement set to AbstractString.ANY. This is used for any opaque instruction such as a field
     * access or a method call (since this analysis is intra-procedural).
     *
     * @param i instruction to be processed
     * @param previousItems input data-flow facts for all incoming edges
     * @param current current basic block
     * @return the map from string builder local variable number to string variable after processing the given
     *         instruction
     */
    private Map<Integer, StringVariable> setDefToAny(SSAInstruction i,
                                                       Set<Map<Integer, StringVariable>> previousItems,
                                                       ISSABasicBlock current) {
        if (i.hasDef()) {
            TypeName type = getTypeName(i.getDef());
            if (type.equals(STRING) || type.equals(CLASS)) {
                StringVariable left = getOrCreateLocal(i.getDef());
                addStatement(new ConstantStringStatement(left, AbstractString.ANY));
            }
            else if (type.equals(STRING_BUILDER)) {
                StringVariable left = factory.createStringBuilder(i.getDef(),
                                                                  m,
                                                                  current.getNumber(),
                                                                  StringBuilderVarType.DEF,
                                                                  -1,
                                                                  pp);
                addStatement(new ConstantStringStatement(left, AbstractString.ANY));
                Map<Integer, StringVariable> in = confluence(previousItems, current);
                in.put(i.getDef(), left);
                return in;
            }
        }

        return confluence(previousItems, current);
    }

    /**
     * Get the name of the type for the given local variable. We do not use the TypeReference since in this case the
     * class loader may be different.
     *
     * @param local local variable number of the variable to get the type for
     * @return name for the local variable
     */
    private TypeName getTypeName(int local) {
        return types.getType(local).getName();
    }
}
