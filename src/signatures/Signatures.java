package signatures;

import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Map;

import util.InstructionType;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInstructionFactory;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.strings.Atom;

/**
 * Class that computes signatures for methods that have hand written Java signatures in the <code>signatures</code>
 * package
 */
public class Signatures {

    /**
     * If this flag is set then the control flow graph (with instructions) will be printed for all signatures before and
     * after being rewritten.
     */
    private static final boolean PRINT_ALL_SIGNATURES = false;
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
     * Memoization map for signature so they do not have to be recomputed, note that the IR could be garbage collected,
     * in which case it must be recomputed.
     */
    private final Map<IMethod, SoftReference<IR>> signatures = new HashMap<>();

    /**
     * Check whether the given method has a signature
     *
     * @param actualMethod
     *            method to check
     * @return true if a signature exists for the given method
     */
    public boolean hasSignature(IMethod actualMethod) {
        if (signatures.containsKey(actualMethod)) {
            return signatures.get(actualMethod) != null;
        }

        MethodReference actual = actualMethod.getReference();
        if (isSigType(actual.getDeclaringClass())) {
            // asking about signature for a signature type just record and return false
            signatures.put(actualMethod, null);
            return false;
        }

        // Try to resolve signature method
        TypeReference sigTarget = getSigTypeForRealType(actual.getDeclaringClass().getName());
        IMethod resolvedSig = resolveSignatureMethod(sigTarget, actual.getName(), actual.getDescriptor());

        if (resolvedSig == null) {
            // no signature found
            signatures.put(actualMethod, null);
            return false;
        }

        return true;
    }

    /**
     * Get the IR for a method that has a signature
     *
     * @param actualMethod
     *            method to find the signature for
     * @return the signature IR or null if no signature is found
     */
    public IR getSignatureIR(IMethod actualMethod) {
        if (signatures.containsKey(actualMethod)) {
            SoftReference<IR> sigRef = signatures.get(actualMethod);
            if (sigRef == null) {
                // already determined that there is no signature
                return null;
            }
            IR sig = sigRef.get();
            if (sig != null) {
                // Already computed the signature return it
                return sig;
            }
            // We computed the signature, but it was collected by the garbage collector so compute it again
            System.err.println("Signature for " + PrettyPrinter.methodString(actualMethod)
                                            + " is being recomputed. It was garbage collected at some point.");
        }

        MethodReference actual = actualMethod.getReference();
        if (isSigType(actual.getDeclaringClass())) {
            // requesting signature for a signature type just record and return null
            signatures.put(actualMethod, null);
            return null;
        }

        TypeReference actualTarget = actual.getDeclaringClass();
        // Take the name of the real method target and append signatures/library to make it a signature type
        TypeReference sigTarget = getSigTypeForRealType(actualTarget.getName());
        IMethod resolvedSig = resolveSignatureMethod(sigTarget, actual.getName(), actual.getDescriptor());

        if (resolvedSig == null) {
            // no signature found
            signatures.put(actualMethod, null);
            return null;
        }

        IR sigIR = AnalysisUtil.getIR(resolvedSig);
        if (sigIR != null) {
            sigIR = rewriteIR(sigIR, actualMethod);
            if (sigIR != null && PointsToAnalysis.outputLevel > 0) {
                System.err.println("USING SIGNATURE for: " + PrettyPrinter.methodString(actualMethod));
            }
            signatures.put(actualMethod, new SoftReference<>(sigIR));
        }
        else {
            signatures.put(actualMethod, null);
        }
        return sigIR;
    }

    private static TypeReference getSigTypeForRealType(TypeName realType) {
        if (realType.isArrayType()) {
            return TypeReference.findOrCreate(CLASS_LOADER, "[Lsignatures/library/" + realType.toString().substring(2));
        }
        return TypeReference.findOrCreate(CLASS_LOADER, "Lsignatures/library/" + realType.toString().substring(1));
    }

