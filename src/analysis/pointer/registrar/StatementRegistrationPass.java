package analysis.pointer.registrar;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.ClassInitFinder;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.registrar.StatementRegistrar.InstructionInfo;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;

/**
 * Collect pointer analysis constraints with a pass over the code
 */
public class StatementRegistrationPass {

    private final StatementRegistrar registrar;
    private static boolean PROFILE = false;

    /**
     * Create a pass which will generate points-to statements
     */
    public StatementRegistrationPass() {
        registrar = new StatementRegistrar();
    }

    /**
     * Initialize the queue using the defined entry points
     */
    private void init(WorkQueue<InstructionInfo> q) {
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
    private boolean addFromMethod(WorkQueue<InstructionInfo> q, IMethod m) {
        return q.addAll(registrar.getFromMethod(m));
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
    private boolean addClassInitializers(WorkQueue<InstructionInfo> q, List<IMethod> clinits) {
        assert !clinits.isEmpty();
        boolean added = false;
        for (int j = clinits.size() - 1; j >= 0; j--) {
            IMethod clinit = clinits.get(j);
            boolean oneAdded = addFromMethod(q, clinit);
            if (oneAdded && PointsToAnalysis.outputLevel >= 2) {
                System.err.println("Adding: " + PrettyPrinter.typeString(clinit.getDeclaringClass()) + " initializer");
            }
            added |= oneAdded;
        }
        return added;
    }

    /**
     * Run the pass
     */
    public void run() {
        long start = System.currentTimeMillis();
        final WorkQueue<InstructionInfo> q = new WorkQueue<>();
        init(q);

        while (!q.isEmpty()) {
            InstructionInfo info = q.poll();
            SSAInstruction i = info.instruction;
            IR ir = info.ir;

            List<IMethod> inits = ClassInitFinder.getClassInitializers(i);
            if (!inits.isEmpty()) {
                addClassInitializers(q, inits);
            }

            if (i instanceof SSAInvokeInstruction) {
                // This is an invocation, add statements for callee to work queue
                SSAInvokeInstruction inv = (SSAInvokeInstruction) i;
                Set<IMethod> targets = StatementRegistrar.resolveMethodsForInvocation(inv);
                for (IMethod m : targets) {
                    if (PointsToAnalysis.outputLevel >= 2) {
                        System.err.println("Adding: " + PrettyPrinter.methodString(m) + " from "
                                                        + PrettyPrinter.methodString(ir.getMethod()));
                    }
                    addFromMethod(q, m);
                }
            }

            registrar.handle(info);
        }
        if (PointsToAnalysis.outputLevel >= 1) {
            System.err.println("Registered " + registrar.getAllStatements().size() + " statements.");
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
        return registrar;
    }

    public ReferenceVariableCache getAllLocals() {
        return registrar.getAllLocals();
    }
}
