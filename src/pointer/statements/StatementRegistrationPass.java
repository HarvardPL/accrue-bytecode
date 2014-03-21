package pointer.statements;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import pointer.graph.MethodSummaryNodes;
import util.PrettyPrinter;
import util.WorkQueue;
import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.impl.FakeRootMethod;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAComparisonInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAConversionInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;

/**
 * Collect pointer analysis constraints with a pass over the code TODO should
 * this implement IVisitor? I think not since we also need the IR
 */
public class StatementRegistrationPass {

    /**
     * Verbosity level, 2 prints all method bodies
     */
    private static int VERBOSE = 0;
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
    private final Set<IClass> visitedClasses = new LinkedHashSet<>();
    private final AnalysisUtil util;

    /**
     * Create a pass which will generate points-to statements
     * 
     * @param util
     *            utility class containing WALA classes needed by this analyssi
     */
    public StatementRegistrationPass(AnalysisUtil util) {
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
        addFromMethod(q, fakeRoot, false);
    }

    /**
     * Add instructions to the work queue for the given method, if this method
     * has not already been processed.
     * 
     * @param m
     *            method to process
     */
    private void addFromMethod(WorkQueue<InstrAndCode> q, IMethod m, boolean addClassInit) {

        if (visitedMethods.contains(m)) {
            return;
        }
        if (m.isNative()) {
            handleNative(q, m, addClassInit);
            return;
        }
        if (m.isAbstract()) {
            System.err.println("No need to analyze abstract methods: " + m.getSignature());
            return;
        }

        // If we haven't analyzed the <clinit> method for the receiver class do
        // it now
        IClass declaringClass = m.getDeclaringClass();
        if (addClassInit && !visitedClasses.contains(declaringClass)) {
            visitedClasses.add(declaringClass);
            if (declaringClass.getClassInitializer() != null) {
                registrar.addClassInitializer(declaringClass.getClassInitializer());
                addFromMethod(q, declaringClass.getClassInitializer(), true);
            }
        }

        visitedMethods.add(m);

        IR ir = util.getCache().getSSACache()
                .findOrCreateIR(m, Everywhere.EVERYWHERE, util.getOptions().getSSAOptions());
        registrar.recordMethod(m, new MethodSummaryNodes(registrar, ir));

        if (VERBOSE >= 2) {
            PrettyPrinter.printIR("\t", ir);
        }

        // TODO make sure that catch instructions end up getting added
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            for (SSAInstruction ins : bb) {
                q.add(new InstrAndCode(ins, ir));
            }
        }
    }

    private void handleNative(WorkQueue<InstrAndCode> q, IMethod m, boolean addClassInit) {
        // TODO Statement registration not handling native methods yet
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

        if (i.getNumberOfDefs() > 2) {
            throw new RuntimeException("More than two defs in instruction: " + i.toString(ir.getSymbolTable()));
        }

        // procedure calls, instance initializers, constructor invocation
        if (i instanceof SSAInvokeInstruction) {
            SSAInvokeInstruction inv = (SSAInvokeInstruction) i;

            Set<IMethod> targets = StatementRegistrar.resolveMethodsForInvocation(inv, util.getClassHierarchy());
            for (IMethod m : targets) {
                if (VERBOSE >= 1) {
                    System.err.println("Adding: " + PrettyPrinter.parseMethod(m.getReference()) + " from "
                            + PrettyPrinter.parseMethod(ir.getMethod().getReference()));
                }
                addFromMethod(q, m, true);
            }
            registrar.registerInvoke(inv, ir, util.getClassHierarchy());
            return;
        }

        if (i.getNumberOfDefs() > 1) {
            throw new RuntimeException("More than one defs in instruction: " + i.toString(ir.getSymbolTable()));
        }

        // v = new Foo();
        if (i instanceof SSANewInstruction) {
            registrar.registerNew((SSANewInstruction) i, ir, util.getClassHierarchy());
            return;
        }

        // v = o.f
        if (i instanceof SSAGetInstruction) {
            registrar.registerFieldAccess((SSAGetInstruction) i, ir);
            return;
        }

        // o.f = v
        if (i instanceof SSAPutInstruction) {
            registrar.registerFieldAssign((SSAPutInstruction) i, ir);
            return;
        }

        // v = phi(x_1,x_2)
        if (i instanceof SSAPhiInstruction) {
            registrar.registerPhiAssignment((SSAPhiInstruction) i, ir);
            return;
        }

        // v = (Type) x
        if (i instanceof SSACheckCastInstruction) {
            registrar.registerCheckCast((SSACheckCastInstruction) i, ir);
            return;
        }

        // v[i] = x
        if (i instanceof SSAArrayStoreInstruction) {
            registrar.registerArrayStore((SSAArrayStoreInstruction) i, ir);
            return;
        }
        // x = v[i]
        if (i instanceof SSAArrayLoadInstruction) {
            registrar.registerArrayLoad((SSAArrayLoadInstruction) i, ir);
            return;
        }

        // return v
        if (i instanceof SSAReturnInstruction) {
            registrar.registerReturn((SSAReturnInstruction) i, ir);
            return;
        }

        // throw e
        if (i instanceof SSAThrowInstruction) {
            registrar.registerThrow((SSAThrowInstruction) i, ir);
            return;
        }

        // Reflection
        if (i instanceof SSALoadMetadataInstruction) {
            registrar.registerReflection((SSALoadMetadataInstruction) i, ir);
            return;
        }
        checkUnhandledInstruction(i, ir);
    }

    /**
     * Check whether the given instruction is one that does not affect pointer
     * analysis results
     */
    private void checkUnhandledInstruction(SSAInstruction i, IR ir) {
        String className = i.getClass().isAnonymousClass() ? i.getClass().getSuperclass().getSimpleName() : i
                .getClass().getSimpleName();
        if (VERBOSE >= 2) {
            i.visit(PrettyPrinter.getPrinter(ir));
            System.out.println(" not handled. " + "(" + className + ")");
        }
        assert i instanceof SSAArrayLengthInstruction || i instanceof SSAGotoInstruction
                || i instanceof SSAConditionalBranchInstruction || i instanceof SSABinaryOpInstruction
                || i instanceof SSAConversionInstruction || i instanceof SSAInstanceofInstruction
                || i instanceof SSAGetCaughtExceptionInstruction
                || (i instanceof SSAUnaryOpInstruction && !(i instanceof SSAPiInstruction))
                || i instanceof SSAComparisonInstruction : "Instructions of type " + className
                + " not handled in Statement registration pass.";
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
