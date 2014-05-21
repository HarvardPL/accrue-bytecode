package signatures;

import java.util.HashMap;
import java.util.Map;

import signatures.synthetic.SyntheticIR;
import util.InstructionType;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.pointer.registrar.StatementRegistrationPass;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInstructionFactory;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * Class that computes signatures for methods that have hand written Java signatures in the <code>signatures</code>
 * package
 */
public class Signatures {

    /**
     * Class loader to use when making new types to find classes for
     */
    private static final ClassLoaderReference CLASS_LOADER = ClassLoaderReference.Application;
    /**
     * Used to create new instructions to replace certain instructions in the signature (e.g. references to fields on a
     * signature type are changed to references to fields on a "real" type)
     */
    private static final SSAInstructionFactory I_FACTORY = Language.JAVA.instructionFactory();
    /**
     * Memoization map for signature so they do not have to be recomputed
     */
    private static final Map<IMethod, IR> signatures = new HashMap<>();
    /**
     * Flag for printing debug messages
     */
    private static final boolean DEBUG = false;

    /**
     * Get the IR for a method that has a signature
     * 
     * @param actualMethod
     *            method to find the signature for
     * @param util
     *            used to get the IR and to get the class hierarchy
     * @return the signature IR or null if no signature is found
     */
    public static IR getSignatureIR(IMethod actualMethod, WalaAnalysisUtil util) {
        if (signatures.containsKey(actualMethod)) {
            // Already computed the signature return it
            return signatures.get(actualMethod);
        }

        MethodReference actual = actualMethod.getReference();
        if (isSigType(actual.getDeclaringClass())) {
            // requesting signature for a signature type just record and return null
            signatures.put(actualMethod, null);
            return null;
        }

        TypeReference actualTarget = actual.getDeclaringClass();
        // Take the name of the real method target and append signatures/library to make it a signature type
        TypeReference sigTarget = TypeReference.findOrCreate(CLASS_LOADER, "Lsignatures/library/"
                                        + actualTarget.getName().toString().substring(1));
        MethodReference sigMethod = MethodReference.findOrCreate(sigTarget, actual.getName(), actual.getDescriptor());

        try {
            IR sigIR = util.getIR(util.getClassHierarchy().resolveMethod(sigMethod));
            IR newIR = update(sigIR, actualMethod, util.getClassHierarchy());
            if (StatementRegistrationPass.VERBOSE >= 1) {
            System.err.println("SIGNATURE for: " + PrettyPrinter.methodString(actualMethod));
            }
            signatures.put(actualMethod, newIR);
            return newIR;
        } catch (RuntimeException e) {
            // Any exceptions means that no signature was found
            signatures.put(actualMethod, null);
            return null;
        }
    }

    /**
     * If the receiver types for method invocations and field accesses are signature types, replace them with "real"
     * types if they exist. Also swap the method in the IR for the "real" method
     * 
     * @param sigIR
     *            IR for the signature method
     * @param actualMethod
     *            "real" resolved method the IR is the signature for
     * @param cha
     *            class hierarchy
     * @return IR with signature types replaced with the "real" versions when they can be found
     */
    private static IR update(IR sigIR, IMethod actualMethod, IClassHierarchy cha) {
        if (DEBUG) {
            CFGWriter.writeToFile(sigIR, "sig_" + PrettyPrinter.methodString(sigIR.getMethod()));
        }
        // does not include phi instructions, but these do not need to be converted
        @SuppressWarnings("deprecation")
        SSAInstruction[] allInstructions = sigIR.getInstructions();
        SSAInstruction updated;
        for (int j = 0; j < allInstructions.length; j++) {
            SSAInstruction i = allInstructions[j];
            if (i == null) {
                // The instruction has been translated away when converting to SSA
                continue;
            }
            switch (InstructionType.forInstruction(i)) {
            case ARRAY_LENGTH:
            case ARRAY_LOAD:
            case ARRAY_STORE:
            case BINARY_OP:
            case BINARY_OP_EX:
            case COMPARISON:
            case CONDITIONAL_BRANCH:
            case CONVERSION:
            case GET_CAUGHT_EXCEPTION:
            case GOTO:
            case LOAD_METADATA:
            case MONITOR:
            case NEW_ARRAY:
            case PHI:
            case RETURN:
            case SWITCH:
            case THROW:
            case UNARY_NEG_OP:
                // These all refer only to local variables so there is nothing to fix
                continue;
            case CHECK_CAST:
            case INSTANCE_OF:
                // These involve types, but we will assume they are the indended types
                continue;
            case NEW_OBJECT:
                updated = handleNewObject((SSANewInstruction) i, cha);
                allInstructions[j] = updated;
                continue;
            case PUT_FIELD:
            case PUT_STATIC:
                updated = handlePut((SSAPutInstruction) i, cha);
                allInstructions[j] = updated;
                continue;
            case INVOKE_INTERFACE:
            case INVOKE_SPECIAL:
            case INVOKE_STATIC:
            case INVOKE_VIRTUAL:
                updated = handleInvoke((SSAInvokeInstruction) i, cha);
                allInstructions[j] = updated;
                continue;
            case GET_FIELD:
            case GET_STATIC:
                updated = handleGet((SSAGetInstruction) i, cha);
                allInstructions[j] = updated;
                continue;
            }
        }

        IR newIR = new SyntheticIR(actualMethod, allInstructions, sigIR.getSymbolTable(), sigIR.getControlFlowGraph(),
                                        sigIR.getOptions());
        if (DEBUG) {
            CFGWriter.writeToFile(newIR, "sig_rewrite_" + PrettyPrinter.methodString(newIR.getMethod()));
        }
        return newIR;
    }

