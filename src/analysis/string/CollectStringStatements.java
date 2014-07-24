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
    private static final String APPEND = "append";
    private static final String TO_STRING = "toString";
    private static final String VALUE_OF = "valueOf";

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
                statements.add(new ConstantStringStatement(factory.getOrCreateLocal(i.getUse(j), m, pp),
                                                           AbstractString.create(s)));
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
                statements.add(new MergeStringStatement(newVar, values));
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
        TypeName type = types.getType(i.getDef()).getName();
        if (type.equals(STRING)) {
            IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
            IField field = cha.resolveField(i.getDeclaredField());
            StringVariable def = factory.getOrCreateLocal(i.getDef(), m, pp);
            if (field.isFinal()) {
                // static final field
                // Get the results of the String analysis for the static initializer for the containing class
                IMethod clinit = getClassInitMethod(i.getDeclaredField().getDeclaringClass());
                Map<StringVariable, AbstractString> res = results.getResultsForMethod(clinit);
                StringVariable fieldVar = factory.getOrCreateStaticField(field.getReference());
                AbstractString s = res.get(fieldVar);
                statements.add(new ConstantStringStatement(def, s));
            }
            else {
                // Intra-procedural analysis so we don't track non-final fields
                statements.add(new ConstantStringStatement(def, AbstractString.ANY));
            }
        }
        else if (type.equals(STRING_BUILDER)) {
            // Intra-procedural analysis so we don't track StringBuilder fields
            // We'll treat this like a new StringBuilder that could contain any string
            StringVariable def = factory.createStringBuilder(i.getDef(),
                                                             m,
                                                             current.getNumber(),
                                                             StringBuilderVarType.DEF,
                                                             -1,
                                                             pp);
            statements.add(new ConstantStringStatement(def, AbstractString.ANY));
            Map<Integer, StringVariable> out = confluence(previousItems, current);
            out.put(i.getDef(), def);
            return out;
        }

        return confluence(previousItems, current);
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
        TypeName type = types.getType(i.getDef()).getName();
        if (type.equals(STRING)) {
            Set<StringVariable> vars = new LinkedHashSet<>();
            for (int j = 0; j < i.getNumberOfUses(); j++) {
                vars.add(factory.getOrCreateLocal(i.getUse(j), m, pp));
            }
            StringVariable local = factory.getOrCreateLocal(i.getDef(), m, pp);
            statements.add(new MergeStringStatement(local, vars));
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
            statements.add(new MergeStringStatement(local, vars));
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
        TypeName type = types.getType(i.getDef()).getName();
        if (type.equals(STRING)) {
            IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
            IField field = cha.resolveField(i.getDeclaredField());
            if (field.isFinal()) {
                // static final field
                IMethod clinit = getClassInitMethod(i.getDeclaredField().getDeclaringClass());
                Map<StringVariable, AbstractString> res = results.getResultsForMethod(clinit);
                StringVariable fieldVar = factory.getOrCreateStaticField(field.getReference());
                AbstractString s = res.get(fieldVar);

                StringVariable def = factory.getOrCreateLocal(i.getDef(), m, pp);
                statements.add(new ConstantStringStatement(def, s));
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
        // could handle array stores more precisely, the array could be a set of strings that is everything ever put into it
        TypeName type = types.getType(i.getDef()).getName();
        if (type.equals(STRING)) {
            StringVariable var = factory.getOrCreateLocal(i.getDef(), m, pp);
            statements.add(new ConstantStringStatement(var, AbstractString.ANY));
        }
        else if (type.equals(STRING_BUILDER)) {
            StringVariable var = factory.createStringBuilder(i.getDef(),
                                                             m,
                                                             current.getNumber(),
                                                             StringBuilderVarType.DEF,
                                                             -1,
                                                             pp);
            statements.add(new ConstantStringStatement(var, AbstractString.ANY));
            Map<Integer, StringVariable> in = confluence(previousItems, current);
            in.put(i.getDef(), var);
            return factToMap(in, current, cfg);
        }

        return factToMap(confluence(previousItems, current), current, cfg);
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
        TypeName type = types.getType(i.getDef()).getName();
        if (type.equals(STRING)) {
            StringVariable left = factory.getOrCreateLocal(i.getDef(), m, pp);
            if (types.getType(i.getUse(0)).equals(STRING)) {
                StringVariable right = factory.getOrCreateLocal(i.getUse(0), m, pp);
                statements.add(new CopyStringStatement(left, right));
            }
            else {
                // The right side might not be a string so is not a literal
                statements.add(new ConstantStringStatement(left, AbstractString.ANY));
            }

        }
        if (type.equals(STRING_BUILDER)) {
            StringVariable left = factory.getOrCreateLocal(i.getDef(), m, pp);
            if (types.getType(i.getUse(0)).equals(STRING_BUILDER)) {
                StringVariable right = factory.getOrCreateLocal(i.getUse(0), m, pp);
                statements.add(new CopyStringStatement(left, right));
            }
            else {
                // The right side might not be a string so is not a literal
                statements.add(new ConstantStringStatement(left, AbstractString.ANY));
            }
            Map<Integer, StringVariable> in = confluence(previousItems, current);
            in.put(i.getDef(), left);
            return factToMap(in, current, cfg);
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
        // Since we are intraprocedural-ish we have to set the results to ANY here
        TypeName type = types.getType(i.getDef()).getName();
        if (type.equals(STRING)) {
            StringVariable left = factory.getOrCreateLocal(i.getDef(), m, pp);
            statements.add(new ConstantStringStatement(left, AbstractString.ANY));
        }
        else if (type.equals(STRING_BUILDER)) {
            StringVariable left = factory.createStringBuilder(i.getDef(),
                                                              m,
                                                              current.getNumber(),
                                                              StringBuilderVarType.DEF,
                                                              -1,
                                                              pp);
            statements.add(new ConstantStringStatement(left, AbstractString.ANY));
            Map<Integer, StringVariable> in = confluence(previousItems, current);
            in.put(i.getDef(), left);
            return factToMap(in, current, cfg);
        }
        return factToMap(confluence(previousItems, current), current, cfg);
    }

    private Map<ISSABasicBlock, Map<Integer, StringVariable>> flowInvoke(SSAInvokeInstruction i,
                                                                         Set<Map<Integer, StringVariable>> previousItems,
                                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                         ISSABasicBlock current) {
        MethodReference mr = i.getDeclaredTarget();
        TypeName type = mr.getDeclaringClass().getName();
        Map<Integer, StringVariable> in = confluence(previousItems, current);
        System.err.println(i + " params: " + mr.getNumberOfParameters() + " isInit: " + mr.isInit());
        if (type.equals(STRING)) {
            if (mr.isInit()) {
                if (mr.getNumberOfParameters() == 1) {
                    if (mr.getParameterType(0).getName().equals(STRING)) {
                        // String.<init>(String)
                        StringVariable left = factory.getOrCreateLocal(i.getReceiver(), m, pp);
                        StringVariable right = factory.getOrCreateLocal(i.getUse(1), m, pp);
                        statements.add(new CopyStringStatement(left, right));
                        return factToMap(confluence(previousItems, current), current, cfg);
                    }
                    else if (mr.getParameterType(0).getName().equals(STRING_BUILDER)) {
                        // String.<init>String(StringBuilder)
                        StringVariable left = factory.getOrCreateLocal(i.getReceiver(), m, pp);
                        StringVariable right = in.get(i.getUse(1));
                        statements.add(new CopyStringStatement(left, right));
                        return factToMap(in, current, cfg);
                    }
                }
                else if (mr.getNumberOfParameters() == 0) {
                    // String.<init>String()
                    StringVariable left = factory.getOrCreateLocal(i.getReceiver(), m, pp);
                    statements.add(new ConstantStringStatement(left, AbstractString.create("")));
                    return factToMap(in, current, cfg);
                }
                // Some other String.<init>, just be conservative
                System.err.println("RECEIVER: " + i.getReceiver());
                StringVariable left = factory.getOrCreateLocal(i.getReceiver(), m, pp);
                statements.add(new ConstantStringStatement(left, AbstractString.ANY));
                return factToMap(in, current, cfg);
            }
            else if (mr.getName().toString().equals(TO_STRING)) {
                // String.toString()
                if (i.hasDef()) {
                    StringVariable left = factory.getOrCreateLocal(i.getDef(), m, pp);
                    StringVariable right = factory.getOrCreateLocal(i.getReceiver(), m, pp);
                    statements.add(new CopyStringStatement(left, right));
                    return factToMap(confluence(previousItems, current), current, cfg);
                }
            }
            else if (mr.getName().toString().equals(VALUE_OF)) {
                // String.valueOf(Object)
                System.err.println("FOUND: " + PrettyPrinter.methodString(mr) + " argType: "
                        + PrettyPrinter.typeString(types.getType(i.getUse(0))));
                if (i.hasDef()) {
                    if (types.getType(i.getUse(0)).getName().equals(STRING)) {
                        // String.valueOf(Object) with a String argument
                        StringVariable left = factory.getOrCreateLocal(i.getDef(), m, pp);
                        StringVariable right = factory.getOrCreateLocal(i.getUse(0), m, pp);
                        statements.add(new CopyStringStatement(left, right));
                        return factToMap(confluence(previousItems, current), current, cfg);
                    }
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
                    StringVariable arg = factory.getOrCreateLocal(i.getUse(1), m, pp);
                    statements.add(new CopyStringStatement(newSB, arg));
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
                    statements.add(new ConstantStringStatement(newSB, AbstractString.ANY));
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
                    statements.add(new ConstantStringStatement(newSB, AbstractString.create("")));
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
                StringVariable argument = factory.getOrCreateLocal(i.getUse(1), m, pp);
                statements.add(new AppendStringStatement(sbAfter, sbBefore, argument));
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
                    StringVariable left = factory.getOrCreateLocal(i.getDef(), m, pp);
                    StringVariable right = in.get(i.getReceiver());
                    statements.add(new CopyStringStatement(left, right));
                    return factToMap(confluence(previousItems, current), current, cfg);
                }
            }
        }

        // Model any other method that returns a String or StringBuilder as opaque, could be more precise if this was a
        // true inter-procedural analysis
        Map<Integer, StringVariable> out = confluence(previousItems, current);
        if (i.hasDef()) {
            TypeName returnType = types.getType(i.getDef()).getName();
            if (returnType.equals(STRING)) {
                StringVariable left = factory.getOrCreateLocal(i.getDef(), m, pp);
                statements.add(new ConstantStringStatement(left, AbstractString.ANY));
            }
            else if (returnType.equals(STRING_BUILDER)) {
                StringVariable left = factory.createStringBuilder(i.getDef(),
                                                                  m,
                                                                  current.getNumber(),
                                                                  StringBuilderVarType.DEF,
                                                                  i.getProgramCounter(),
                                                                  pp);
                statements.add(new ConstantStringStatement(left, AbstractString.ANY));
                out.put(i.getDef(), left);
            }
        }

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
                statements.add(new ConstantStringStatement(newVar, AbstractString.ANY));
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
        TypeName type = types.getType(i.getDef()).getName();
        if (type.equals(STRING)) {
            StringVariable left = factory.getOrCreateLocal(i.getDef(), m, pp);
            statements.add(new ConstantStringStatement(left, AbstractString.ANY));
        }
        else if (type.equals(STRING_BUILDER)) {
            StringVariable left = factory.createStringBuilder(i.getDef(),
                                                              m,
                                                              current.getNumber(),
                                                              StringBuilderVarType.DEF,
                                                              -1,
                                                              pp);
            statements.add(new ConstantStringStatement(left, AbstractString.ANY));
            Map<Integer, StringVariable> in = confluence(previousItems, current);
            in.put(i.getDef(), left);
            return factToMap(in, current, cfg);
        }
        return factToMap(confluence(previousItems, current), current, cfg);
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

}
