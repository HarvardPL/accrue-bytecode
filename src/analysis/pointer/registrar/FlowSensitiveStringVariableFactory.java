package analysis.pointer.registrar;

import java.util.HashMap;
import java.util.Map;

import util.Triplet;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.registrar.strings.StringVariable;
import analysis.pointer.registrar.strings.StringVariableFactory;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.strings.Atom;

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

    public StringVariable getOrCreateLocalDef(SSAInstruction i, int defNum, IMethod method, PrettyPrinter pp) {
        return getOrCreateLocal(this.defSensitizerAtInstruction.get(i), defNum, method, pp);
    }

    public StringVariable getOrCreateLocalUse(SSAInstruction i, int useNum, IMethod method, PrettyPrinter pp) {
        return getOrCreateLocal(this.useSensitizerAtInstruction.get(i), useNum, method, pp);
    }

    private StringVariable getOrCreateLocal(Map<Integer, Integer> sensitizer, Integer varNum, IMethod m,
                                            @SuppressWarnings("unused") PrettyPrinter pp) {
        return getOrCreateLocalWithSubscript(varNum, getOrDefaultSensitizer(sensitizer, varNum), m);
    }

    private StringVariable getOrCreateLocalWithSubscript(int varNum, int sensitizingSubscript, IMethod m) {
        StringVariable maybeValue = localsCache.get(new Triplet<>(m, varNum, sensitizingSubscript));
        if (maybeValue == null) {
            return StringVariableFactory.makeLocal(m, varNum, sensitizingSubscript);
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

    public StringVariable getOrCreateParamDef(int varNum, IMethod m, @SuppressWarnings("unused") PrettyPrinter pp) {
        return getOrCreateLocalWithSubscript(varNum, initialSubscript, m);
    }

    private static Integer getOrDefaultSensitizer(Map<Integer, Integer> m, Integer a) {
        System.err.println("Variable " + a + " was unmapped (in " + m + "), returning the default " + initialSubscript
                + ".");
        // return m.getOrDefault(a, initialSubscript);
        Integer maybeValue = m.get(a);
        if (maybeValue == null) {
            return initialSubscript;
        }
        else {
            return maybeValue;
        }
    }

    /* ******************************************************************** */
    /* Static Method and Fields for Finding String-related Methods and Objects */

    private static IClass typeReferenceToIClass(TypeReference tr) {
        return AnalysisUtil.getClassHierarchy().lookupClass(tr);
    }

    private static final IClass JavaLangStringIClass = AnalysisUtil.getStringClass();
    private static final IClass JavaLangStringBuilderIClass = typeReferenceToIClass(TypeReference.findOrCreate(ClassLoaderReference.Application,
                                                                                                               TypeName.string2TypeName("Ljava/lang/StringBuilder")));

    @SuppressWarnings("static-method")
    public boolean isStringType(TypeReference resultType) {
        IClass iclass = typeReferenceToIClass(resultType);
        return iclass.equals(JavaLangStringIClass) || iclass.equals(JavaLangStringBuilderIClass);
    }

    private static IMethod methodReferenceToIMethod(MethodReference m) {
        return AnalysisUtil.getClassHierarchy().resolveMethod(m);
    }

    public final static TypeReference JavaLangStringTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Application,
                                                                                               TypeName.string2TypeName("Ljava/lang/String"));
    public final static Atom concatAtom = Atom.findOrCreateUnicodeAtom("concat");
    public final static Descriptor concatDesc = Descriptor.findOrCreateUTF8(Language.JAVA,
                                                                            "(Ljava/lang/String;)Ljava/lang/String;");
    public final static MethodReference JavaLangStringConcat = MethodReference.findOrCreate(JavaLangStringTypeReference,
                                                                                            concatAtom,
                                                                                            concatDesc);
    public final static IMethod JavaLangStringConcatIMethod = methodReferenceToIMethod(JavaLangStringConcat);

    @SuppressWarnings("static-method")
    public boolean isStringLikeMethodInvocation(SSAInvokeInstruction i) {
        IMethod imethod = methodReferenceToIMethod(i.getDeclaredTarget());
        return imethod.equals(JavaLangStringConcatIMethod);
    }
}
