package analysis.pointer.registrar;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import util.InstructionType;
import util.print.PrettyPrinter;
import analysis.ClassInitFinder;
import analysis.WalaAnalysisUtil;
import analysis.pointer.graph.ReferenceVariableCache;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;

/**
 * Collect pointer analysis constraints
 */
public class RegistrationUtil {

    /**
     * Output level (defines how much is printed to the console)
     */
    public static int outputLevel = 0;
    /**
     * Container and manager of points-to statements
     */
    protected final StatementRegistrar registrar;
    /**
     * Methods we have already added statements for
     */
    private final Set<IMethod> visitedMethods = new LinkedHashSet<>();
    /**
     * WALA-defined analysis utilities
     */
    protected final WalaAnalysisUtil util;
    /**
     * Factory for finding and creating reference variable (local variable and static fields)
     */
    private final ReferenceVariableFactory rvFactory = new ReferenceVariableFactory();

    /**
     * Create a pass which will generate points-to statements
     * 
     * @param util
     *            utility class containing WALA classes needed by this analysis
     */
    public RegistrationUtil(WalaAnalysisUtil util) {
        this.util = util;
        registrar = new StatementRegistrar();
        registrar.setEntryPoint(util.getFakeRoot());
    }

    /**
     * Handle all the instructions for a given method
     * 
     * @param m
     *            method to register points-to statements for
     */
    public void registerMethod(IMethod m) {
        for (InstrAndCode info : getFromMethod(m)) {

            // Add any class initializers required before executing this instruction
            // TODO can be even more precise if we add a LoadClassStatement for each
            // possible clinit, add that to the list of statements for the method
            // containing the instruction that could load, and then make sure to
            // only handle each one once in the pointer analysis
            List<IMethod> inits = ClassInitFinder.getClassInitializers(util.getClassHierarchy(), info.instruction);
            if (!inits.isEmpty()) {
                registrar.addStatementsForClassInitializer(info.instruction, info.ir, inits);
            }
            handle(info);
        }
    }

    /**
     * Get points-to statements for the given method if this method has not already been processed. (Does not
     * recursively get statements for callees.)
     * 
     * @param m
     *            method to get instructions for
     * @return set of new instructions, empty if the method is abstract, or has already been processed
     */
    protected Set<InstrAndCode> getFromMethod(IMethod m) {

        if (visitedMethods.contains(m)) {
            if (outputLevel >= 2) {
                System.err.println("\tAlready added " + PrettyPrinter.methodString(m));
            }
            return Collections.emptySet();
        }
        if (m.isAbstract()) {
            if (outputLevel >= 2) {
                System.err.println("No need to analyze abstract methods: " + m.getSignature());
            }
            return Collections.emptySet();
        }

        visitedMethods.add(m);
        IR ir = util.getIR(m);
        if (ir == null) {
            // Native method with no signature
            assert m.isNative() : "No IR for non-native method: " + PrettyPrinter.methodString(m);
            return Collections.emptySet();
        }

        Set<InstrAndCode> newInstructions = new LinkedHashSet<>();

        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            for (SSAInstruction ins : bb) {
                newInstructions.add(new InstrAndCode(ins, ir));
            }
        }

        if (outputLevel >= 2) {
            System.err.println(PrettyPrinter.methodString(m) + " was handled.");
        }

