package analysis.pointer.statements;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import util.optional.Optional;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.ClassInstanceKey;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.strings.Atom;

public class ClassMethodInvocationStatement extends
        AbstractObjectInvocationStatement<ClassMethodInvocationStatement.ReflectiveMethod> {

    public final static TypeReference JavaLangClassTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Primordial,
                                                                                              TypeName.string2TypeName("Ljava/lang/Class"));
    static final IClass JavaLangClassIClass = AnalysisUtil.getClassHierarchy().lookupClass(TypeReference.JavaLangClass);
    public final static MethodReference JavaLangClassNewInstance = MethodReference.JavaLangClassNewInstance;
    public final static MethodReference JavaLangClassForName = MethodReference.JavaLangClassForName;

    public final static Atom getClassAtom = Atom.findOrCreateUnicodeAtom("getClass");
    public final static Descriptor getClassDesc = Descriptor.findOrCreateUTF8(Language.JAVA, "()Ljava/lang/Class;");
    public final static MethodReference JavaLangClassGetClass = MethodReference.findOrCreate(JavaLangClassTypeReference,
                                                                                             getClassAtom,
                                                                                             getClassDesc);

    public final static IMethod JavaLangClassNewInstanceIMethod = AnalysisUtil.getClassHierarchy()
                                                                              .resolveMethod(JavaLangClassNewInstance);
    public final static IMethod JavaLangClassForNameIMethod = AnalysisUtil.getClassHierarchy()
                                                                          .resolveMethod(JavaLangClassForName);
    public final static IMethod JavaLangClassGetClassIMethod = AnalysisUtil.getClassHierarchy()
                                                                           .resolveMethod(JavaLangClassGetClass);

    private final IMethod caller;

    // XXX: I really need to figure out why these are spreading throughout my codebase
    private static final int MAX_STRING_SET_SIZE = 5;
    private static final int MAX_CLASS_SET_SIZE = 5;

    protected enum ReflectiveMethod {
        /* Class methods */
        asSubclassRM, castRM, desiredAssertionStatusRM, getAnnotationRM, getAnnotationsRM, getCanonicalNameRM,
        getClassesRM, getClassLoaderRM, getComponentTypeRM, getConstructorRM, getConstructorsRM,
        getDeclaredAnnotationsRM, getDeclaredClassesRM, getDeclaredConstructorRM, getDeclaredConstructorsRM,
        getDeclaredFieldRM, getDeclaredFieldsRM, getDeclaredMethodRM, getDeclaredMethodsRM, getDeclaringClassRM,
        getEnclosingClassRM, getEnclosingConstructorRM, getEnclosingMethodRM, getEnumConstantsRM, getFieldRM,
        getFieldsRM, getGenericInterfacesRM, getGenericSuperclassRM, getInterfacesRM, getMethodRM, getMethodsRM,
        getModifiersRM, getNameRM, getPackageRM, getProtectionDomainRM, getResourceRM, getResourceAsStreamRM,
        getSignersRM, getSimpleNameRM, getSuperclassRM, getTypeParametersRM, isAnnotationRM, isAnnotationPresentRM,
        isAnonymousClassRM, isArrayRM, isAssignableFromRM, isEnumRM, isInstanceRM, isInterfaceRM, isLocalClassRM,
        isMemberClassRM, isPrimitiveRM, isSyntheticRM, newInstanceRM

        ,

        /* Object methods */
        getClassRM
    }

    private static ReflectiveMethod toReflectiveMethod(IMethod im) {
        if (im.equals(JavaLangClassNewInstanceIMethod)) {
            return ReflectiveMethod.newInstanceRM;
        }
        else if (im.equals(JavaLangClassGetClassIMethod)) {
            return ReflectiveMethod.getClassRM;
        }
        throw new RuntimeException("Unknown Class method: " + im);
    }

    public static boolean isReflectiveMethod(MethodReference m) {
        IMethod im = AnalysisUtil.getClassHierarchy().resolveMethod(m);
        return im.equals(JavaLangClassNewInstanceIMethod) || im.equals(JavaLangClassForNameIMethod)
                || im.equals(JavaLangClassGetClassIMethod);
    }

    public ClassMethodInvocationStatement(CallSiteReference callSite, IMethod caller, ReferenceVariable result,
                                          ReferenceVariable receiver, List<ReferenceVariable> actuals,
                                          ReferenceVariable exception) {
        super(toReflectiveMethod(AnalysisUtil.getClassHierarchy().resolveMethod(callSite.getDeclaredTarget())),
              callSite,
              caller,
              result,
              receiver,
              actuals,
              exception);
        this.caller = caller;
    }

    @Override
    public GraphDelta processMethod(Context context, List<Iterable<InstanceKey>> actualsIKs,
                                    Iterable<InstanceKey> receiverIKs, Iterable<InstanceKey> resultIKs,
                                    ReferenceVariableReplica resultReplica, Iterable<InstanceKey> exceptionIKs,
                                    PointsToGraph g, GraphDelta changed, HeapAbstractionFactory haf) {
        switch (this.method) {
        case asSubclassRM:
            break;
        case castRM:
            break;
        case desiredAssertionStatusRM:
            break;
        case getAnnotationRM:
            break;
        case getAnnotationsRM:
            break;
        case getCanonicalNameRM:
            break;
        case getClassLoaderRM:
            break;
        case getClassRM: {
            assert actuals.size() == 1;
            Set<IClass> classes = new HashSet<>();
            for (InstanceKey receiverIK : receiverIKs) {
                classes.add(receiverIK.getConcreteType());
            }
            AllocSiteNode asn = AllocSiteNodeFactory.createGenerated("getClass",
                                                                     JavaLangClassIClass,
                                                                     caller,
                                                                     result,
                                                                     false);
            System.err.println("[ClassMethodInvocationStatement.getClassRM] classes: " + classes);
            changed.combine(g.addEdge(resultReplica, ClassInstanceKey.makeSet(MAX_CLASS_SET_SIZE, classes)));
            return changed;
        }
        case getClassesRM:
            break;
        case getComponentTypeRM:
            break;
        case getConstructorRM:
            break;
        case getConstructorsRM:
            break;
        case getDeclaredAnnotationsRM:
            break;
        case getDeclaredClassesRM:
            break;
        case getDeclaredConstructorRM:
            break;
        case getDeclaredConstructorsRM:
            break;
        case getDeclaredFieldRM:
            break;
        case getDeclaredFieldsRM:
            break;
        case getDeclaredMethodRM:
            break;
        case getDeclaredMethodsRM:
            break;
        case getDeclaringClassRM:
            break;
        case getEnclosingClassRM:
            break;
        case getEnclosingConstructorRM:
            break;
        case getEnclosingMethodRM:
            break;
        case getEnumConstantsRM:
            break;
        case getFieldRM:
            break;
        case getFieldsRM:
            break;
        case getGenericInterfacesRM:
            break;
        case getGenericSuperclassRM:
            break;
        case getInterfacesRM:
            break;
        case getMethodRM:
            break;
        case getMethodsRM:
            break;
        case getModifiersRM:
            break;
        case getNameRM:
            break;
        case getPackageRM:
            break;
        case getProtectionDomainRM:
            break;
        case getResourceAsStreamRM:
            break;
        case getResourceRM:
            break;
        case getSignersRM:
            break;
        case getSimpleNameRM:
            break;
        case getSuperclassRM:
            break;
        case getTypeParametersRM:
            break;
        case isAnnotationPresentRM:
            break;
        case isAnnotationRM:
            break;
        case isAnonymousClassRM:
            break;
        case isArrayRM:
            break;
        case isAssignableFromRM:
            break;
        case isEnumRM:
            break;
        case isInstanceRM:
            break;
        case isInterfaceRM:
            break;
        case isLocalClassRM:
            break;
        case isMemberClassRM:
            break;
        case isPrimitiveRM:
            break;
        case isSyntheticRM:
            break;
        case newInstanceRM: {
            assert actuals.size() == 1;
            for (InstanceKey receiverIK : receiverIKs) {
                if (receiverIK instanceof ClassInstanceKey) {
                    Optional<Set<IClass>> classes = ((ClassInstanceKey) receiverIK).getReflectedType().maybeIterable();
                    if (classes.isSome()) {
                        for (IClass klass : classes.get()) {
                            // XXX: This is broken; ASNF will only allow one ASN per result but we might need many because
                            //      there could be many different classes flowing to this point.
                            AllocSiteNode asn = AllocSiteNodeFactory.createGenerated("newInstance",
                                                                                     klass,
                                                                                     caller,
                                                                                     result,
                                                                                     false);
                            InstanceKey newik = haf.record(asn, context);
                            System.err.println("[ClassMethodInvocationStatement.newInstanceRM] class: " + klass);
                            changed.combine(g.addEdge(resultReplica, newik));
                        }
                    }
                }
            }
            return changed;
        }
        }
        throw new RuntimeException("Unhandled reflective method type: " + this.method);
    }

    protected static Optional<IClass> stringToIClass(String string) {
        IClass result = AnalysisUtil.getClassHierarchy()
                                    .lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, "L"
                                            + string.replace(".", "/")));
        if (result == null) {
            System.err.println("Could not find class for: " + string);
            return Optional.none();
        }
        else {
            return Optional.some(result);
        }
    }

    @Override
    public String toString() {
        return "ReflectiveMethodInvocationStatement(" + caller + ")";
    }
}
