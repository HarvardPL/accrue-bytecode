package analysis.pointer.registrar;

import java.util.HashMap;
import java.util.Map;

import types.TypeRepository;
import util.OrderedPair;
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

/**
 * A FlowSensitiveStringVariableFactory is responsible for creating StringVariables for a particular IR. There is one
 * FlowSensitiveStringVariableFactory per IR. The FlowSensitiveStringVariableFactory keeps track of which StringVariable
 * is used to represent the current abstract string value of StringBuilders at various program points.
 */
public final class FlowSensitiveStringVariableFactory {

    /* This class expects the `initialSubscript` to be equal to the subscript
     * corresponding to the sensitized variable valid at the beginning of a method,
     * i.e., before any instructions are ready */
    private static final int initialSubscript = 0;

    private final IMethod method;
    private final TypeRepository typeRepo;
    private final Map<SSAInstruction, Map<Integer, Integer>> useSensitizerAtInstruction;
    private final Map<SSAInstruction, Map<Integer, Integer>> defSensitizerAtInstruction;
    private final Map<OrderedPair<Integer, Integer>, StringVariable> localsCache;
    private final Map<IField, StringVariable> staticFieldCache;

    /* Factory Methods */

    public static FlowSensitiveStringVariableFactory make(IMethod method, TypeRepository types, Map<SSAInstruction, Map<Integer, Integer>> defSensitizerAtInstruction,
                                                          Map<SSAInstruction, Map<Integer, Integer>> useSensitizerAtInstruction) {
        return new FlowSensitiveStringVariableFactory(method, types, defSensitizerAtInstruction, useSensitizerAtInstruction);
    }

    /* Constructors */

    private FlowSensitiveStringVariableFactory(IMethod method, TypeRepository typeRepo, Map<SSAInstruction, Map<Integer, Integer>> defSensitizerAtInstruction,
                                               Map<SSAInstruction, Map<Integer, Integer>> useSensitizerAtInstruction) {
        this.method = method;
        this.typeRepo = typeRepo;
        this.defSensitizerAtInstruction = defSensitizerAtInstruction;
        this.useSensitizerAtInstruction = useSensitizerAtInstruction;
        this.localsCache = new HashMap<>();
        this.staticFieldCache = new HashMap<>();
    }

    /* Logic */

    public StringVariable getOrCreateLocalDef(SSAInstruction i, int defNum, PrettyPrinter pp) {
        return getOrCreateLocal(this.defSensitizerAtInstruction.get(i), defNum, pp);
    }

    public StringVariable getOrCreateLocalUse(SSAInstruction i, int useNum, PrettyPrinter pp) {
        return getOrCreateLocal(this.useSensitizerAtInstruction.get(i), useNum, pp);
    }

    private StringVariable getOrCreateLocal(Map<Integer, Integer> sensitizer, Integer varNum,
                                            @SuppressWarnings("unused") PrettyPrinter pp) {
        return getOrCreateLocalWithSubscript(varNum, getOrDefaultSensitizer(sensitizer, varNum));
    }

    public StringVariable getOrCreateLocalWithSubscript(int varNum, int sensitizingSubscript) {
        StringVariable maybeValue = localsCache.get(new OrderedPair<>(varNum, sensitizingSubscript));
        if (maybeValue == null) {
            if (typeRepo.getType(varNum).equals(TypeReference.JavaLangString)) {
                return StringVariableFactory.makeLocalString(method, varNum, sensitizingSubscript);
            }
            else if (typeRepo.getType(varNum).equals(TypeReference.JavaLangStringBuilder)) {
                return StringVariableFactory.makeLocalStringBuilder(method, varNum, sensitizingSubscript);
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

    public StringVariable getOrCreateParamDef(int varNum) {
        return getOrCreateLocalWithSubscript(varNum, initialSubscript);
    }

    @SuppressWarnings("static-method")
    public StringVariable getOrCreateMethodReturn(IMethod method) {
        if (method.getReturnType().equals(TypeReference.JavaLangString)) {
            return StringVariableFactory.makeMethodReturnString(method);
        } else if (method.getReturnType().equals(TypeReference.JavaLangStringBuilder)) {
            return StringVariableFactory.makeMethodReturnStringBuilder(method);
        } else {
            throw new RuntimeException("FlowSensitiveStringVariableFactory can only create "
                    + "StringVariables for the return values of methods that have return "
                    + "type equal to String or to " + "StringBuilder. Given method, " + method
                    + ", has return type: " + method.getReturnType() + ".");
        }
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
