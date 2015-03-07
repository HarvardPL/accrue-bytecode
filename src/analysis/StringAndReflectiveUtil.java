package analysis;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.strings.Atom;

public class StringAndReflectiveUtil {
    private static final IClass JavaLangStringIClass = AnalysisUtil.getStringClass();
    private static final IClass JavaLangStringBuilderIClass = typeReferenceToIClass(TypeReference.findOrCreate(ClassLoaderReference.Application,
                                                                                                               TypeName.string2TypeName("Ljava/lang/StringBuilder")));

    public final static TypeReference JavaLangStringTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Application,
                                                                                               TypeName.string2TypeName("Ljava/lang/String"));
    public final static Atom concatAtom = Atom.findOrCreateUnicodeAtom("concat");
    public final static Descriptor concatDesc = Descriptor.findOrCreateUTF8(Language.JAVA,
                                                                            "(Ljava/lang/String;)Ljava/lang/String;");
    public final static MethodReference JavaLangStringConcat = MethodReference.findOrCreate(JavaLangStringTypeReference,
                                                                                            concatAtom,
                                                                                            concatDesc);
    public final static IMethod JavaLangStringConcatIMethod = methodReferenceToIMethod(JavaLangStringConcat);

    private static final TypeReference JavaLangStringBuilderTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Application,
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

    private static IMethod methodReferenceToIMethod(MethodReference m) {
        return AnalysisUtil.getClassHierarchy().resolveMethod(m);
    }

    private static IClass typeReferenceToIClass(TypeReference tr) {
        return AnalysisUtil.getClassHierarchy().lookupClass(tr);
    }

    public static boolean isStringType(TypeReference resultType) {
        IClass iclass = typeReferenceToIClass(resultType);
        return iclass.equals(JavaLangStringIClass) || iclass.equals(JavaLangStringBuilderIClass);
    }

    public static boolean isStringMethod(MethodReference m) {
        System.err.println("[isStringMethod] Comparing " + m + " to " + stringBuilderAppendStringMethod);
        return m.equals(stringBuilderAppendStringBuilderMethod) || m.equals(stringBuilderAppendStringMethod)
                || m.equals(stringBuilderAppendObjectMethod);
    }
}
