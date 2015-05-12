package analysis.pointer.registrar;

import java.util.HashMap;
import java.util.Map;

import types.TypeRepository;
import analysis.AnalysisUtil;
import analysis.StringAndReflectiveUtil;
import analysis.dataflow.flowsensitizer.StringBuilderLocation;
import analysis.pointer.registrar.strings.StringBuilderVariableFactory;
import analysis.pointer.registrar.strings.StringLikeVariable;
import analysis.pointer.registrar.strings.StringVariableFactory;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.FieldReference;

/**
 * A FlowSensitiveStringVariableFactory is responsible for creating StringVariables for a particular IR. There is one
 * FlowSensitiveStringVariableFactory per IR. The FlowSensitiveStringVariableFactory keeps track of which StringVariable
 * is used to represent the current abstract string value of StringBuilders at various program points.
 */
public final class FlowSensitiveStringLikeVariableFactory {

    private final IMethod method;
    private final TypeRepository typeRepo;
    private final Map<SSAInstruction, Map<Integer, StringBuilderLocation>> useStringBuilderAtInstruction;
    private final Map<SSAInstruction, Map<Integer, StringBuilderLocation>> defStringBuilderAtInstruction;
    private final Map<IField, StringLikeVariable> staticFieldCache;

    /* Factory Methods */

    public static FlowSensitiveStringLikeVariableFactory make(IMethod method,
                                                              TypeRepository types,
                                                              Map<SSAInstruction, Map<Integer, StringBuilderLocation>> map,
                                                              Map<SSAInstruction, Map<Integer, StringBuilderLocation>> map2) {
        return new FlowSensitiveStringLikeVariableFactory(method, types, map, map2);
    }

    /* Constructors */

    private FlowSensitiveStringLikeVariableFactory(IMethod method,
                                                   TypeRepository typeRepo,
                                                   Map<SSAInstruction, Map<Integer, StringBuilderLocation>> map,
                                                   Map<SSAInstruction, Map<Integer, StringBuilderLocation>> map2) {
        this.method = method;
        this.typeRepo = typeRepo;
        this.defStringBuilderAtInstruction = map;
        this.useStringBuilderAtInstruction = map2;
        this.staticFieldCache = new HashMap<>();
    }

    /* Logic */

    public StringLikeVariable getOrCreateLocalDef(SSAInstruction i, int defNum) {
        return getOrCreateLocal(this.defStringBuilderAtInstruction.get(i), defNum);
    }

    public StringLikeVariable getOrCreateLocalUse(SSAInstruction i, int useNum) {
        return getOrCreateLocal(this.useStringBuilderAtInstruction.get(i), useNum);
    }

    private StringLikeVariable getOrCreateLocal(Map<Integer, StringBuilderLocation> map, Integer varNum) {
        StringLikeVariable maybeValue = null; /* localsCache.get(new OrderedPair<>(varNum, s)); */
        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(typeRepo.getType(varNum));
        if (maybeValue == null) {
            if (klass == null) {
                return StringBuilderVariableFactory.makeLocalNull(method, varNum);
            }
            else if (klass.equals(StringAndReflectiveUtil.JavaLangStringIClass)) {
                return StringVariableFactory.makeLocalString(method, varNum);
            }
            else if (klass.equals(StringAndReflectiveUtil.JavaLangStringBuilderIClass)) {
                return StringBuilderVariableFactory.makeLocalStringBuilder(method, varNum, map.get(varNum));
            }
            else {
                throw new RuntimeException("String variables may only be created for objects "
                        + "of class StringBuilder or String, given: " + klass + " compared to: "
                        + StringAndReflectiveUtil.JavaLangStringIClass + " and "
                        + StringAndReflectiveUtil.JavaLangStringBuilderIClass);
            }
        }
        else {
            return maybeValue;
        }
    }

    public StringLikeVariable getOrCreateStaticField(FieldReference field) {
        IField ifield = AnalysisUtil.getClassHierarchy().resolveField(field);
        assert !ifield.getFieldTypeReference().isPrimitiveType() : "Trying to create reference variable for a static field with a primitive type.";
        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(ifield.getFieldTypeReference());
        StringLikeVariable maybeValue = staticFieldCache.get(ifield);
        if (maybeValue == null) {
            if (klass == null) {
                throw new RuntimeException("Unreachable?");
            }
            else if (klass.equals(StringAndReflectiveUtil.JavaLangStringIClass)) {
                return StringVariableFactory.makeField(ifield);
            }
            else if (klass.equals(StringAndReflectiveUtil.JavaLangStringBuilderIClass)) {
                return StringBuilderVariableFactory.makeField(ifield);
            }
            else {
                throw new RuntimeException("String variables may only be created for objects "
                        + "of class StringBuilder or String, given: " + klass + " compared to: "
                        + StringAndReflectiveUtil.JavaLangStringIClass + " and "
                        + StringAndReflectiveUtil.JavaLangStringBuilderIClass);
            }
        }
        else {
            return maybeValue;
        }

    }
}