        if (outputLevel >= 6) {
            try (Writer writer = new StringWriter()) {
                PrettyPrinter.writeIR(ir, writer, "\t", "\n");
                System.err.print(writer.toString());
            } catch (IOException e) {
                throw new RuntimeException();
            }
        }
        return newInstructions;
    }

    /**
     * Handle a particular instruction, this dispatches on the type of the instruction
     * 
     * @param info
     *            instruction and IR to handle
     */
    protected void handle(InstrAndCode info) {
        SSAInstruction i = info.instruction;
        IR ir = info.ir;

        assert i.getNumberOfDefs() <= 2 : "More than two defs in instruction: "
                                        + PrettyPrinter.instructionString(i, ir);

        // Add statements for any string literals in the instruction
        registrar.addStatementsForStringLiterals(i, ir, util.getStringClass(), util.getStringValueClass(), rvFactory);

        // Add statements for any JVM-generated exceptions this instruction
        // could throw (e.g. NullPointerException)
        registrar.addStatementsForGeneratedExceptions(i, ir, util, rvFactory);

        InstructionType type = InstructionType.forInstruction(i);
        switch (type) {
        case ARRAY_LOAD:
            // x = v[i]
            registrar.registerArrayLoad((SSAArrayLoadInstruction) i, ir, rvFactory);
            return;
        case ARRAY_STORE:
            // v[i] = x
            registrar.registerArrayStore((SSAArrayStoreInstruction) i, ir, rvFactory);
            return;
        case CHECK_CAST:
            // v = (Type) x
            registrar.registerCheckCast((SSACheckCastInstruction) i, ir, rvFactory);
            return;
        case GET_FIELD:
            // v = o.f
            registrar.registerGetField((SSAGetInstruction) i, ir, rvFactory);
            return;
        case GET_STATIC:
            // v = ClassName.f
            registrar.registerGetStatic((SSAGetInstruction) i, ir, util.getClassHierarchy(), rvFactory);
            return;
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_STATIC:
        case INVOKE_VIRTUAL:
            // procedure calls, instance initializers
            registrar.registerInvoke((SSAInvokeInstruction) i, ir, util, rvFactory);
            return;
        case LOAD_METADATA:
            // Reflection
            registrar.registerReflection((SSALoadMetadataInstruction) i, ir, rvFactory);
            return;
        case NEW_ARRAY:
            registrar.registerNewArray((SSANewInstruction) i, ir, util.getClassHierarchy(), rvFactory);
            return;
        case NEW_OBJECT:
            // v = new Foo();
            registrar.registerNewObject((SSANewInstruction) i, ir, util.getClassHierarchy(), rvFactory);
            return;
        case PHI:
            // v = phi(x_1,x_2)
            registrar.registerPhiAssignment((SSAPhiInstruction) i, ir, rvFactory);
            return;
        case PUT_FIELD:
            // o.f = v
            registrar.registerPutField((SSAPutInstruction) i, ir, rvFactory);
            return;
        case PUT_STATIC:
            // ClassName.f = v
            registrar.registerPutStatic((SSAPutInstruction) i, ir, util.getClassHierarchy(), rvFactory);
            return;
        case RETURN:
            // return v
            registrar.registerReturn((SSAReturnInstruction) i, ir, rvFactory);
            return;
        case THROW:
            // throw e
            registrar.registerThrow((SSAThrowInstruction) i, ir, util.getClassHierarchy(), rvFactory);
            return;
        case ARRAY_LENGTH: // primitive op with generated exception
        case BINARY_OP: // primitive op
        case BINARY_OP_EX: // primitive op with generated exception
        case COMPARISON: // primitive op
        case CONDITIONAL_BRANCH: // computes primitive and branches
        case CONVERSION: // primitive op
        case GET_CAUGHT_EXCEPTION: // handled in PointsToStatement#checkThrown
        case GOTO: // control flow
        case INSTANCE_OF: // results in a primitive
        case MONITOR: // generated exception already taken care of
        case SWITCH: // only switch on int
        case UNARY_NEG_OP: // primitive op
            break;
        }
    }

    /**
     * Instruction together with the code it is a part of
     */
    protected static class InstrAndCode {
        final SSAInstruction instruction;
        final IR ir;

        public InstrAndCode(SSAInstruction instr, IR ir) {
            this.instruction = instr;
            this.ir = ir;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((instruction == null) ? 0 : instruction.hashCode());
            result = prime * result + System.identityHashCode(ir);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            InstrAndCode other = (InstrAndCode) obj;
            if (instruction == null) {
                if (other.instruction != null)
                    return false;
            } else if (!instruction.equals(other.instruction))
                return false;
            if (ir == null) {
                if (other.ir != null)
                    return false;
            } else if (!(ir == other.ir))
                return false;
            return true;
        }
    }

    /**
     * Get the statement registrar
     * 
     * @return statement registrar
     */
    public StatementRegistrar getRegistrar() {
        return registrar;
    }

    /**
     * Map from local variable to reference variable. This is not complete until the statement registration pass has
     * completed.
     * 
     * @return map from local variable to unique reference variable
     */
    public ReferenceVariableCache getAllLocals() {
        return rvFactory.getAllLocals();
    }
}
