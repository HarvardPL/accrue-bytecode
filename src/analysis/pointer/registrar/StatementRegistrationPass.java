package analysis.pointer.registrar;

import java.io.IOException;
import java.lang.management.ManagementFactory;
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
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * Collect pointer analysis constraints with a pass over the code
 */
public class StatementRegistrationPass {

    private final StatementRegistrar registrar;
    private static boolean PROFILE = false;

    /**
     * Create a pass which will generate points-to statements
     *
     * @param factory factory used to create points-to statements
     * 
     * @param useSingleAllocForGenEx If true then only one allocation will be made for each generated exception type.
     *            This will reduce the size of the points-to graph (and speed up the points-to analysis), but result in
     *            a loss of precision for such exceptions.
     * @param useSingleAllocForThrowable If true then only one allocation will be made for each type of throwable. This
     *            will reduce the size of the points-to graph (and speed up the points-to analysis), but result in a
     *            loss of precision for throwables.
     * @param useSingleAllocForPrimitiveArrays If true then only one allocation will be made for any kind of primitive
     *            array. Reduces precision, but improves performance.
     * @param useSingleAllocForStrings If true then only one allocation will be made for any string. This will reduce
     *            the size of the points-to graph (and speed up the points-to analysis), but result in a loss of
     *            precision for strings.
     */
    public StatementRegistrationPass(StatementFactory factory, boolean useSingleAllocForGenEx,
                                     boolean useSingleAllocForThrowable, boolean useSingleAllocForPrimitiveArrays,
                                     boolean useSingleAllocForStrings) {
        registrar = new StatementRegistrar(factory,
                                           useSingleAllocForGenEx,
                                           useSingleAllocForThrowable,
                                           useSingleAllocForPrimitiveArrays,
                                           useSingleAllocForStrings);
    }

    /**
     * Initialize the queue using the defined entry points
     */
    private static void init(WorkQueue<IMethod> q) {
        q.add(AnalysisUtil.getFakeRoot());
    }

    /**
     * Add statements given class initializers
     *
     * @param trigger instruction that triggered the clinit
     * @param containingCode code containing the instruction that triggered the clinit
     * @param clinits class initialization methods that might need to be called in the order they need to be called
     *            (i.e. element j is a super class of element j+1)
     * @return true if any new class initializer was seen
     */
    private static boolean addClassInitializers(WorkQueue<IMethod> q, List<IMethod> clinits) {
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

        Set<MethodReference> alreadyDispatched = new HashSet<>();

        init(q);

        while (!q.isEmpty()) {
            IMethod m = q.poll();

            // Register all the instructions in the method.
            if (!registrar.registerMethod(m)) {
                continue;
            }

            if (m.isInit()) {
                // it is an instance initialization method!
                processInstanceClass(seenInstancesOf, m.getDeclaringClass(), waitingForInstances, q);
            }

            // now also go through each instruction, and see if we need to add anything else to the
            // workqueue
            IR ir = AnalysisUtil.getIR(m);
            if (ir == null) {
                // Native method with no signature.

                // Assume that the return object was constructed by the method (and thus methods can be called on the return type)
                if (!m.getReturnType().isPrimitiveType()) {
                    IClass retType = AnalysisUtil.getClassHierarchy().lookupClass(m.getReturnType());
                    processInstanceClass(seenInstancesOf, retType, waitingForInstances, q);
                }

                // Also assume that the exception object was constructed by the method
                try {
                    TypeReference[] exceptions = m.getDeclaredExceptions();
                    if (exceptions != null) {
                        for (TypeReference exType : exceptions) {
                            // Record the "initialization" of the exception type
                            IClass exClass = AnalysisUtil.getClassHierarchy().lookupClass(exType);
                            processInstanceClass(seenInstancesOf, exClass, waitingForInstances, q);
                        }
                    }
                }
                catch (UnsupportedOperationException | InvalidClassFileException e) {
                    throw new RuntimeException(e);
                }

                // There are no instructions to process.
                continue;
            }

            // Process all instructions looking for methods that have not yet been handled
            for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
                for (SSAInstruction i : bb) {
                    List<IMethod> inits = ClassInitFinder.getClassInitializers(i);
                    if (!inits.isEmpty()) {
                        addClassInitializers(q, inits);
                    }

                    if (!(i instanceof SSAInvokeInstruction)) {
                        // This loop only processes invocations to add statements for new methods.
                        continue;
                    }

                    // This is an invocation, add statements for callee to work queue
                    SSAInvokeInstruction inv = (SSAInvokeInstruction) i;

                    if (!alreadyDispatched.add(inv.getDeclaredTarget())) {
                        // we have seen this declared target before, no need to process again
                        continue;
                    }

                    Set<IMethod> targets = StatementRegistrar.resolveMethodsForInvocation(inv);
                    if (inv.isSpecial() || inv.isStatic()) {
                        // it is a special or a static method, so add the target(s) to the queue
                        q.addAll(targets);
                    }
                    else {
                        // only add the targets for which we have seen an instance of the declaring class.
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

        System.err.println("Statement registration took " + (System.currentTimeMillis() - start) + "ms");
        System.err.println("USED " + (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1000000)
                + "Mb");
        if (PROFILE) {
            System.err.println("PAUSED HIT ENTER TO CONTINUE: ");
            try {
                System.in.read();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * When encountering a virtual method call we only want to add statements for the bodies of methods that the type
     * system allows. We approximate this by assuming that receiver of the method can be any type that is constructed in
     * the code. This could be made more precise if run together with the pointer analysis (supported by Accrue as the
     * online statement registration) when we have more precise type information for the receiver.
     *
     * @param seenInstancesOf set of classes that have already been seen in the code
     * @param instanceClass current class to process
     * @param waitingForInstances Map from classes to methods that need to be processed if that class is instantiated
     * @param q work queue of methods to register statements for
     */
    public static void processInstanceClass(Set<IClass> seenInstancesOf, IClass instanceClass,
                                            Map<IClass, Collection<IMethod>> waitingForInstances, WorkQueue<IMethod> q) {
        if (seenInstancesOf.add(instanceClass)) {
            // this is the first instance method we have seen for this class
            // Add any methods that were waiting on registration.
            Collection<IMethod> waiting = waitingForInstances.remove(instanceClass);
            if (waiting != null) {
                q.addAll(waiting);
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
