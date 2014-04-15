package analysis.pointer.statements;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import util.InstructionType;
import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.pointer.graph.MethodSummaryNodes;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.impl.FakeRootMethod;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
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
     * Output level, 2 prints all method bodies
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
     * Classes we have already called <clinit> for
     */
    private final Set<IClass> initializedClasses = new LinkedHashSet<>();
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

        // Set up the entry points
        FakeRootMethod fakeRoot = new FakeRootMethod(util.getClassHierarchy(), util.getOptions(), util.getCache());
        for (Iterator<? extends Entrypoint> it = util.getOptions().getEntrypoints().iterator(); it.hasNext();) {
            Entrypoint e = (Entrypoint) it.next();
            // Add in the fake root method that sets up the call to main
            SSAAbstractInvokeInstruction call = e.addCall(fakeRoot);

            if (call == null) {
                throw new RuntimeException("Missing entry point " + e);
            }
        }
        registrar.setEntryPoint(fakeRoot);
        addFromMethod(q, fakeRoot);
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
                System.out.println("\tAlready added " + PrettyPrinter.parseMethod(m.getReference()));
            }
            return;
        }
        if (m.isNative()) {
            handleNative(q, m);
            return;
        }
        if (m.isAbstract()) {
            System.err.println("No need to analyze abstract methods: " + m.getSignature());
            return;
        }

        visitedMethods.add(m);

        IR ir = util.getCache().getSSACache()
                .findOrCreateIR(m, Everywhere.EVERYWHERE, util.getOptions().getSSAOptions());
        registrar.recordMethod(m, new MethodSummaryNodes(registrar, ir));

        if (VERBOSE >= 1) {
            System.out.println(PrettyPrinter.parseMethod(m.getReference()) + " will be registered.");
        }
        if (VERBOSE >= 2) {
            Writer writer = new StringWriter();
            PrettyPrinter.writeIR(ir, writer, "\t", "\n");
            System.out.print(writer.toString());
            try {
                writer.close();
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
        IClass klass = null;

        // T is a class and an instance of T is created.
        if (i instanceof SSAInvokeInstruction) {
            SSAInvokeInstruction ins = (SSAInvokeInstruction) i;
            if (ins.isSpecial()) {
                IMethod callee = util.getClassHierarchy().resolveMethod(ins.getDeclaredTarget());
                if (callee.isInit()) {
                    klass = callee.getDeclaringClass();
                }
            }
        }

        // T is a class and a static method declared by T is invoked.
        if (i instanceof SSAInvokeInstruction) {
            SSAInvokeInstruction ins = (SSAInvokeInstruction) i;
            if (ins.isStatic()) {
                IMethod callee = util.getClassHierarchy().resolveMethod(ins.getDeclaredTarget());
                if (callee == null) {
                    throw new RuntimeException("Trying to add class initializer and could not resolve "
                            + PrettyPrinter.parseMethod(ins.getDeclaredTarget()));
                }
                klass = callee.getDeclaringClass();
            }
        }

        // A static field declared by T is assigned.
        if (i instanceof SSAPutInstruction) {
            SSAPutInstruction ins = (SSAPutInstruction) i;
            if (ins.isStatic()) {
                IField f = util.getClassHierarchy().resolveField(ins.getDeclaredField());
                if (f == null) {
                    throw new RuntimeException("Trying to add class initializer and could not resolve "
                            + PrettyPrinter.parseType(ins.getDeclaredField().getDeclaringClass()) + "." + ins.getDeclaredField().getName());
                }
                klass = f.getDeclaringClass();
            }
        }

        // A static field declared by T is used
        if (i instanceof SSAGetInstruction) {
            SSAGetInstruction ins = (SSAGetInstruction) i;
            if (ins.isStatic()) {
                IField f = util.getClassHierarchy().resolveField(ins.getDeclaredField());
                if (f == null) {
                    throw new RuntimeException("Trying to add class initializer and could not resolve "
                            + PrettyPrinter.parseType(ins.getDeclaredField().getDeclaringClass()) + "." + ins.getDeclaredField().getName());
                }
                klass = f.getDeclaringClass();
            }
        }

        // Invocation of certain reflective methods in class Class and in
        // package
        // java.lang.reflect also causes class or interface initialization.
        // TODO handle class initializers for reflection

        if (klass == null) {
            return false;
        }
        if (VERBOSE >= 1 && !initializedClasses.contains(klass)) {
            StringBuilder s = new StringBuilder();
            s.append("Adding: " + PrettyPrinter.parseType(klass.getReference()) + " initializer from ");
            s.append(PrettyPrinter.instructionString(ir, i));
            System.err.println(s.toString());
        }
        return addClassInit(q, klass);
    }

    /**
     * Add a class initializers for the given class and its super classes to the
     * work queue (if it has not already been added).
     * 
     * @param q
     *            work queue of instructions
     * @param klass
     *            class to add the initializer for
     * @return true if class init was added
     */
    private boolean addClassInit(WorkQueue<InstrAndCode> q, IClass klass) {

        if (!initializedClasses.contains(klass)) {

            if (!util.getClassHierarchy().isRootClass(klass)) {
                // Need to initialize the super class before initializing klass
                IClass superClass = klass.getSuperclass();
                if (!superClass.isInterface()) {
                    if (VERBOSE >= 1 && !initializedClasses.contains(superClass)) {
                        System.out.println("Adding: " + PrettyPrinter.parseType(superClass.getReference())
                                + " super class initializer from " + PrettyPrinter.parseType(klass.getReference()));
                    }
                    addClassInit(q, superClass);
                }
            }

            initializedClasses.add(klass);
            if (klass.getClassInitializer() != null) {
                registrar.addClassInitializer(klass.getClassInitializer());
                addFromMethod(q, klass.getClassInitializer());
            } else if (VERBOSE >= 2) {
                System.out.println("\tNo class initializer for " + PrettyPrinter.parseType(klass.getReference()));
            }
            return true;
        }
        return false;
    }

    private void handleNative(WorkQueue<InstrAndCode> q, IMethod m) {
        // TODO Statement registration not handling native methods yet
        if (VERBOSE >= 2) {
            System.out.println("\tNot handling native method " + PrettyPrinter.parseMethod(m.getReference()));
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

        // Add any class initializers required before executing this instruction
        addClassInitForInstruction(q, i, ir);

        if (i.getNumberOfDefs() > 2) {
            throw new RuntimeException("More than two defs in instruction: " + i.toString(ir.getSymbolTable()));
        }

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
            registrar.registerGetField((SSAGetInstruction) i, ir, util.getClassHierarchy());
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
                    System.out.println("Adding: " + PrettyPrinter.parseMethod(m.getReference()) + " from "
                            + PrettyPrinter.parseMethod(ir.getMethod().getReference()));
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
            registrar.registerPutField((SSAPutInstruction) i, ir, util.getClassHierarchy());
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
            registrar.registerThrow((SSAThrowInstruction) i, ir);
            return;
        case ARRAY_LENGTH: // primitive return
        case BINARY_OP: // not creating nodes for intermediate results
        case COMPARISON: // primitive op
        case CONDITIONAL_BRANCH: // computes primitive and branches
        case CONVERSION: // primitive op
        case GET_CAUGHT_EXCEPTION: // handled in PointsToStatement#checkThrown
        case GOTO: // control flow
        case INSTANCE_OF: // results in a primitive
        case MONITOR: // no effect on pointers
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
