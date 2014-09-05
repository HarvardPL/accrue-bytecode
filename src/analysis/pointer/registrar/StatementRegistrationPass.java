package analysis.pointer.registrar;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.ClassInitFinder;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.statements.StatementFactory;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
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
     *
     * @param factory
     */
    public StatementRegistrationPass(StatementFactory factory) {
        registrar = new StatementRegistrar(factory);
    }

    /**
     * Initialize the queue using the defined entry points
     */
    private void init(WorkQueue<IMethod> q) {
        q.add(AnalysisUtil.getFakeRoot());
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
    private boolean addClassInitializers(WorkQueue<IMethod> q, List<IMethod> clinits) {
        assert !clinits.isEmpty();
        boolean added = false;
        for (int j = clinits.size() - 1; j >= 0; j--) {
            IMethod clinit = clinits.get(j);
            boolean oneAdded = q.add(clinit);
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
        final WorkQueue<IMethod> q = new WorkQueue<>();

        // the classes for which we have registered an instance methods.
        // These are the classes that might have instances when we execute
        Set<IClass> seenInstancesOf = new HashSet<>();
        Map<IClass, Collection<IMethod>> waitingForInstances = new HashMap<>();

        init(q);

        while (!q.isEmpty()) {
            IMethod m = q.poll();

            // Register all the instructions in the method.
            registrar.registerMethod(m);

            if (!m.isStatic()) {
                // it is an instance method!
                if (seenInstancesOf.add(m.getDeclaringClass())) {
                    // this is the first instance method we have seen for this class
                    // Add any methods that were waiting on registration.
                    Collection<IMethod> waiting = waitingForInstances.remove(m.getDeclaringClass());
                    if (waiting != null) {
                        q.addAll(waiting);
                    }
                }
            }

            // now also go through each instruction, and see if we need to add anything else to the
            // workqueue
            IR ir = AnalysisUtil.getIR(m);
            if (ir != null) {
                for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
                    for (SSAInstruction i : bb) {
                        List<IMethod> inits = ClassInitFinder.getClassInitializers(i);
                        if (!inits.isEmpty()) {
                            addClassInitializers(q, inits);
                        }

                        if (i instanceof SSAInvokeInstruction) {
                            // This is an invocation, add statements for callee to work queue
                            SSAInvokeInstruction inv = (SSAInvokeInstruction) i;
                            Set<IMethod> targets = StatementRegistrar.resolveMethodsForInvocation(inv);
                            if (inv.isSpecial() || inv.isStatic()) {
                                // it is a special or a static method, so add the target(s) to the queue
                                q.addAll(targets);
                            }
                            else {
                                // only add the ones for which we have seen an instance of the delcaring class.
                                for (IMethod target : targets) {
                                    assert !target.isStatic() && !target.isPrivate();
                                    IClass container = target.getDeclaringClass();
                                    if (seenInstancesOf.contains(container)) {
                                        q.add(target);
                                    }
                                    else {
                                        // haven't seen an instance yet...
                                        Collection<IMethod> c = waitingForInstances.get(container);
                                        if (c == null) {
                                            c = new HashSet<>();
                                            waitingForInstances.put(container, c);
                                        }
                                        c.add(target);
                                    }
                                }

                            }
                        }

                    }
                }
            }
        }
        if (PointsToAnalysis.outputLevel >= 1) {
            System.err.println("Registered " + registrar.size() + " statements.");
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
