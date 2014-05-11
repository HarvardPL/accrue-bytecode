package analysis.pointer.statements;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import util.InstructionType;
import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.ClassInitFinder;
import analysis.WalaAnalysisUtil;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
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
 * Collect pointer analysis constraints with a pass over the code
 */
public class StatementRegistrationPass {

    /**
     * Output level
     */
    public static int VERBOSE = 2;
    /**
     * Container and manager of points-to statements
     */
    private final StatementRegistrar registrar;
    /**
     * Methods we have already added statements for
     */
    private final Set<IMethod> visitedMethods = new LinkedHashSet<>();
    /**
     * <clinit> methods that have already been added
     */
    private final Set<IMethod> classInits = new LinkedHashSet<>();
    /**
     * WALA-defined analysis utilities
     */
    private final WalaAnalysisUtil util;

    /**
     * Create a pass which will generate points-to statements
     * 
     * @param util
     *            utility class containing WALA classes needed by this analyssi
     */
    public StatementRegistrationPass(WalaAnalysisUtil util) {
        this.util = util;
        registrar = new StatementRegistrar();
    }

    /**
     * Initialize the queue using the defined entry points
     */
    private void init(WorkQueue<InstrAndCode> q) {
        registrar.setEntryPoint(util.getFakeRoot());
        addFromMethod(q, util.getFakeRoot());
    }