    /**
     * Try to find a signature method with declaring type <code>sigTarget</code> and the given name and descriptor
     *
     * @param sigTarget Declaring class type
     * @param name name of the method to look for
     * @param descriptor argument and return types
     * @return the signature method if found, otherwise returns null
     */
    private static IMethod resolveSignatureMethod(TypeReference sigTarget, Atom name, Descriptor descriptor) {

        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();

        MethodReference mr = MethodReference.findOrCreate(sigTarget, name, descriptor);
        IMethod resolved = cha.resolveMethod(mr);

        if (resolved == null && !mr.getReturnType().equals(TypeReference.Void)) {
            // No signature found check if the return has an associated signature type, if so try again with that
            // version
            TypeReference sigReturn = getSigTypeForRealType(descriptor.getReturnType());
            IClass resolvedReturn = cha.lookupClass(sigReturn);
            if (resolvedReturn != null) {
                // There is a signature for the return type try to use it
                Descriptor newDescriptor = Descriptor.findOrCreate(descriptor.getParameters(), sigReturn.getName());
                mr = MethodReference.findOrCreate(sigTarget, name, newDescriptor);
                resolved = cha.resolveMethod(mr);

                if (resolved == null) {
                    // try replacing the argument types as well
                    resolved = replaceParamsWithSigTypes(sigTarget, name, newDescriptor);
                }
            }
        }

        if (resolved == null) {
            // nothing found yet try replacing only the argument types with signature types
            resolved = replaceParamsWithSigTypes(sigTarget, name, descriptor);
        }

        if (resolved != null && !isSigType(resolved.getDeclaringClass().getReference())) {
            // Some super class not in the signature library was found, we don't want to overwrite the method with its
            // superclass' method. One example of this is when an undefined clinit resolves to Object.<clinit>, which
            // we don't want to replace the real <clinit> with.
            return null;
        }

        return resolved;
    }

    /**
     * Replace all signature types in the list with real types if any can be found
     *
     * @param types names of types to replace
     * @return new array of types
     */
    private static TypeName[] replaceAllTypeNamesWithReal(TypeName[] types) {
        if (types == null) {
            // probably the arguments to a method with no args
            return null;
        }
        TypeName[] newTypes = new TypeName[types.length];
        for (int i = 0; i < types.length; i++) {
            newTypes[i] = types[i];
            TypeReference type_i = TypeReference.findOrCreate(CLASS_LOADER, types[i]);
            if (isSigType(type_i)) {
                TypeReference realParam = findRealTypeForSigType(TypeReference.findOrCreate(CLASS_LOADER, types[i]));
                if (realParam != null) {
                    newTypes[i] = realParam.getName();
                }
            }
        }
        return newTypes;
    }

    /**
     * Replace all the types in the list with signature types if any can be found
     *
     * @param types names of types to replace
     * @return new array of types
     */
    private static TypeName[] replaceAllTypeNamesWithSig(TypeName[] types) {
        if (types == null) {
            // probably the arguments to a method with no args
            return null;
        }
        TypeName[] newParams = new TypeName[types.length];
        for (int i = 0; i < types.length; i++) {
            TypeReference sigParam = getSigTypeForRealType(types[i]);
            IClass resolvedParam = AnalysisUtil.getClassHierarchy().lookupClass(sigParam);
            if (resolvedParam != null) {
                newParams[i] = resolvedParam.getName();
            }
            else {
                newParams[i] = types[i];
            }
        }
        return newParams;
    }

    /**
     * Try to find a signature method by replacing the argument types (in the descriptor) with signature types. Return
     * null if none is found or if there are no arguments.
     *
     * @param sigTarget Declaring class type
     * @param name name of the method to look for
     * @param descriptor argument and return types (the argument types are what this replace and try)
     * @return the signature method if found, otherwise returns null
     */
    private static IMethod replaceParamsWithSigTypes(TypeReference sigTarget, Atom name, Descriptor descriptor) {
        if (descriptor.getNumberOfParameters() > 0) {
            // No signature found check if the arguments have an associated signature type, if so try again with that
            // version
            TypeName[] params = descriptor.getParameters();
            TypeName[] newParams = replaceAllTypeNamesWithSig(params);

            // There is a signature for at least one parameter try to use it
            descriptor = Descriptor.findOrCreate(newParams, descriptor.getReturnType());
            MethodReference mr = MethodReference.findOrCreate(sigTarget, name, descriptor);
            return AnalysisUtil.getClassHierarchy().resolveMethod(mr);
        }
        return null;
    }

