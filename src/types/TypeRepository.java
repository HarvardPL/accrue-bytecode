package types;

import java.util.Arrays;
import java.util.HashMap;
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
 * Computes on-demand and stores type information
 */
public class TypeRepository {

    /**
     * All type information
     */
    private static final Map<IR, TypeInference> types = new HashMap<>();
    /**
     * Value numbers for exceptions thrown by callees
     */
    private static final Map<IR, Set<Integer>> exceptions = new HashMap<>();
    /**
     * Types of exceptions thrown
     */
    private static final Map<IMethod, Set<TypeReference>> exceptionTypes = new HashMap<>();
    /**
     * Memoize results of isAssignable
     */
    private static final Map<OrderedPair<IClass, IClass>, Boolean> isAssignable = new HashMap<>();

    /**
     * Construct types for the given IR using WALA's type inference
     * 
     * @param ir
     *            code for the method to get the types for
     * @return {@link TypeInference} object containing the types
     */
    private static TypeInference getTypeInformation(IR ir) {
        TypeInference ti = types.get(ir);
        if (ti == null) {
            ti = TypeInference.make(ir, true);
            types.put(ir, ti);
            // System.err.println("Types for " + ir.getMethod().getSignature());
            // printTypes(ir, ti);
        }
        return ti;
    }

    /**
     * Get the type for a specific variable in the given code
     * 
     * @param valNum
     *            value number for the variable
     * @param ir
     *            code for the method containing the variable
     * @return the type of the variable with the given value number
     */
    public static TypeReference getType(int valNum, IR ir) {
        assert valNum >= 0 : "Negative value number " + valNum + " getting type in " + PrettyPrinter.irString(ir, "\t", "\n");
        if (ir.getSymbolTable().isNullConstant(valNum)) {
            return TypeReference.Null;
        }
        TypeInference ti = getTypeInformation(ir);
        TypeReference tr = ti.getType(valNum).getTypeReference();
        if (tr == null) {
            if (ti.getType(valNum) == TypeAbstraction.TOP && getExceptions(ir).contains(valNum)) {
                // This is an unknown exception/error type
                return TypeReference.JavaLangThrowable;
            }
            throw new RuntimeException("No type for "
                                            + ir.getSymbolTable().getValueString(valNum)
                                            + " in "
                                            + ir.getMethod().getSignature()
                                            + " Probably an element of an array that was set to Object at some point. "
                                            + "Set it to double since anything can cast up to it. I guess.");
        }
        return tr;
    }

    /**
     * True if we can assign from c2 to c1. i.e. c1 = c2 type checks i.e. c2 is a subtype of c1?
     * <p>
     * The results are memoized, so only call this if this check will be performed many times with the same classes,
     * otherwise use {@link IClassHierarchy#isAssignableFrom(IClass, IClass)} directly. This is useful for checking
     * exception sub-typing as the same types show up over and over.
     * 
     * @param c1
     *            assignee
     * @param c2
     *            assigned
     * @return true if c1 = c2 type checks
     */
    public static boolean isAssignableFrom(IClass c1, IClass c2) {
        // shortcut a common case.
        if (c1.equals(c2)) {
            return true;
        }
        OrderedPair<IClass, IClass> key = new OrderedPair<>(c1, c2);
        // count++;
        // if (count % 10000000 == 0) {
        // System.err.println("SIZE: " + isAssignable.size() + " COUNT: " + count);
        // }
        Boolean res = isAssignable.get(key);
        if (res == null) {
            res = AnalysisUtil.getClassHierarchy().isAssignableFrom(c1, c2);
            isAssignable.put(key, res);
        }
        return res;
    }

    /**
     * Get value numbers for exceptions thrown by other methods called in the
     * IR. These sometimes to not have exact types after type inference, but we
     * know they are at least Throwable.
     * 
     * @param ir
     *            code for the method we are getting exceptions for
     * @return value numbers for the exceptions thrown by methods called by the
     *         method the IR is for
     */
    private static Set<Integer> getExceptions(IR ir) {
        Set<Integer> exs = exceptions.get(ir);
        if (exs == null) {
            exs = new LinkedHashSet<>();
            for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
                for (SSAInstruction ins : bb) {
                    if (ins instanceof SSAInvokeInstruction) {
                        exs.add(((SSAInvokeInstruction) ins).getException());
                    }
                }
            }
            exceptions.put(ir, exs);
        }
        return exs;
    }

    /**
     * Get the types of exceptions that the given method can thrown
     * 
     * @param m
     *            method to get the exception types for
     * @return set of exception types m could throw
     */
    public static Set<TypeReference> getThrowTypes(IMethod m) {
        Set<TypeReference> et = exceptionTypes.get(m);
        if (et == null) {
            TypeReference[] exTypes = null;
            et = new LinkedHashSet<>();
            try {
                exTypes = m.getDeclaredExceptions();
            } catch (UnsupportedOperationException | InvalidClassFileException e) {
                throw new RuntimeException("Cannot find exception types for " + m.getSignature(), e);
            }
            if (exTypes != null) {
                et.addAll(Arrays.asList(exTypes));
            }

            // All methods can throw RuntimException or Error
            et.add(TypeReference.JavaLangRuntimeException);
            if (m instanceof FakeRootMethod) {
                // The top level doesn't declare anything, but can throw
                // anything main can
                // We'll be conservative and assume it can throw any Throwable

                // XXX this exception could point to a lot of stuff, but should never be queried
                et.add(TypeReference.JavaLangThrowable);
            }
            
            exceptionTypes.put(m, et);
        }
        return et;
    }

    /**
     * @param code
     */
    public static void printTypes(IR code) {
        System.err.println("Types for " + PrettyPrinter.methodString(code.getMethod()));
        System.err.println(types.get(code));
    }
}
