package signatures;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
import com.ibm.wala.ssa.SSAInstructionFactory;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.strings.Atom;

public class Signatures {

    private final static TypeReference SYNTHETIC_SYSTEM = TypeReference.findOrCreate(ClassLoaderReference.Primordial,
                                    TypeName.string2TypeName("Lcom/ibm/wala/model/java/lang/System"));

    public final static Atom arraycopyAtom = Atom.findOrCreateUnicodeAtom("arraycopy");

    private final static Descriptor arraycopyDescSynthetic = Descriptor
                                    .findOrCreateUTF8("(Ljava/lang/Object;Ljava/lang/Object;)V");

    private final static Descriptor arraycopyDesc = Descriptor
                                    .findOrCreateUTF8("(Ljava/lang/Object;Ljava/lang/Object;)V");

    private final static MethodReference SYNTHETIC_ARRAYCOPY = MethodReference.findOrCreate(SYNTHETIC_SYSTEM,
                                    arraycopyAtom, arraycopyDescSynthetic);

    private final static TypeReference ACTUAL_SYSTEM = TypeReference.findOrCreate(ClassLoaderReference.Primordial,
                                    TypeName.string2TypeName("Ljava/lang/System"));
    private final static MethodReference ACTUAL_ARRAYCOPY = MethodReference.findOrCreate(ACTUAL_SYSTEM, arraycopyAtom,
                                    arraycopyDesc);

    private final static SSAInstructionFactory INSTRUCTION_FACTORY = Language.JAVA.instructionFactory();

    private static SSAInvokeInstruction getSyntheticArrayCopy(SSAInvokeInstruction actualArrayCopy) {
        // generate a synthetic arraycopy from this (v.n. 1) to the clone
        int[] params = new int[2];
        params[0] = actualArrayCopy.getUse(0);
        params[1] = actualArrayCopy.getUse(2);
        // note that the synthetic one has just 2 arguments and always copies
        // probably need to rewrite it
        CallSiteReference callSite = CallSiteReference.make(actualArrayCopy.getProgramCounter(), SYNTHETIC_ARRAYCOPY,
                                        IInvokeInstruction.Dispatch.STATIC);
        return INSTRUCTION_FACTORY.InvokeInstruction(params, actualArrayCopy.getException(), callSite);
    }

    public static SSAInvokeInstruction getSyntheticInvoke(SSAInvokeInstruction actualInvoke) {
        if (actualInvoke.getDeclaredTarget().equals(ACTUAL_ARRAYCOPY)) {
            return getSyntheticArrayCopy(actualInvoke);
        }
        return null;
    }
}
