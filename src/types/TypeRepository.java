package types;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;

import com.ibm.wala.analysis.typeInference.TypeAbstraction;
import com.ibm.wala.analysis.typeInference.TypeInference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.impl.FakeRootMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Computes and stores type information for variables and thrown exceptions in a given method
 */
public class TypeRepository {

    /**
     * Memoize results of isAssignable
     */
    private static final Map<OrderedPair<IClass, IClass>, Boolean> isAssignable = AnalysisUtil
                                    .createConcurrentHashMap();
    /**
     * Results of WALA type inference
     */
    private TypeInference ti;
    /**
     * Value numbers for exceptions
     */
    private Set<Integer> exceptionValNums;
    /**
     * Types of exceptions thrown by the method the IR is for
     */
    private Set<TypeReference> throwTypes;

    /**
     * Create a new type repository for the given IR
     *
     * @param ir
     *            ir to get types for
     */
    public TypeRepository(IR ir) {
        this.ti = TypeInference.make(ir, true);
    }

    /**
     * Get the type for the variable with the given value number
     *
     * @param valNum
     *            value number for the variable
     * @return the type of the variable with the given value number
     */
    public TypeReference getType(int valNum) {
        assert valNum >= 0 : "Negative valNum: " + valNum + " in " + PrettyPrinter.methodString(ti.getIR().getMethod());
        if (ti.getIR().getSymbolTable().isNullConstant(valNum)) {
            return TypeReference.Null;
        }
        TypeReference tr = ti.getType(valNum).getTypeReference();
        if (tr == null) {
            if (ti.getType(valNum) == TypeAbstraction.TOP && getExceptions().contains(valNum)) {
                // This is an unknown exception/error type
                return TypeReference.JavaLangThrowable;
            }
            PrettyPrinter pp = new PrettyPrinter(ti.getIR());
            throw new RuntimeException("No type for " + pp.valString(valNum) + " in "
                                            + PrettyPrinter.methodString(ti.getIR().getMethod())
                                            + " Could be an element of an array that was set to Object at some point.");
        }
        return tr;
    }

    /**
     * Get value numbers for exceptions thrown by other methods called in the IR. These sometimes to not have exact
     * types after type inference, but we know they are at least Throwable.
     *
     * @param ir
     *            code for the method we are getting exceptions for
     * @return value numbers for the exceptions thrown by methods called by the method the IR is for
     */
    private Set<Integer> getExceptions() {
        if (exceptionValNums == null) {
            exceptionValNums = new LinkedHashSet<>();
            for (ISSABasicBlock bb : ti.getIR().getControlFlowGraph()) {
                for (SSAInstruction ins : bb) {
                    if (ins instanceof SSAInvokeInstruction) {
                        exceptionValNums.add(((SSAInvokeInstruction) ins).getException());
                    }
                }
            }
        }
        return exceptionValNums;
    }

    /**
     * Get the types of exceptions that the method can thrown
     *
     * @return set of exception types the method could throw
     */
    public Set<TypeReference> getThrowTypes() {
        if (throwTypes == null) {
            TypeReference[] exTypes = null;
            throwTypes = new LinkedHashSet<>();
            try {
                exTypes = ti.getIR().getMethod().getDeclaredExceptions();
            } catch (UnsupportedOperationException | InvalidClassFileException e) {
                throw new RuntimeException("Exception when finding exception types for "
                                                + PrettyPrinter.methodString(ti.getIR().getMethod()), e);
            }
            if (exTypes != null) {
                throwTypes.addAll(Arrays.asList(exTypes));
            }

            // All methods can throw RuntimException or Error
            throwTypes.add(TypeReference.JavaLangRuntimeException);
            if (ti.getIR().getMethod() instanceof FakeRootMethod) {
                // The top level doesn't declare anything, but can throw
                // anything main can
                // We'll be conservative and assume it can throw any Throwable

                // XXX this exception could point to a lot of stuff, but should never be queried
                throwTypes.add(TypeReference.JavaLangThrowable);
            }
        }
        return throwTypes;
    }

    /**
     * Compute and print the types for local variables in the given method
     * <p>
     * This is expensive and should be used for debugging only
     *
     * @param m
     *            method to get the types
     */
    public static void print(IMethod m) {
        TypeInference ti = TypeInference.make(AnalysisUtil.getIR(m), true);
        System.err.println("Types for " + PrettyPrinter.methodString(m));
        System.err.println(ti);
    }

    /**
     * Compute and print the types for local variables in the given method
     * <p>
     * This is expensive and should be used for debugging only
     *
     * @param m
     *            method to get the types
     */
    public static void printToFile(IMethod m) {
        TypeInference ti = TypeInference.make(AnalysisUtil.getIR(m), true);

        String dir = AnalysisUtil.getOutputDirectory();
        String fullFilename = dir + "/types_" + PrettyPrinter.methodString(m) + ".txt";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            out.write(ti.toString());
            System.err.println("TYPES written to: " + fullFilename);
        } catch (IOException e) {
            System.err.println("Could not write TYPES to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    /**
     * True if we can assign from c2 to c1. i.e. c1 = c2 type checks i.e. c2 is a subtype of c1?
     * <p>
     * The results are memoized, so only call this if this check will be performed many times with the same classes,
     * otherwise use {@link IClassHierarchy#isAssignableFrom(IClass, IClass)} directly. This is useful for checking
     * exception sub-typing as the same types show up over and over.
     *
     * @param supertype
     *            assignee
     * @param subtype
     *            assigned
     * @return true if c1 = c2 type checks
     */
    public static boolean isAssignableFrom(IClass supertype, IClass subtype) {
        assert supertype != null;
        assert subtype != null;
        // shortcut a common case.
        if (supertype.equals(subtype)) {
            return true;
        }
        OrderedPair<IClass, IClass> key = new OrderedPair<>(supertype, subtype);
        // count++;
        // if (count % 10000000 == 0) {
        // System.err.println("SIZE: " + isAssignable.size() + " COUNT: " + count);
        // }
        Boolean res = isAssignable.get(key);
        if (res == null) {
            res = AnalysisUtil.getClassHierarchy().isAssignableFrom(supertype, subtype);
            isAssignable.put(key, res);
        }
        return res;
    }
}
