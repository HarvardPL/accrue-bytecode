package analysis.pointer.registrar;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import signatures.Signatures;
import util.InstructionType;
import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.ClassInitFinder;
import analysis.WalaAnalysisUtil;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.IClass;
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
import com.ibm.wala.types.TypeReference;

/**
 * Collect pointer analysis constraints with a pass over the code
 */
public class StatementRegistrationPass {

    /**
     * Output level
     */
    public static int VERBOSE = 0;
    /**
     * Set to true if in profiling mode, inserts breaks to allow for inspection
     */
    public static final boolean PROFILE = true;
    /**
     * Container and manager of points-to statements
     */
    private final StatementRegistrar registrar;
    /**
     * Methods we have already added statements for
     */
    private final Set<IMethod> visitedMethods = new LinkedHashSet<>();
    /**
     * WALA-defined analysis utilities
     */
    private final WalaAnalysisUtil util;
    /**
     * WALA representation of java.lang.String
     */
    private final IClass stringClass;
    /**
     * WALA representation of char[]
     */
    protected final IClass stringValueClass;
    /**
     * Factory for finding and creating reference variable (local variable and static fields)
     */
    private final ReferenceVariableFactory rvFactory = new ReferenceVariableFactory();
    /**
     * String literals that new allocation sites have already been created for
     */
    private final Set<ReferenceVariable> handledStringLit = new HashSet<>();

    /**
     * Create a pass which will generate points-to statements
     * 
     * @param util
     *            utility class containing WALA classes needed by this analysis
     */
    public StatementRegistrationPass(WalaAnalysisUtil util) {
        this.util = util;
        stringClass = util.getClassHierarchy().lookupClass(TypeReference.JavaLangString);
        stringValueClass = util.getClassHierarchy().lookupClass(TypeReference.JavaLangObject);
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
     * Add instructions to the work queue for the given method, if this method has not already been processed.
     * 
     * @param q
     *            work queue containing instructions to be processed
     * @param m
     *            method to process
     * @return true if this method has been added yet, false otherwise
     */
    private boolean addFromMethod(WorkQueue<InstrAndCode> q, IMethod m) {

        if (visitedMethods.contains(m)) {
            if (VERBOSE >= 2) {
                System.err.println("\tAlready added " + PrettyPrinter.methodString(m));
            }
            return false;
        }
        if (m.isAbstract()) {
            if (VERBOSE >= 2) {
                System.err.println("No need to analyze abstract methods: " + m.getSignature());
            }
            return false;
        }

        visitedMethods.add(m);

        MethodSummaryNodes summaryNodes = new MethodSummaryNodes(m, rvFactory);
        registrar.recordMethod(m, summaryNodes);

        IR ir = util.getIR(m);

        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            for (SSAInstruction ins : bb) {
                q.add(new InstrAndCode(ins, ir));
            }
        }

        if (VERBOSE >= 1) {
            System.err.println(PrettyPrinter.methodString(m) + " was registered.");
        }

        if (VERBOSE >= 2) {
            try (Writer writer = new StringWriter()) {
                PrettyPrinter.writeIR(ir, writer, "\t", "\n");
                System.err.print(writer.toString());
            } catch (IOException e) {
                throw new RuntimeException();
            }
        }
        return true;
    }

    /**
     * Add statements given class initializers
     * 
     * @param trigger
     *            instruction that triggered the clinit
     * @param containingCode
     *            code containing the instruction that triggered the clinit
     * @param clinits
     *            class initialization methods that might need to be called in the order they need to be called (i.e.
     *            element j is a super class of element j+1)
     * @return true if any new class initializer was seen
     */
    private boolean addClassInitializers(SSAInstruction trigger, IR containingCode, WorkQueue<InstrAndCode> q,
                                    List<IMethod> clinits) {
        boolean added = false;
        for (int j = clinits.size() - 1; j >= 0; j--) {
            IMethod clinit = clinits.get(j);
            boolean oneAdded = addFromMethod(q, clinit);
            if (oneAdded && VERBOSE >= 1) {
                System.err.println("Adding: " + PrettyPrinter.typeString(clinit.getDeclaringClass().getReference())
                                                + " initializer");
            }
            added |= oneAdded;
        }
        registrar.addStatementsForClassInitializer(trigger, containingCode, clinits);
        return added;
    }

