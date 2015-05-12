package analysis;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.strings.Atom;

public class StringAndReflectiveUtil {
    public static final IClass JavaLangStringIClass = AnalysisUtil.getStringClass();
    public static final IClass JavaLangStringBuilderIClass = typeReferenceToIClass(TypeReference.findOrCreate(ClassLoaderReference.Application,
                                                                                                              TypeName.string2TypeName("Ljava/lang/StringBuilder")));

    public final static TypeReference JavaLangStringTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Application,
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
    private static final MethodReference stringBuilderInit0Method = MethodReference.findOrCreate(JavaLangStringBuilderTypeReference,
                                                                                                 MethodReference.initSelector);
    private static final MethodReference abstractStringBuilderInitMethod = MethodReference.findOrCreate(JavaLangAbstractStringBuilderTypeReference,
                                                                                                        MethodReference.initSelector);
    public static final IMethod stringBuilderAppendStringBuilderIMethod = methodReferenceToIMethod(stringBuilderAppendStringBuilderMethod);
    public static final IMethod stringBuilderAppendStringIMethod = methodReferenceToIMethod(stringBuilderAppendStringMethod);
    public static final IMethod stringBuilderAppendObjectIMethod = methodReferenceToIMethod(stringBuilderAppendObjectMethod);
    public static final IMethod stringBuilderInit0IMethod = methodReferenceToIMethod(stringBuilderInit0Method);
    public static final IMethod stringBuilderInit1IMethod = getIMethod(JavaLangStringBuilderTypeReference,
                                                                       "<init>",
                                                                       "(Ljava/lang/String;)V");
    public static final IMethod abstractStringBuilderInitIMethod = methodReferenceToIMethod(abstractStringBuilderInitMethod);
    public static final IMethod stringBuilderToStringIMethod = getIMethod(JavaLangStringBuilderTypeReference,
                                                                          "toString",
                                                                          "()Ljava/lang/String;");
    private static final IMethod stringInit0IMethod = getIMethod(JavaLangStringTypeReference,
                                                                 MethodReference.initSelector);

    private static final TypeReference JavaLangSystemTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Application,
                                                                                                TypeName.string2TypeName("Ljava/lang/System"));
    private static final IMethod systemGetProperty1IMethod = getIMethod(JavaLangSystemTypeReference,
                                                                        "getProperty",
                                                                        "(Ljava/lang/String;)Ljava/lang/String;");
    private static final IMethod systemGetProperty2IMethod = getIMethod(JavaLangSystemTypeReference,
                                                                        "getProperty",
                                                                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    private static final TypeReference JavaLangSecurityTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Application,
                                                                                                  TypeName.string2TypeName("Ljava/lang/Security"));
    private static final IMethod securityGetPropertyIMethod = getIMethod(JavaLangSecurityTypeReference,
                                                                         "getProperty",
                                                                         "(Ljava/lang/String;)Ljava/lang/String;");
    public static final IMethod stringToStringIMethod = getIMethod(JavaLangStringTypeReference,
                                                                   "toString",
                                                                   "()Ljava/lang/String;");
    private static final Object stringValueOfIMethod = getIMethod(JavaLangStringTypeReference,
                                                                  "valueOf",
                                                                  "(Ljava/lang/Object;)Ljava/lang/String;");

    public static IMethod methodReferenceToIMethod(MethodReference m) {
        return AnalysisUtil.getClassHierarchy().resolveMethod(m);
    }

    private static IClass getIClass(String name) {
        return AnalysisUtil.getClassHierarchy()
                           .lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application,
                                                                   TypeName.string2TypeName(name)));
    }

    private static IMethod getIMethod(TypeReference type, String atom, String desc) {
        return methodReferenceToIMethod(MethodReference.findOrCreate(type,
                                                                     Atom.findOrCreateUnicodeAtom(atom),
                                                                     Descriptor.findOrCreateUTF8(Language.JAVA, desc)));
    }

    private static IMethod getIMethod(TypeReference type, Selector selector) {
        return methodReferenceToIMethod(MethodReference.findOrCreate(type, selector));
    }

    public static IClass typeReferenceToIClass(TypeReference tr) {
        return AnalysisUtil.getClassHierarchy().lookupClass(tr);
    }

    public static boolean isStringLikeType(TypeReference t) {
        if (t == null) {
            return false;
        }
        else {
            IClass iclass = typeReferenceToIClass(t);
            return iclass != null
                    && (iclass.equals(JavaLangStringIClass) || iclass.equals(JavaLangStringBuilderIClass));
        }
    }

    public static boolean isStringMutatingMethod(MethodReference m) {
        IMethod im = AnalysisUtil.getClassHierarchy().resolveMethod(m);
        return im.equals(stringBuilderAppendStringBuilderIMethod) || im.equals(stringBuilderAppendStringIMethod)
                || im.equals(stringBuilderInit0IMethod) || im.equals(stringBuilderInit1IMethod)
                || im.equals(stringInit0IMethod);
    }

    public static boolean isStringMethod(MethodReference m) {
        IMethod im = AnalysisUtil.getClassHierarchy().resolveMethod(m);
        return isStringMutatingMethod(m) || im.equals(stringBuilderToStringIMethod) || im.equals(stringToStringIMethod);
    }

    public static boolean isStringInit0Method(IMethod im) {
        return im.equals(stringInit0IMethod);
    }

    public static boolean isStringBuilderInit0Method(IMethod im) {
        return im.equals(stringBuilderInit0IMethod);
    }

    public static boolean isStringBuilderInit1Method(IMethod im) {
        return im.equals(stringBuilderInit1IMethod);
    }

    public static boolean isValueOf(MethodReference m) {
        IMethod im = AnalysisUtil.getClassHierarchy().resolveMethod(m);
        return im.equals(stringValueOfIMethod);
    }

    public static boolean isGetPropertyCall(MethodReference methodReference) {
        IMethod im = AnalysisUtil.getClassHierarchy().resolveMethod(methodReference);
        return im.equals(systemGetProperty1IMethod) || im.equals(systemGetProperty2IMethod)
                || im.equals(securityGetPropertyIMethod);
    }

    public static boolean isStringBuilderType(TypeReference t) {
        return trEqualsIClass(t, JavaLangStringBuilderIClass);

    }

    public static boolean isStringType(TypeReference t) {
        return trEqualsIClass(t, JavaLangStringIClass);
    }

    public static boolean trEqualsIClass(TypeReference t, IClass ic) {
        if (t == null) {
            return false;
        }
        else {
            IClass iclass = typeReferenceToIClass(t);
            return iclass != null && iclass.equals(ic);
        }
    }

    public static boolean resultAndReceiverAliasMethod(MethodReference m) {
        IMethod im = AnalysisUtil.getClassHierarchy().resolveMethod(m);
        return im.equals(stringBuilderAppendObjectIMethod) || im.equals(stringBuilderAppendStringBuilderIMethod)
                || im.equals(stringBuilderAppendStringIMethod);
    }

    public static boolean isStringBuilderAppend(IMethod im) {
        return im.equals(stringBuilderAppendStringBuilderIMethod) || im.equals(stringBuilderAppendStringIMethod);
    }

}
