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

    private final static TypeReference JavaLangStringTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Application,
                                                                                                TypeName.string2TypeName("Ljava/lang/String"));
    private final static Atom concatAtom = Atom.findOrCreateUnicodeAtom("concat");
    private final static Descriptor concatDesc = Descriptor.findOrCreateUTF8(Language.JAVA,
                                                                             "(Ljava/lang/String;)Ljava/lang/String;");
    private final static MethodReference JavaLangStringConcat = MethodReference.findOrCreate(JavaLangStringTypeReference,
                                                                                             concatAtom,
                                                                                             concatDesc);
    private final static IMethod JavaLangStringConcatIMethod = methodReferenceToIMethod(JavaLangStringConcat);

    public static final TypeReference JavaLangStringBuilderTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Application,
                                                                                                      TypeName.string2TypeName("Ljava/lang/StringBuilder"));
    private static final TypeReference JavaLangAbstractStringBuilderTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Application,
                                                                                                               TypeName.string2TypeName("Ljava/lang/AbstractStringBuilder"));
    private final static Atom appendAtom = Atom.findOrCreateUnicodeAtom("append");
    private final static Descriptor appendStringBuilderDesc = Descriptor.findOrCreateUTF8(Language.JAVA,
                                                                                          "(Ljava/lang/StringBuilder;)Ljava/lang/StringBuilder;");
    private final static Descriptor appendStringDesc = Descriptor.findOrCreateUTF8(Language.JAVA,
                                                                                   "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
    private final static Descriptor appendObjectDesc = Descriptor.findOrCreateUTF8(Language.JAVA,
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
    private static final MethodReference stringBuilderInitMethod = MethodReference.findOrCreate(JavaLangStringBuilderTypeReference,
                                                                                                MethodReference.initSelector);
    private static final MethodReference abstractStringBuilderInitMethod = MethodReference.findOrCreate(JavaLangAbstractStringBuilderTypeReference,
                                                                                                        MethodReference.initSelector);
    public static final IMethod stringBuilderAppendStringBuilderIMethod = methodReferenceToIMethod(stringBuilderAppendStringBuilderMethod);
    public static final IMethod stringBuilderAppendStringIMethod = methodReferenceToIMethod(stringBuilderAppendStringMethod);
    public static final IMethod stringBuilderAppendObjectIMethod = methodReferenceToIMethod(stringBuilderAppendObjectMethod);
    public static final IMethod stringBuilderInitIMethod = methodReferenceToIMethod(stringBuilderInitMethod);
    public static final IMethod abstractStringBuilderInitIMethod = methodReferenceToIMethod(abstractStringBuilderInitMethod);

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
        IMethod im = AnalysisUtil.getClassHierarchy().resolveMethod(m);
        return im.equals(stringBuilderAppendStringBuilderIMethod) || im.equals(stringBuilderAppendStringIMethod)
                || im.equals(stringBuilderAppendObjectIMethod);
    }

    public static boolean isStringInitMethod(MethodReference m) {
        IMethod im = AnalysisUtil.getClassHierarchy().resolveMethod(m);
        return im.equals(stringBuilderInitIMethod) || im.equals(abstractStringBuilderInitIMethod);
    }
}
