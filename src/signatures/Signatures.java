package signatures;

import java.util.HashMap;
import java.util.Map;

import signatures.synthetic.SyntheticIR;
import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

public class Signatures {

    private static final ClassLoaderReference CLASS_LOADER = ClassLoaderReference.Application;
    // private static final ClassLoaderReference PRIMORDIAL_CLASS_LOADER = ClassLoaderReference.Primordial;

    // private static final SSAInstructionFactory INSTRUCTION_FACTORY = Language.JAVA.instructionFactory();

    // private static final Map<IMethod, IR> syntheticIR = new HashMap<>();
    //
    // /**
    // * Get the IR for a method created synthetically in this class
    // *
    // * @param resolvedMethod
    // * synthetic method to get the IR for (if it exists)
    // * @return the IR for the synthetic method
    // */
    // public static IR getSyntheticIR(IMethod resolvedMethod) {
    // assert resolvedMethod.isSynthetic();
    // return syntheticIR.get(resolvedMethod);
    // }
    
    private static final Map<IMethod, IR> signatures = new HashMap<>();

    @SuppressWarnings("deprecation")
    public static IR getSignatureIR(IMethod actualMethod, WalaAnalysisUtil util) {
        if (signatures.containsKey(actualMethod)) {
            return signatures.get(actualMethod);
        }

        MethodReference actual = actualMethod.getReference();

        TypeReference sigTarget = TypeReference.findOrCreate(CLASS_LOADER, "Lsignatures/"
                                        + actual.getDeclaringClass().getName().toString().substring(1));
        MethodReference sigMethod = MethodReference.findOrCreate(sigTarget, actual.getName(), actual.getDescriptor());

        try {
            IR sigIR = util.getIR(util.getClassHierarchy().resolveMethod(sigMethod));
            sigIR = new SyntheticIR(actualMethod, sigIR.getInstructions(), sigIR.getSymbolTable(),
                                            sigIR.getControlFlowGraph(), sigIR.getOptions());
            System.err.println("Using signature for: " + PrettyPrinter.methodString(actualMethod));
            signatures.put(actualMethod, sigIR);
            return sigIR;
        } catch (RuntimeException e) {
            // Any exceptions means that no signature was found
            return null;
        }
    }

    // Lets not do this, there is something more fundamentally wrong with TreeMap
    // public static IR synthesizeStaticAccessIR(String fieldName, IClass klass, MethodReference mr, AnalysisOptions
    // opts) {
    // IMethod method = new SyntheticMethod(mr, klass, true, false);
    // if (syntheticIR.containsKey(method)) {
    // return syntheticIR.get(method);
    // }
    //
    // FieldReference field = null;
    // for (IField f : klass.getAllFields()) {
    // if (f.getName().toString().contains(fieldName)) {
    // field = f.getReference();
    // break;
    // }
    // }
    // assert field != null : "Couldn't find " + fieldName + " for "
    // + PrettyPrinter.typeString(klass.getReference());
    //
    // SymbolTable symbolTable = new SymbolTable(0);
    // int assignee = symbolTable.newSymbol();
    // SSAInstruction[] instructions = new SSAInstruction[2];
    // instructions[0] = INSTRUCTION_FACTORY.GetInstruction(assignee, field);
    // instructions[1] = INSTRUCTION_FACTORY.ReturnInstruction(assignee, true);
    //
    // StraightLineCFG cfg = new StraightLineCFG(method);
    // SimpleBasicBlock bb = new SimpleBasicBlock(0, Arrays.asList(instructions), 1, method, false);
    // cfg.addNode(bb);
    //
    // SSACFG ssacfg = new SSACFG(method, cfg, instructions);
    // IR ir = new SyntheticIR(method, instructions, symbolTable, ssacfg, opts.getSSAOptions());
    // syntheticIR.put(method, ir);
    // return ir;
    // }
}