    /**
     * If the receiver types for method invocations and field accesses are signature types, replace them with "real"
     * types if they exist. Also swap the method in the IR for the "real" method
     *
     * @param sigIR
     *            IR for the signature method
     * @param actualMethod
     *            "real" resolved method the IR is the signature for
     * @return IR with signature types replaced with the "real" versions when they can be found
     */
    private static IR rewriteIR(IR sigIR, IMethod actualMethod) {
        if (PRINT_ALL_SIGNATURES) {
            CFGWriter.writeToFile(sigIR, "sig_" + PrettyPrinter.methodString(sigIR.getMethod()));
        }
        // does not include phi instructions, but these do not need to be converted
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
            case PHI:
            case RETURN:
            case SWITCH:
            case THROW:
            case UNARY_NEG_OP:
                // These all refer only to local variables so there is nothing to fix
                continue;
            case CHECK_CAST:
                updated = handleCheckCast((SSACheckCastInstruction) i);
                allInstructions[j] = updated;
                continue;
            case INSTANCE_OF:
                updated = handleInstanceOf((SSAInstanceofInstruction) i);
                allInstructions[j] = updated;
                continue;
            case NEW_ARRAY:
            case NEW_OBJECT:
                updated = handleNew((SSANewInstruction) i);
                allInstructions[j] = updated;
                continue;
            case PUT_FIELD:
            case PUT_STATIC:
                updated = handlePut((SSAPutInstruction) i);
                allInstructions[j] = updated;
                continue;
            case INVOKE_INTERFACE:
            case INVOKE_SPECIAL:
            case INVOKE_STATIC:
            case INVOKE_VIRTUAL:
                updated = handleInvoke((SSAInvokeInstruction) i);
                allInstructions[j] = updated;
                continue;
            case GET_FIELD:
            case GET_STATIC:
                updated = handleGet((SSAGetInstruction) i);
                allInstructions[j] = updated;
                continue;
            }
        }

        IR newIR = new SignatureIR(actualMethod, allInstructions, sigIR.getSymbolTable(), sigIR.getControlFlowGraph(),
                                        sigIR.getOptions());
        if (PRINT_ALL_SIGNATURES) {
            CFGWriter.writeToFile(newIR, "sig_rewrite_" + PrettyPrinter.methodString(newIR.getMethod()));
        }
        return newIR;
    }

    /**
     * If the checked type is a signature type, try to replace it with a real one
     *
     * @param i instruction to change
     * @return new instruction if the type changed or the same instruction if it doesn't
     */
    private static SSAInstruction handleInstanceOf(SSAInstanceofInstruction i) {
        TypeReference checkedType = i.getCheckedType();
        if (isSigType(checkedType)) {
            // This is an instanceof check on an type in the signatures package
            // Check to see if there is a "real" type out there that the signature type is emulating.
            TypeReference real = findRealTypeForSigType(checkedType);
            if (real != null) {
                // Found a real type
                SSAInstruction ii = I_FACTORY.InstanceofInstruction(i.getDef(), i.getRef(), real);
                if (PointsToAnalysis.outputLevel >= 5) {
                    System.err.println("SIGNATURE REPLACED: " + i + " with " + ii);
                }
                return ii;
            }
        }
        // This a check of a real class or a signature class with no real counterpart
        return i;
    }

    /**
     * If the checked type is a signature type, try to replace it with a real one
     *
     * @param i instruction to change
     * @return new instruction if the type changed or the same instruction if it doesn't
     */
    private static SSAInstruction handleCheckCast(SSACheckCastInstruction i) {
        TypeReference checkedType = i.getDeclaredResultTypes()[0];
        if (isSigType(checkedType)) {
            // This is a cast to a type in the signatures package
            // Check to see if there is a "real" type out there that the signature type is emulating.
            TypeReference real = findRealTypeForSigType(checkedType);
            if (real != null) {
                // Found a real type
                SSAInstruction ii = I_FACTORY.CheckCastInstruction(i.getResult(), i.getVal(), real, i.isPEI());
                if (PointsToAnalysis.outputLevel >= 5) {
                    System.err.println("SIGNATURE REPLACED: " + i + " with " + ii);
                }
                return ii;
            }
        }
        // This a check of a real class or a signature class with no real counterpart
        return i;
    }

    /**
     * If the receiver type is a signature type then replace it with the corresponding "real" type if there is one
     *
     * @param i instruction to potentially replace
     * @return new instruction (or same instruction if no changes were made)
     */
    private static SSAInstruction handleGet(SSAGetInstruction i) {

        TypeReference receiverType = i.getDeclaredField().getDeclaringClass();

        TypeReference fieldType = i.getDeclaredFieldType();
        if (isSigType(fieldType)) {
            fieldType = findRealTypeForSigType(fieldType);
        }

        if (isSigType(receiverType)) {
            TypeReference real = findRealTypeForSigType(receiverType);
            if (real != null) {
                FieldReference newFR = FieldReference.findOrCreate(real, i.getDeclaredField().getName(), fieldType);
                IField newField = AnalysisUtil.getClassHierarchy().resolveField(newFR);
                if (newField != null) {
                    // The field exists on the real type use it to create a new instruction
                    if (i.isStatic()) {
                        SSAInstruction ii = I_FACTORY.GetInstruction(i.getDef(), newFR);
                        if (PointsToAnalysis.outputLevel >= 5) {
                            System.err.println("SIGNATURE REPLACED: " + i + " with " + ii);
                        }
                        return ii;
                    }
                    SSAInstruction ii = I_FACTORY.GetInstruction(i.getDef(), i.getRef(), newFR);
                    if (PointsToAnalysis.outputLevel >= 5) {
                        System.err.println("SIGNATURE REPLACED: " + i + " with " + ii);
                    }
                    return ii;
                }
            }
        }

        return i;
    }

    /**
     * If the receiver type or return type is a signature type then replace it with the corresponding "real" type if
     * there is one
     *
     * @param i
     *            instruction to potentially replace
     * @return new instruction (or same instruction if no changes were made)
     */
    private static SSAInstruction handleInvoke(SSAInvokeInstruction i) {
        TypeReference receiverType = i.getDeclaredTarget().getDeclaringClass();
        TypeReference returnType = i.getDeclaredResultType();

        TypeReference realReturn = returnType;
        if (isSigType(returnType)) {
            realReturn = findRealTypeForSigType(returnType);
        }

        TypeName[] realParams = replaceAllTypeNamesWithReal(i.getDeclaredTarget()
                                                        .getSelector()
                                                        .getDescriptor()
                                                        .getParameters());

        if (isSigType(receiverType)) {
            TypeReference real = findRealTypeForSigType(receiverType);
            if (real != null) {
                Selector s = i.getDeclaredTarget().getSelector();
                Descriptor d = Descriptor.findOrCreate(realParams, realReturn.getName());
                s = new Selector(s.getName(), d);

                MethodReference newMR = MethodReference.findOrCreate(real, s);
                IMethod newMethod = AnalysisUtil.getClassHierarchy().resolveMethod(newMR);
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
                    if (PointsToAnalysis.outputLevel >= 5) {
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
     * @return new instruction (or same instruction if no changes were made)
     */
    private static SSAInstruction handlePut(SSAPutInstruction i) {
        TypeReference receiverType = i.getDeclaredField().getDeclaringClass();

        TypeReference fieldType = i.getDeclaredFieldType();
        if (isSigType(fieldType)) {
            fieldType = findRealTypeForSigType(fieldType);
        }

        if (isSigType(receiverType)) {
            TypeReference real = findRealTypeForSigType(receiverType);
            if (real != null) {
                FieldReference newFR = FieldReference.findOrCreate(real, i.getDeclaredField().getName(), fieldType);
                IField newField = AnalysisUtil.getClassHierarchy().resolveField(newFR);
                if (newField != null) {
                    // The field exists on the real type use it to create a new put instruction
                    if (i.isStatic()) {
                        SSAInstruction ii = I_FACTORY.PutInstruction(i.getVal(), newFR);
                        if (PointsToAnalysis.outputLevel >= 5) {
                            System.err.println("SIGNATURE REPLACED: " + i + " with " + ii);
                        }
                        return ii;
                    }
                    SSAInstruction ii = I_FACTORY.PutInstruction(i.getRef(), i.getVal(), newFR);
                    if (PointsToAnalysis.outputLevel >= 5) {
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
     * @return new instruction (or same instruction if no changes were made)
     */
    private static SSAInstruction handleNew(SSANewInstruction i) {
        TypeReference allocatedType = i.getConcreteType();
        if (isSigType(allocatedType)) {
            // This is an allocation of an object in the signatures package
            // Check to see if there is a "real" object out there that the signature object is emulating.
            TypeReference real = findRealTypeForSigType(allocatedType);
            if (real != null) {
                // Found a real object
                NewSiteReference site = i.getNewSite();
                NewSiteReference newSite = new NewSiteReference(site.getProgramCounter(), real);
                SSAInstruction ii;
                if (real.isArrayType()) {
                    int[] dimensions = new int[i.getNumberOfUses()];
                    for (int j = 0; j < i.getNumberOfUses(); j++) {
                        dimensions[j] = i.getUse(j);
                    }
                    ii = I_FACTORY.NewInstruction(i.getDef(), newSite, dimensions);
                }
                else {
                    ii = I_FACTORY.NewInstruction(i.getDef(), newSite);
                }
                if (PointsToAnalysis.outputLevel >= 5) {
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
     * @return Type for the real class corresponding to the given type from the signature library, null if there is no
     *         corresponding type
     */
    private static TypeReference findRealTypeForSigType(TypeReference signatureType) {
        assert isSigType(signatureType) : "Can only find classes for signature types.";
        // This is an allocation of an object in the signatures package
        // Check to see if there is a "real" object out there the signature object is emulating.
        String typeToCheck = signatureType.getName().toString().replace("Lsignatures/library/", "L");
        if (PointsToAnalysis.outputLevel >= 5) {
            System.err.println("SIGNATURE LOOKING: " + typeToCheck);
        }
        TypeReference potentialMatch = TypeReference.findOrCreate(CLASS_LOADER, typeToCheck);
        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(potentialMatch);
        if (klass == null) {
            return null;
        }
        if (PointsToAnalysis.outputLevel >= 5) {
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
    public static boolean isSigType(TypeReference type) {
        return type.toString().contains("Lsignatures/library/");
    }
}
