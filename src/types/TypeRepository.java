package types;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;

import com.ibm.wala.analysis.typeInference.TypeAbstraction;
import com.ibm.wala.analysis.typeInference.TypeInference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.impl.FakeRootMethod;
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
    private static final Map<IR, TypeInference> types = new LinkedHashMap<>();
    /**
     * Value numbers for exceptions thrown by callees
     */
    private static final Map<IR, Set<Integer>> exceptions = new LinkedHashMap<>();
    /**
     * Types of exceptions thrown
     */
    private static final Map<IMethod, Set<TypeReference>> exceptionTypes = new LinkedHashMap<>();

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
            System.err.println("No type for " + ir.getSymbolTable().getValueString(valNum) + " in "
                    + ir.getMethod().getSignature());
        }
        return tr;
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
            et.add(TypeReference.JavaLangError);
            if (m instanceof FakeRootMethod) {
                // The top level doesn't declare anything, but can throw
                // anything main can
                // We'll be conservative and assume it can throw any Throwable
                et.add(TypeReference.JavaLangThrowable);
            }
            
            exceptionTypes.put(m, et);
        }
        return et;
    }
}
