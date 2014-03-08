package pointer;

import java.util.Iterator;

import pointer.analyses.HeapAbstractionFactory;
import pointer.statements.PointsToStatement;
import pointer.statements.StatementRegistrar;
import util.WorkQueue;

import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.impl.ExplicitCallGraph;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;

/**
 * Call graph builder based on the pointer analysis factoring of "Pick Your
 * Contexts Well: Understanding Object-Sensitivity" by Smaragdakis, Bravenboer,
 * and Lhotak, POPL 2011.
 * 
 * @author ajohnson
 */
public class AccrueCallGraphBuilder {



    private ExplicitCallGraph callGraph;
    private final AnalysisCache cache;
    private final IClassHierarchy cha;
    private final HeapAbstractionFactory haf;
    private final WorkQueue<StmtAndContext> q = new WorkQueue<>();
    private final StatementRegistrar registrar;
    private final PointsToGraph g;

    public AccrueCallGraphBuilder(IClassHierarchy cha, AnalysisCache cache, HeapAbstractionFactory haf, StatementRegistrar registrar) {
        this.cha = cha;
        this.cache = cache;
        this.haf = haf;
        this.registrar = registrar;
        this.g = new PointsToGraph(cha);
    }

    public CallGraph makeCallGraph(AnalysisOptions options, IProgressMonitor monitor) throws IllegalArgumentException,
            CallGraphBuilderCancelException {
        long start = System.currentTimeMillis();
        callGraph = new ExplicitCallGraph(cha, options, cache);
        
        init(options);
        solve();

        System.err.println("Call graph construction took " + (System.currentTimeMillis() - start) + "ms");
        return callGraph;
    }

    public void init(AnalysisOptions options) {

        try {
            callGraph.init();
        } catch (CancelException e1) {
            throw new RuntimeException("Call graph initialization failed.");
        }

//        // Set up the entry points
//        for (Iterator<? extends Entrypoint> it = options.getEntrypoints().iterator(); it.hasNext();) {
//            Entrypoint e = (Entrypoint) it.next();
//            // Add in the fake root method that sets up the call to main
//            SSAAbstractInvokeInstruction call = e.addCall((AbstractRootMethod) callGraph.getFakeRootNode().getMethod());
//
//            if (call == null) {
//                throw new RuntimeException("Missing entry point " + e);
//            }
//            IR ir = cache.getSSACache().findOrCreateIR(e.getMethod(), Everywhere.EVERYWHERE, options.getSSAOptions());
//
//            // TODO make sure that catch instructions end up getting added
//            for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
//                for (SSAInstruction ins : bb) {
//                    q.add(new StmtAndContext(registrar.get(ins, ir), haf.initialContext()));
//                }
//            }
//        }
    }

    /**
     * A simple and inefficient solution technique: iterate through all
     * statements until no more changes
     */
    private PointsToGraph solve() {

        while (!q.isEmpty()) {
            StmtAndContext sac = q.poll();
            sac.stmt.process(sac.context, haf, g);
        }

        return g;
    }
    
    public PointsToGraph getGraph() {
        return g;
    }

    private static class StmtAndContext {

        final PointsToStatement stmt;
        final Context context;
        
        public StmtAndContext(PointsToStatement stmt, Context context) {
            this.stmt = stmt;
            this.context = context;
        }

    }
}
