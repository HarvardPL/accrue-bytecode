package analysis.pointer.registrar;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.ClassInitFinder;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.registrar.RegistrationUtil.InstrAndCode;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;

/**
 * Collect pointer analysis constraints with a pass over the code
 */
public class StatementRegistrationPass {

    private final RegistrationUtil registration;
    public static int outputLevel = 0;
    private static boolean PROFILE = false;

    /**
     * Create a pass which will generate points-to statements
     */
    public StatementRegistrationPass() {
        registration = new RegistrationUtil();
        RegistrationUtil.outputLevel = outputLevel;
    }

    /**
     * Initialize the queue using the defined entry points
     */
    private void init(WorkQueue<InstrAndCode> q) {
        registration.getRegistrar().setEntryPoint(AnalysisUtil.getFakeRoot());
        addFromMethod(q, AnalysisUtil.getFakeRoot());
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
        return q.addAll(registration.getFromMethod(m));
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
        assert !clinits.isEmpty();
        boolean added = false;
        for (int j = clinits.size() - 1; j >= 0; j--) {
            IMethod clinit = clinits.get(j);
            boolean oneAdded = addFromMethod(q, clinit);
            if (oneAdded && RegistrationUtil.outputLevel >= 2) {
                System.err.println("Adding: " + PrettyPrinter.typeString(clinit.getDeclaringClass().getReference())
                                                + " initializer");
            }
            added |= oneAdded;
        }
        registration.getRegistrar().addStatementsForClassInitializer(trigger, containingCode, clinits);
        return added;
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
            SSAInstruction i = info.instruction;
            IR ir = info.ir;

            // Add any class initializers required before executing this instruction
            // TODO can be even more precise if we add a LoadClassStatement for each
            // possible clinit, add that to the list of statements for the method
            // containing the instruction that could load, and then make sure to
            // only handle each one once in the pointer analysis
            List<IMethod> inits = ClassInitFinder.getClassInitializers(i);
            if (!inits.isEmpty()) {
                addClassInitializers(i, ir, q, inits);
            }

            if (i instanceof SSAInvokeInstruction) {
                // This is an invocation, add statements for callee to work queue
                SSAInvokeInstruction inv = (SSAInvokeInstruction) i;
                Set<IMethod> targets = StatementRegistrar.resolveMethodsForInvocation(inv);
                for (IMethod m : targets) {
                    if (outputLevel >= 2) {
                        System.err.println("Adding: " + PrettyPrinter.methodString(m) + " from "
                                                        + PrettyPrinter.methodString(ir.getMethod()) + " for "
                                                        + PrettyPrinter.instructionString(inv, ir));
                    }
                    addFromMethod(q, m);
                }
            }

            registration.handle(info);
        }
        if (outputLevel >= 1) {
            System.err.println("Registered " + registration.getRegistrar().getAllStatements().size() + " statements.");
            System.err.println("It took " + (System.currentTimeMillis() - start) + "ms");
        }
        if (PROFILE) {
            System.err.println("PAUSED HIT ENTER TO CONTINUE: ");
            try {
                System.in.read();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public StatementRegistrar getRegistrar() {
        return registration.getRegistrar();
    }

    public ReferenceVariableCache getAllLocals() {
        return registration.getAllLocals();
    }
}
