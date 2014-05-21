package analysis;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.statements.StatementRegistrationPass;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;

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
     * @param cha
     *            class hierarchy
     * @param i
     *            current instruction
     * @param ir
     *            code for method containing instruction
     * @return clinit methods that might need to be called in the order they need to be called (i.e. element j is a
     *         super class of element j+1)
     */
    public static List<IMethod> getClassInitializers(IClassHierarchy cha, SSAInstruction i) {
        IClass klass = null;

        // T is a class and an instance of T is created.
        if (i instanceof SSANewInstruction) {
            SSANewInstruction ins = (SSANewInstruction) i;
            if (!ins.getConcreteType().isArrayType()) {
                return getClassInitializersForClass(klass, cha);
            }
        }

        // T is a class and a static method declared by T is invoked.
        if (i instanceof SSAInvokeInstruction) {
            SSAInvokeInstruction ins = (SSAInvokeInstruction) i;
            if (ins.isStatic()) {
                IMethod callee = cha.resolveMethod(ins.getDeclaredTarget());
                if (callee == null) {
                    if (StatementRegistrationPass.VERBOSE >= 1) {
                        System.err.println("Trying to get class initializer for " + i + " and could not resolve "
                                                        + PrettyPrinter.methodString(ins.getDeclaredTarget()));
                    }
                    return Collections.emptyList();
                }
                return getClassInitializersForClass(callee.getDeclaringClass(), cha);
            }
        }

        // A static field declared by T is assigned.
        if (i instanceof SSAPutInstruction) {
            SSAPutInstruction ins = (SSAPutInstruction) i;
            if (ins.isStatic()) {
                IField f = cha.resolveField(ins.getDeclaredField());
                if (f == null) {
                    throw new RuntimeException("Trying to get class initializer for "
                                                    + i
                                                    + " and could not resolve "
                                                    + PrettyPrinter.typeString(ins.getDeclaredField()
                                                                                    .getDeclaringClass()) + "."
                                                    + ins.getDeclaredField().getName());
                }
                return getClassInitializersForClass(f.getDeclaringClass(), cha);
            }
        }

        // A static field declared by T is used
        if (i instanceof SSAGetInstruction) {
            SSAGetInstruction ins = (SSAGetInstruction) i;
            if (ins.isStatic()) {
                IField f = cha.resolveField(ins.getDeclaredField());
                if (f == null) {
                    throw new RuntimeException("Trying to add class initializer for "
                                                    + i
                                                    + " and could not resolve "
                                                    + PrettyPrinter.typeString(ins.getDeclaredField()
                                                                                    .getDeclaringClass()) + "."
                                                    + ins.getDeclaredField().getName());
                }
                return getClassInitializersForClass(f.getDeclaringClass(), cha);
            }
        }

        // Invocation of certain reflective methods in class Class and in
        // package java.lang.reflect also causes class or interface initialization.
        // TODO handle class initializers for reflection

        return Collections.emptyList();
    }

    /**
     * Get any classes that have to be initialized when the given class is initialized (i.e. the superclasses and
     * interfaces)
     * 
     * @param clazz
     *            class to be initialized
     * @param cha
     *            class hierarchy
     * @return List of class init methods that need to be called
     */
    public static List<IMethod> getClassInitializersForClass(IClass clazz, IClassHierarchy cha) {
        IClass klass = clazz;
        if (klass != null) {
            LinkedList<IMethod> inits = new LinkedList<>();
            // Need to also call clinit for any super classes
            while (!klass.isInterface() && !cha.isRootClass(klass)) {
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