    private void addFromNative(IMethod m, SSAInvokeInstruction callSite, IR ir, WorkQueue<InstrAndCode> q) {

        if (visitedMethods.contains(m)) {
            if (VERBOSE >= 2) {
                System.err.println("\tAlready added native " + PrettyPrinter.methodString(m));
            }
            return;
        }

        visitedMethods.add(m);

        MethodSummaryNodes summaryNodes = new MethodSummaryNodes(m, rvFactory);
        registrar.recordMethod(m, summaryNodes);

        // ********** SIGNATURES ************ //
        IR sigIR = Signatures.getSignatureIR(m, util);
        if (sigIR != null) {
            IMethod sigMethod = sigIR.getMethod();
            registrar.recordMethod(sigMethod, new MethodSummaryNodes(sigMethod, rvFactory));
            for (ISSABasicBlock bb : sigIR.getControlFlowGraph()) {
                for (SSAInstruction ins : bb) {
                    q.add(new InstrAndCode(ins, sigIR));
                }
            }

            if (VERBOSE >= 1) {
                System.err.println(PrettyPrinter.methodString(sigMethod) + " signature registered");
            }

            if (VERBOSE >= 2) {
                try (Writer writer = new StringWriter()) {
                    PrettyPrinter.writeIR(sigIR, writer, "\t", "\n");
                    System.err.print(writer.toString());
                } catch (IOException e) {
                    throw new RuntimeException();
                }
            }
        } else {
            if (VERBOSE >= 3) {
                System.err.println("NO SIGNATURE FOR " + PrettyPrinter.methodString(m) + " " + m);
            }
        }
        // ********** END SIGNATURES ************ //

        // Add points-to statements
        registrar.addStatementsForNative(m, summaryNodes, ir, callSite, util.getClassHierarchy());

        if (VERBOSE >= 2) {
            System.err.println("\tAdding statements from native method: " + PrettyPrinter.methodString(m));
        }
    }

    /**
     * Run the pass
     */
    public void run() {
        long start = System.currentTimeMillis();
        final WorkQueue<InstrAndCode> q = new WorkQueue<>();
        init(q);

        while (!q.isEmpty()) {
            InstrAndCode info = q.poll();
            handle(q, info);
        }
        System.err.println("Registered " + registrar.getAllStatements().size() + " statements.");
        System.err.println("It took " + (System.currentTimeMillis() - start) + "ms");
        if (PROFILE) {
            System.err.println("PAUSED HIT ENTER TO CONTINUE: ");
            try {
                System.in.read();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Handle a particular instruction, this dispatches on the type of the instruction
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
        List<IMethod> inits = ClassInitFinder.getClassInitializers(util.getClassHierarchy(), i);
        if (!inits.isEmpty()) {
            addClassInitializers(i, ir, q, inits);
        }

        // Add statements for any string literals in the instruction
        addStatementsForStringLiterals(i, ir, stringClass, stringValueClass);

        // Add statements for any JVM-generated exceptions this instruction
        // could throw (e.g. NullPointerException)
        registrar.addStatementsForGeneratedExceptions(i, ir, util.getClassHierarchy(), rvFactory);

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
            // procedure calls, instance initializers, constructor invocation
            SSAInvokeInstruction inv = (SSAInvokeInstruction) i;

            Set<IMethod> targets = StatementRegistrar.resolveMethodsForInvocation(inv, util);
            for (IMethod m : targets) {
                if (VERBOSE >= 1) {
                    System.err.println("Adding: " + PrettyPrinter.methodString(m) + " from "
                                                    + PrettyPrinter.methodString(ir.getMethod()) + " for "
                                                    + PrettyPrinter.instructionString(inv, ir));
                }
                if (m.isNative()) {
                    addFromNative(m, inv, ir, q);
                } else {
                    addFromMethod(q, m);
                }
            }
            registrar.registerInvoke(inv, ir, util, rvFactory);
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

    /**
     * Look for String literals in the instruction and create allocation sites for them
     * 
     * @param i
     *            instruction to create string literals for
     * @param ir
     *            code containing the instruction
     * @param stringClass
     *            WALA representation of the java.lang.String class
     */
    private void addStatementsForStringLiterals(SSAInstruction i, IR ir, IClass stringClass, IClass stringValueClass) {
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int use = i.getUse(j);
            if (ir.getSymbolTable().isStringConstant(use)) {
                ReferenceVariable newStringLit = rvFactory.getOrCreateLocal(use, ir);
                if (handledStringLit.contains(newStringLit)) {
                    // Already handled this allocation
                    return;
                }
                handledStringLit.add(newStringLit);

                // The fake root method always allocates a String so the clinit has already been called, even if we are
                // flow sensitive

                // add points to statements to simulate the allocation
                registrar.addStatementsForStringLit(newStringLit, use, ir, i, stringClass, stringValueClass, rvFactory);
            }
        }

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