    /**
     * If the receiver type is a signature type then replace it with the corresponding "real" type if there is one
     * 
     * @param i
     *            instruction to potentially replace
     * @param cha
     *            class hierarchy
     * @return new instruction (or same instruction if no changes were made)
     */
    private static SSAInstruction handleGet(SSAGetInstruction i, IClassHierarchy cha) {
        TypeReference receiverType = i.getDeclaredField().getDeclaringClass();

        if (isSigType(receiverType)) {
            TypeReference real = findRealTypeForSigType(receiverType, cha);
            if (real != null) {
                FieldReference newFR = FieldReference.findOrCreate(real, i.getDeclaredField().getName(),
                                                i.getDeclaredFieldType());
                IField newField = cha.resolveField(newFR);
                if (newField != null) {
                    // The field exists on the real type use it to create a new instruction
                    if (i.isStatic()) {
                        SSAInstruction ii = I_FACTORY.GetInstruction(i.getDef(), newFR);
                        if (StatementRegistrationPass.VERBOSE >= 5) {
                            System.err.println("SIGNATURE REPLACED: " + i + " with " + ii);
                        }
                        return ii;
                    }
                    SSAInstruction ii = I_FACTORY.GetInstruction(i.getDef(), i.getRef(), newFR);
                    if (StatementRegistrationPass.VERBOSE >= 5) {
                        System.err.println("SIGNATURE REPLACED: " + i + " with " + ii);
                    }
                    return ii;
                }
            }
        }

        return i;
    }

    /**
     * If the receiver type is a signature type then replace it with the corresponding "real" type if there is one
     * 
     * @param i
     *            instruction to potentially replace
     * @param cha
     *            class hierarchy
     * @return new instruction (or same instruction if no changes were made)
     */
    private static SSAInstruction handleInvoke(SSAInvokeInstruction i, IClassHierarchy cha) {
        TypeReference receiverType = i.getDeclaredTarget().getDeclaringClass();

        if (isSigType(receiverType)) {
            TypeReference real = findRealTypeForSigType(receiverType, cha);
            if (real != null) {
                MethodReference newMR = MethodReference.findOrCreate(real, i.getDeclaredTarget().getSelector());
                IMethod newMethod = cha.resolveMethod(newMR);
                if (newMethod != null) {
                    // The method exists on the real type use it to create a new instruction
                    CallSiteReference newSite = CallSiteReference.make(i.getProgramCounter(), newMR, i.getCallSite()
                                                    .getInvocationCode());
                    int[] params = new int[i.getNumberOfParameters()];
                    // What if the number of params is 0 i.e. static with no args
                    for (int j = 0; j < i.getNumberOfParameters(); j++) {
                        params[j] = i.getUse(j);
                    }
                    int returnValue = i.getNumberOfReturnValues() > 0 ? i.getDef() : -1;
                    SSAInstruction ii = I_FACTORY.InvokeInstruction(returnValue, params, i.getException(), newSite);
                    if (StatementRegistrationPass.VERBOSE >= 5) {
                        System.err.println("SIGNATURE REPLACED: " + i + " with " + ii);
                    }
                    return ii;
                }
            }
        }

        return i;
    }

