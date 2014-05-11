package analysis;

import java.util.LinkedList;
import java.util.List;

import util.print.PrettyPrinter;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
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
     * As defined in JLS 12.4.1, get class initializers that must be called (if
     * they have not already been called) before executing the given
     * instruction.
     * 
     * @param cha
     *            class hierarchy
     * @param i
     *            current instruction
     * @param ir
     *            code for method containing instruction
     * @return clinit methods that might need to be called in the order they
     *         need to be called (i.e. element j is a super class of element
     *         j+1)
     */
    public static List<IMethod> getClassInitializers(IClassHierarchy cha, SSAInstruction i, IR ir) {
        IClass klass = null;

        // T is a class and an instance of T is created.
        if (i instanceof SSANewInstruction) {
            SSANewInstruction ins = (SSANewInstruction) i;
            if (!ins.getConcreteType().isArrayType()) {
                klass = cha.lookupClass(ins.getConcreteType());
            }
        }

        // T is a class and a static method declared by T is invoked.
        if (i instanceof SSAInvokeInstruction) {
            SSAInvokeInstruction ins = (SSAInvokeInstruction) i;
            if (ins.isStatic()) {
                IMethod callee = cha.resolveMethod(ins.getDeclaredTarget());
                if (callee == null) {
                    throw new RuntimeException("Trying to get class initializer for "
                                                    + PrettyPrinter.instructionString(i, ir)
                                                    + " and could not resolve "
                                                    + PrettyPrinter.parseMethod(ins.getDeclaredTarget()));
                }
                klass = callee.getDeclaringClass();
            }
        }

        // A static field declared by T is assigned.
        if (i instanceof SSAPutInstruction) {
            SSAPutInstruction ins = (SSAPutInstruction) i;
            if (ins.isStatic()) {
                IField f = cha.resolveField(ins.getDeclaredField());
                if (f == null) {
                    throw new RuntimeException("Trying to get class initializer for "
                                                    + PrettyPrinter.instructionString(i, ir)
                                                    + " and could not resolve "
                                                    + PrettyPrinter.parseType(ins.getDeclaredField()
                                                                                    .getDeclaringClass()) + "."
                                                    + ins.getDeclaredField().getName());
                }
                klass = f.getDeclaringClass();
            }
        }

        // A static field declared by T is used
        if (i instanceof SSAGetInstruction) {
            SSAGetInstruction ins = (SSAGetInstruction) i;
            if (ins.isStatic()) {
                IField f = cha.resolveField(ins.getDeclaredField());
                if (f == null) {
                    throw new RuntimeException("Trying to add class initializer for "
                                                    + PrettyPrinter.instructionString(i, ir)
                                                    + " and could not resolve "
                                                    + PrettyPrinter.parseType(ins.getDeclaredField()
                                                                                    .getDeclaringClass()) + "."
                                                    + ins.getDeclaredField().getName());
                }
                klass = f.getDeclaringClass();
            }
        }

        // Invocation of certain reflective methods in class Class and in
        // package
        // java.lang.reflect also causes class or interface initialization.
        // TODO handle class initializers for reflection

        LinkedList<IMethod> inits = new LinkedList<>();
        if (klass != null) {
            // Need to also call clinit for any super classes
            while (!klass.isInterface() && !cha.isRootClass(klass)) {
                if (klass.getClassInitializer() != null) {
                    // class has an initializer so add it
                    inits.addFirst(klass.getClassInitializer());
                }
                klass = klass.getSuperclass();
            }
        }
                
        return inits;
    }
}