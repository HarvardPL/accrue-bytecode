package analysis.pointer.registrar;

import java.util.HashMap;
import java.util.Map;

import types.TypeRepository;
import util.Triplet;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.StringAndReflectiveUtil;
import analysis.pointer.registrar.strings.StringVariable;
import analysis.pointer.registrar.strings.StringVariableFactory;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

public final class FlowSensitiveStringVariableFactory {

    /* This class expects the `initialSubscript` to be equal to the subscript
     * corresponding to the sensitized variable valid at the beginning of a method,
     * i.e., before any instructions are ready */
    private static final int initialSubscript = 0;

    private final Map<SSAInstruction, Map<Integer, Integer>> useSensitizerAtInstruction;
    private final Map<SSAInstruction, Map<Integer, Integer>> defSensitizerAtInstruction;
    private final Map<Triplet<IMethod, Integer, Integer>, StringVariable> localsCache;
    private final Map<IField, StringVariable> staticFieldCache;

    /* Factory Methods */

    public static FlowSensitiveStringVariableFactory make(Map<SSAInstruction, Map<Integer, Integer>> defSensitizerAtInstruction,
                                                          Map<SSAInstruction, Map<Integer, Integer>> useSensitizerAtInstruction) {
        return new FlowSensitiveStringVariableFactory(defSensitizerAtInstruction, useSensitizerAtInstruction);
    }

    /* Constructors */

    private FlowSensitiveStringVariableFactory(Map<SSAInstruction, Map<Integer, Integer>> defSensitizerAtInstruction,
                                               Map<SSAInstruction, Map<Integer, Integer>> useSensitizerAtInstruction) {
        this.defSensitizerAtInstruction = defSensitizerAtInstruction;
        this.useSensitizerAtInstruction = useSensitizerAtInstruction;
        localsCache = new HashMap<>();
        staticFieldCache = new HashMap<>();
    }

    /* Logic */

    public StringVariable getOrCreateLocalDef(SSAInstruction i, int defNum, IMethod method, TypeRepository types, PrettyPrinter pp) {
        return getOrCreateLocal(this.defSensitizerAtInstruction.get(i), defNum, method, types, pp);
    }

    public StringVariable getOrCreateLocalUse(SSAInstruction i, int useNum, IMethod method, TypeRepository types,
                                              PrettyPrinter pp) {
        return getOrCreateLocal(this.useSensitizerAtInstruction.get(i), useNum, method, types, pp);
    }

    private StringVariable getOrCreateLocal(Map<Integer, Integer> sensitizer, Integer varNum, IMethod m,
                                            TypeRepository types, @SuppressWarnings("unused") PrettyPrinter pp) {
        return getOrCreateLocalWithSubscript(varNum, getOrDefaultSensitizer(sensitizer, varNum), m, types);
    }

    public StringVariable getOrCreateLocalWithSubscript(int varNum, int sensitizingSubscript, IMethod m,
                                                        TypeRepository types) {
        StringVariable maybeValue = localsCache.get(new Triplet<>(m, varNum, sensitizingSubscript));
        if (maybeValue == null) {
            if (types.getType(varNum).equals(TypeReference.JavaLangString)) {
                return StringVariableFactory.makeLocalString(m, varNum, sensitizingSubscript);
            }
            else if (types.getType(varNum).equals(TypeReference.JavaLangStringBuilder)) {
                return StringVariableFactory.makeLocalStringBuilder(m, varNum, sensitizingSubscript);
            }
            else {
                throw new RuntimeException("String variables may only be created for objects of class String or StringBuilder");
            }
        }
        else {
            return maybeValue;
        }
    }

    public StringVariable getOrCreateStaticField(FieldReference field) {
        IField ifield = AnalysisUtil.getClassHierarchy().resolveField(field);
        assert !ifield.getFieldTypeReference().isPrimitiveType() : "Trying to create reference variable for a static field with a primitive type.";
        // return staticFieldCache.computeIfAbsent(ifield, k -> StringVariableFactory.makeField(ifield));
        StringVariable maybeValue = staticFieldCache.get(ifield);
        if (maybeValue == null) {
            return StringVariableFactory.makeField(ifield);
        }
        else {
            return maybeValue;
        }

    }

    public StringVariable getOrCreateParamDef(int varNum, IMethod m, TypeRepository types,
                                              @SuppressWarnings("unused") PrettyPrinter pp) {
        return getOrCreateLocalWithSubscript(varNum, initialSubscript, m, types);
    }

    private static Integer getOrDefaultSensitizer(Map<Integer, Integer> m, Integer a) {
        // return m.getOrDefault(a, initialSubscript);
        Integer maybeValue = m.get(a);
        if (maybeValue == null) {
            return initialSubscript;
        }
        else {
            return maybeValue;
        }
    }

    @SuppressWarnings("static-method")
    public boolean isStringType(TypeReference resultType) {
        return StringAndReflectiveUtil.isStringType(resultType);
    }

    @SuppressWarnings("static-method")
    public boolean isStringLikeMethodInvocation(SSAInvokeInstruction i) {
        return StringAndReflectiveUtil.isStringMethod(i.getDeclaredTarget());
    }
}