    /**
     * If the receiver type is a signature type then replace it with the corresponding "real" type if there is one
     * 
     * @param i
     *            instruction to potentially replace
     * @param cha
     *            class hierarchy
     * @return new instruction (or same instruction if no changes were made)
     */
    private static SSAInstruction handlePut(SSAPutInstruction i, IClassHierarchy cha) {
        TypeReference receiverType = i.getDeclaredField().getDeclaringClass();

        if (isSigType(receiverType)) {
            TypeReference real = findRealTypeForSigType(receiverType, cha);
            if (real != null) {
                FieldReference newFR = FieldReference.findOrCreate(real, i.getDeclaredField().getName(),
                                                i.getDeclaredFieldType());
                IField newField = cha.resolveField(newFR);
                if (newField != null) {
                    // The field exists on the real type use it to create a new put instruction
                    if (i.isStatic()) {
                        SSAInstruction ii = I_FACTORY.PutInstruction(i.getVal(), newFR);
                        if (StatementRegistrationPass.VERBOSE >= 5) {
                            System.err.println("SIGNATURE REPLACED: " + i + " with " + ii);
                        }
                        return ii;
                    }
                    SSAInstruction ii = I_FACTORY.PutInstruction(i.getRef(), i.getVal(), newFR);
                    if (StatementRegistrationPass.VERBOSE >= 5) {
                        System.err.println("SIGNATURE REPLACED: " + i + " with " + ii);
                    }
                    return ii;
                }
            }
        }

        return i;
    }

    /**
     * If the new object is a signature object and the real one exists then change this to the real one
     * 
     * @param i
     *            instruction to potentially replace
     * @param cha
     *            class hierarchy
     * @return new instruction (or same instruction if no changes were made)
     */
    private static SSAInstruction handleNewObject(SSANewInstruction i, IClassHierarchy cha) {
        TypeReference allocatedType = i.getConcreteType();
        if (isSigType(allocatedType)) {
            // This is an allocation of an object in the signatures package
            // Check to see if there is a "real" object out there that the signature object is emulating.
            TypeReference real = findRealTypeForSigType(allocatedType, cha);
            if (real != null) {
                // Found a real object
                NewSiteReference site = i.getNewSite();
                NewSiteReference newSite = new NewSiteReference(site.getProgramCounter(), real);
                SSAInstruction ii = I_FACTORY.NewInstruction(i.getDef(), newSite);
                if (StatementRegistrationPass.VERBOSE >= 5) {
                    System.err.println("SIGNATURE REPLACED: " + i + " with " + ii);
                }
                return ii;
            }
        }
        // This an allocation of a real class or a signature class with no real counterpart
        return i;
    }

    /**
     * Given a signature type, find the corresponding "real" type that exists in the type hierarchy, returns null if no
     * valid type is found
     * 
     * @param signatureType
     *            type of class from signature library
     * @param cha
     *            class hierarchy
     * @return Type for the real class corresponding to the given type from the signature library, null if there is no
     *         corresponding type
     */
    private static TypeReference findRealTypeForSigType(TypeReference signatureType, IClassHierarchy cha) {
        assert isSigType(signatureType) : "Can only find classes for signature types.";
        // This is an allocation of an object in the signatures package
        // Check to see if there is a "real" object out there the signature object is emulating.
        String typeToCheck = signatureType.getName().toString().replace("Lsignatures/library/", "L");
        if (StatementRegistrationPass.VERBOSE >= 5) {
            System.err.println("SIGNATURE LOOKING: " + typeToCheck);
        }
        TypeReference potentialMatch = TypeReference.findOrCreate(CLASS_LOADER, typeToCheck);
        IClass klass = cha.lookupClass(potentialMatch);
        if (klass == null) {
            return null;
        }
        if (StatementRegistrationPass.VERBOSE >= 5) {
            System.err.println("SIGNATURE FOUND: " + klass);
        }
        return klass.getReference();
    }

    /**
     * Check whether a given type is from the signature library
     * 
     * @param type
     *            type to check
     * @return true if the class is from the signature library. False otherwise
     */
    private static boolean isSigType(TypeReference type) {
        return type.toString().contains("Lsignatures/library/");
    }
}
