package analysis;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.engine.PointsToAnalysis;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Find classes that may need to be initialized
 */
public class ClassInitFinder {

    /**
     * Use this class statically
     */
    private ClassInitFinder() {
        // Intentionally left blank
    }

    /**
     * As defined in JLS 12.4.1, get class initializers that must be called (if they have not already been called)
     * before executing the given instruction.
     *
     * @param i current instruction
     * @return clinit methods that might need to be called in the order they need to be called (i.e. element j is a
     *         super class of element j+1)
     */
    public static List<IMethod> getClassInitializers(SSAInstruction i) {
        IClass klass = getRequiredInitializedClass(i);
        if (klass == null) {
            return Collections.emptyList();
        }
        return getClassInitializersForClass(klass);
    }

    /**
     * As defined in JLS 12.4.1, get the class (if any) that must be initialized before executing the given instruction.
     *
     * @param i current instruction
     * @return Class that must be initialized before executing <code>i</code>
     */
    public static IClass getRequiredInitializedClass(SSAInstruction i) {
        // T is a class and an instance of T is created.
        if (i instanceof SSANewInstruction) {
            SSANewInstruction ins = (SSANewInstruction) i;
            if (!ins.getConcreteType().isArrayType()) {
                TypeReference rf = ins.getConcreteType();
                IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(rf);
                assert klass != null;
                return klass;
            }
        }

        // T is a class and a static method declared by T is invoked.
        if (i instanceof SSAInvokeInstruction) {
            SSAInvokeInstruction ins = (SSAInvokeInstruction) i;
            if (ins.isStatic()) {
                IMethod callee = AnalysisUtil.getClassHierarchy().resolveMethod(ins.getDeclaredTarget());
                if (callee == null) {
                    if (PointsToAnalysis.outputLevel >= 2) {
                        System.err.println("Trying to get class initializer for " + i + " and could not resolve "
                                                        + PrettyPrinter.methodString(ins.getDeclaredTarget()));
                    }
                    return null;
                }
                return callee.getDeclaringClass();
            }
        }

        // A static field declared by T is assigned.
        if (i instanceof SSAPutInstruction) {
            SSAPutInstruction ins = (SSAPutInstruction) i;
            if (ins.isStatic()) {
                IField f = AnalysisUtil.getClassHierarchy().resolveField(ins.getDeclaredField());
                if (f == null) {
                    throw new RuntimeException("Trying to get class initializer for "
                                                    + i
                                                    + " and could not resolve "
                                                    + PrettyPrinter.typeString(ins.getDeclaredField()
                                                                                    .getDeclaringClass()) + "."
                                                    + ins.getDeclaredField().getName());
                }
                return f.getDeclaringClass();
            }
        }

        // A static field declared by T is used
        if (i instanceof SSAGetInstruction) {
            SSAGetInstruction ins = (SSAGetInstruction) i;
            if (ins.isStatic()) {
                IField f = AnalysisUtil.getClassHierarchy().resolveField(ins.getDeclaredField());
                if (f == null) {
                    throw new RuntimeException("Trying to add class initializer for "
                                                    + i
                                                    + " and could not resolve "
                                                    + PrettyPrinter.typeString(ins.getDeclaredField()
                                                                                    .getDeclaringClass()) + "."
                                                    + ins.getDeclaredField().getName());
                }
                return f.getDeclaringClass();
            }
        }

        if (i instanceof SSALoadMetadataInstruction) {
            return AnalysisUtil.getClassHierarchy().lookupClass(TypeReference.JavaLangClass);
        }

        // Invocation of certain reflective methods in class Class and in
        // package java.lang.reflect also causes class or interface initialization.
        // TODO handle class initializers for reflection

        return null;
    }

    /**
     * Get any classes that have to be initialized when the given class is initialized (i.e. the superclasses and
     * interfaces)
     *
     * @param klass class to be initialized
     * @return List of class init methods that need to be called in the order they need to be initialized in
     */
    public static List<IMethod> getClassInitializersForClass(IClass klass) {
        if (klass != null) {
            IClass objectClass = AnalysisUtil.getClassHierarchy().getRootClass();
            LinkedList<IMethod> inits = new LinkedList<>();
            // Need to also add clinit for any super classes

            // Note that object doesn't have any clinit, and interface clinits are not called until a static field is
            // actually accessed
            while (!klass.isInterface() && !(klass == objectClass)) {
                if (klass.getClassInitializer() != null) {
                    // class has an initializer so add it
                    inits.addFirst(klass.getClassInitializer());
                }
                klass = klass.getSuperclass();
            }
            return inits;
        }
        return Collections.emptyList();
    }
}
