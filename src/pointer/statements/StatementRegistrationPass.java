package pointer.statements;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import util.WorkQueue;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.impl.FakeRootMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;

/**
 * Collect pointer analysis constraints with a pass over the code
 * 
 * @author ajohnson
 */
public class StatementRegistrationPass {

    /**
     * Cache containing and managing SSA IR we are analyzing
     */
    private final AnalysisCache cache;
    /**
     * Options for the analysis (e.g. entry points)
     */
    private final AnalysisOptions options;
    /**
     * Class hierarchy for the code being analyzed
     */
    private final IClassHierarchy cha;
    /**
     * Container and manager of points-to statements
     */
    private final StatementRegistrar registrar;
    /**
     * Methods we have already added statements for
     */
    private final Set<IMethod> visitedMethods = new LinkedHashSet<>();

    /**
     * Create a pass which will generate points-to statements
     * 
     * @param cha
     *            class hierarchy
     * @param cache
     *            contains the SSA IR
     * @param options
     *            entry points and other options
     */
    public StatementRegistrationPass(IClassHierarchy cha, AnalysisCache cache, AnalysisOptions options) {
        this.options = options;
        this.cache = cache;
        this.cha = cha;
        registrar = new StatementRegistrar();
    }

    /**
     * Initialize the queue using the defined entry points
     */
    private void init(WorkQueue<InstrAndCode> q) {

        // Set up the entry points
        FakeRootMethod fakeMethodRoot = new FakeRootMethod(cha, options, cache);
        for (Iterator<? extends Entrypoint> it = options.getEntrypoints().iterator(); it.hasNext();) {
            Entrypoint e = (Entrypoint) it.next();
            // Add in the fake root method that sets up the call to main
            SSAAbstractInvokeInstruction call = e.addCall(fakeMethodRoot);

            if (call == null) {
                throw new RuntimeException("Missing entry point " + e);
            }
            
            addFromMethod(q, e.getMethod());
        }
        registrar.setEntryPoint(FakeRootMethod.rootMethod);
    }

    /**
     * Add instructions to the work queue for the given method, if this method
     * has not already been processed.
     * 
     * @param m
     *            method to process
     */
    private void addFromMethod(WorkQueue<InstrAndCode> q, IMethod m) {
        
        if (visitedMethods.contains(m)) {
            return;
        }
        visitedMethods.add(m);
        IR ir = cache.getSSACache().findOrCreateIR(m, Everywhere.EVERYWHERE, options.getSSAOptions());
        registrar.recordMethod( m.getReference(), new MethodSummaryNodes(registrar, ir));
        
        // TODO make sure that catch instructions end up getting added
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            for (SSAInstruction ins : bb) {
                q.add(new InstrAndCode(ins, ir));
            }
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

        if (i.getNumberOfDefs() > 1) {
            throw new RuntimeException("More than one def in instruction: " + i.toString(ir.getSymbolTable()));
        }

        // procedure calls, class init, instance init, constructor calls
        if (i instanceof SSAInvokeInstruction) {
            SSAInvokeInstruction inv = (SSAInvokeInstruction) i;
            Set<IMethod> targets = cha.getPossibleTargets(inv.getDeclaredTarget());
            for (IMethod m : targets) {
                addFromMethod(q, m);
            }
            registrar.handleInvoke(inv, ir);
            return;
        }

        // v = new Foo();
        if (i instanceof SSANewInstruction) {
            registrar.handleNew((SSANewInstruction) i, ir);
            return;
        }

        // v = o.f
        if (i instanceof SSAGetInstruction) {
            registrar.handleFieldAccess((SSAGetInstruction) i, ir);
            return;
        }

        // o.f = v
        if (i instanceof SSAPutInstruction) {
            registrar.handleFieldAssign((SSAPutInstruction) i, ir);
            return;
        }

        // assignment into catch block exception variable
        if (i instanceof SSAGetCaughtExceptionInstruction) {
            registrar.handleCatchAssignment((SSAGetCaughtExceptionInstruction) i, ir);
            return;
        }

        // v = phi(x_1,x_2)
        if (i instanceof SSAPhiInstruction) {
            registrar.handlePhiAssignment((SSAPhiInstruction) i, ir);
            return;
        }

        // v = (Type) x
        if (i instanceof SSACheckCastInstruction) {
            registrar.handleCheckCast((SSACheckCastInstruction) i, ir);
            return;
        }

        // v[i] = x
        if (i instanceof SSAArrayStoreInstruction) {
            registrar.handleArrayStore((SSAArrayStoreInstruction) i, ir);
            return;
        }
        // x = v[i]
        if (i instanceof SSAArrayLoadInstruction) {
            registrar.handleArrayLoad((SSAArrayLoadInstruction) i, ir);
            return;
        }
        
        // return v
        if (i instanceof SSAReturnInstruction) {
            registrar.handleReturn((SSAReturnInstruction) i,ir);
            return;
        }

        System.err.println(i.toString() + " not handled");
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
}