    /**
     * Add instructions to the work queue for the given method, if this method
     * has not already been processed.
     * 
     * @param q
     *            work queue containing instructions to be processed
     * 
     * @param m
     *            method to process
     * @param addClassInit
     *            if true then a class initializer will be added if needed
     *            (should be false if <code>m</code> is WALA's fake root method
     *            which has no class initializer)
     */
    private void addFromMethod(WorkQueue<InstrAndCode> q, IMethod m) {

        if (visitedMethods.contains(m)) {
            if (VERBOSE >= 2) {
                System.err.println("\tAlready added " + PrettyPrinter.parseMethod(m));
            }
            return;
        }
        if (m.isAbstract()) {
            System.err.println("No need to analyze abstract methods: " + m.getSignature());
            return;
        }

        visitedMethods.add(m);
        if (m.isNative()) {
            addFromNative(q, m);
            return;
        }

        IR ir = util.getCache().getSSACache()
                                        .findOrCreateIR(m, Everywhere.EVERYWHERE, util.getOptions().getSSAOptions());
        registrar.recordMethod(m, new MethodSummaryNodes(registrar, ir));

        if (VERBOSE >= 1) {
            System.err.println(PrettyPrinter.parseMethod(m) + " will be registered.");
        }
        if (VERBOSE >= 2) {
            try (Writer writer = new StringWriter()) {
                PrettyPrinter.writeIR(ir, writer, "\t", "\n");
                System.err.print(writer.toString());
            } catch (IOException e) {
                throw new RuntimeException();
            }
        }

        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            for (SSAInstruction ins : bb) {
                q.add(new InstrAndCode(ins, ir));
            }
        }
    }

    /**
     * As defined in JLS 12.4.1, add class initializers for the given
     * instruction if needed.
     * 
     * @param q
     *            work queue for instructions
     * @param i
     *            current instruction
     * @param ir
     *            code for method containing instruction
     * @return true if any class initializers were added
     */
    private boolean addClassInitForInstruction(WorkQueue<InstrAndCode> q, SSAInstruction i, IR ir) {
        List<IMethod> inits = ClassInitFinder.getClassInitializers(util.getClassHierarchy(), i, ir);

        boolean added = false;
        for (int j = inits.size() - 1; j >= 0; j--) {
            IMethod clinit = inits.get(j);
            if (classInits.add(clinit)) {
                if (VERBOSE >= 1) {
                    System.err.println("Adding: " + PrettyPrinter.parseType(clinit.getDeclaringClass().getReference())
                                                    + " initializer");
                }
                addFromMethod(q, clinit);
                added = true;
            } else {
                // we have reached a class in the list that has already been
                // initialized the rest are super classes that must have already
                // been initialized
                break;
            }
        }

        return added;
    }

    @SuppressWarnings("unused")
    private static void addFromNative(WorkQueue<InstrAndCode> q, IMethod m) {
        // TODO Statement registration not handling native methods yet
        if (VERBOSE >= 2) {
            System.err.println("\tNot adding statements from native methods yet " + PrettyPrinter.parseMethod(m));
        }
    }

    /**
     * Run the pass
     */
    public void run() {
        final WorkQueue<InstrAndCode> q = new WorkQueue<>();
        init(q);

        while (!q.isEmpty()) {
            InstrAndCode info = q.poll();
            handle(q, info);
        }
    }

    /**
     * Handle a particular instruction, this dispatches on the type of the
     * instruction
     * 
     * @param info
     *            instruction and IR to handle
     */
    private void handle(WorkQueue<InstrAndCode> q, InstrAndCode info) {
        SSAInstruction i = info.instruction;
        IR ir = info.ir;

        assert i.getNumberOfDefs() <= 2 : "More than two defs in instruction: "
                                        + PrettyPrinter.instructionString(i, ir);

        // Add any class initializers required before executing this instruction
        // TODO can be even more precise if we add a LoadClassStatement for each
        // possible clinit, add that to the list of statements for the method
        // containing the instruction that could load, and then make sure to
        // only handle each one once in the pointer analysis
        addClassInitForInstruction(q, i, ir);

        // Add statements for any JVM-generated exceptions this instruction
        // could throw (e.g. NullPointerException)
        registrar.addStatementsForGeneratedExceptions(i, ir, util.getClassHierarchy());

        InstructionType type = InstructionType.forInstruction(i);
        switch (type) {
        case ARRAY_LOAD:
            // x = v[i]
            registrar.registerArrayLoad((SSAArrayLoadInstruction) i, ir);
            return;
        case ARRAY_STORE:
            // v[i] = x
            registrar.registerArrayStore((SSAArrayStoreInstruction) i, ir);
            return;
        case CHECK_CAST:
            // v = (Type) x
            registrar.registerCheckCast((SSACheckCastInstruction) i, ir);
            return;
        case GET_FIELD:
            // v = o.f
            registrar.registerGetField((SSAGetInstruction) i, ir);
            return;
        case GET_STATIC:
            // v = ClassName.f
            registrar.registerGetStatic((SSAGetInstruction) i, ir, util.getClassHierarchy());
            return;
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_STATIC:
        case INVOKE_VIRTUAL:
            // procedure calls, instance initializers, constructor invocation
            SSAInvokeInstruction inv = (SSAInvokeInstruction) i;

            Set<IMethod> targets = StatementRegistrar.resolveMethodsForInvocation(inv, util.getClassHierarchy());
            for (IMethod m : targets) {
                if (VERBOSE >= 1) {
                    System.err.println("Adding: " + PrettyPrinter.parseMethod(m) + " from "
                                                    + PrettyPrinter.parseMethod(ir.getMethod()));
                }
                addFromMethod(q, m);
            }
            registrar.registerInvoke(inv, ir, util.getClassHierarchy());
            return;
        case LOAD_METADATA:
            // Reflection
            registrar.registerReflection((SSALoadMetadataInstruction) i, ir);
            return;
        case NEW_ARRAY:
            registrar.registerNewArray((SSANewInstruction) i, ir, util.getClassHierarchy());
            return;
        case NEW_OBJECT:
            // v = new Foo();
            registrar.registerNewObject((SSANewInstruction) i, ir, util.getClassHierarchy());
            return;
        case PHI:
            // v = phi(x_1,x_2)
            registrar.registerPhiAssignment((SSAPhiInstruction) i, ir);
            return;
        case PUT_FIELD:
            // o.f = v
            registrar.registerPutField((SSAPutInstruction) i, ir);
            return;
        case PUT_STATIC:
            // ClassName.f = v
            registrar.registerPutStatic((SSAPutInstruction) i, ir, util.getClassHierarchy());
            return;
        case RETURN:
            // return v
            registrar.registerReturn((SSAReturnInstruction) i, ir);
            return;
        case THROW:
            // throw e
            registrar.registerThrow((SSAThrowInstruction) i, ir, util.getClassHierarchy());
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
    private static class InstrAndCode {
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
}
