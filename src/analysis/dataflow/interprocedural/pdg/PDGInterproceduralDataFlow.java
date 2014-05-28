package analysis.dataflow.interprocedural.pdg;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.interprocedural.pdg.graph.PDGEdgeType;
import analysis.dataflow.interprocedural.pdg.graph.ProgramDependenceGraph;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeFactory;
import analysis.dataflow.interprocedural.pdg.graph.node.ProcedureSummaryPDGNodes;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.dataflow.util.Unit;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableCache;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.types.TypeReference;

public class PDGInterproceduralDataFlow extends InterproceduralDataFlow<Unit> {

    private final ProgramDependenceGraph pdg;
    private final PreciseExceptionResults preciseEx;
    private static final Map<ExitType, Unit> UNIT_MAP = new HashMap<>();
    static {
        UNIT_MAP.put(ExitType.EXCEPTIONAL, Unit.VALUE);
        UNIT_MAP.put(ExitType.NORMAL, Unit.VALUE);
    }

    public PDGInterproceduralDataFlow(PointsToGraph ptg, PreciseExceptionResults preciseEx,
                                    ReachabilityResults reachable, ReferenceVariableCache rvCache) {
        super(ptg, reachable, rvCache);
        this.pdg = new ProgramDependenceGraph();
        this.preciseEx = preciseEx;
    }

    @Override
    protected String getAnalysisName() {
        return "PDG data-flow";
    }

    @Override
    protected Map<ExitType, Unit> analyze(CGNode n, Unit input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING:\n\t" + PrettyPrinter.cgNodeString(n));
        }
        ComputePDGNodesDataflow df = new ComputePDGNodesDataflow(n, this);
        df.setOutputLevel(getOutputLevel());
        df.dataflow();
        return UNIT_MAP;
    }

    @Override
    protected Map<ExitType, Unit> analyzeNative(CGNode n, Unit input) {
        // Create edges from the input context and formals to the output context
        // This is unsound if the method has heap side effects, but is sound if
        // it doesn't
        ProcedureSummaryPDGNodes summary = PDGNodeFactory.findOrCreateProcedureSummary(n);
        PDGContext entry = summary.getEntryContext();
        PDGContext normExit = summary.getNormalExitContext();
        PDGContext exExit = summary.getExceptionalExitContext();

        pdg.addEdge(entry.getPCNode(), normExit.getPCNode(), PDGEdgeType.CONJUNCTION);
        pdg.addEdge(entry.getPCNode(), exExit.getPCNode(), PDGEdgeType.CONJUNCTION);

        for (PDGNode formal : summary.getFormals()) {
            if (!n.getMethod().getReturnType().equals(TypeReference.Void)) {
                pdg.addEdge(formal, normExit.getReturnNode(), PDGEdgeType.MISSING);
            }
            pdg.addEdge(formal, exExit.getExceptionNode(), PDGEdgeType.MISSING);
        }

        return UNIT_MAP;
    }

    @Override
    protected Map<ExitType, Unit> getDefaultOutput(Unit input) {
        return UNIT_MAP;
    }

    @Override
    protected Unit getInputForEntryPoint() {
        return Unit.VALUE;
    }

    @Override
    protected boolean outputChanged(Map<ExitType, Unit> previousOutput, Map<ExitType, Unit> currentOutput) {
        return previousOutput == null;
    }

    @Override
    protected boolean existingResultSuitable(Unit newInput, AnalysisRecord<Unit> existingResults) {
        return existingResults.getOutput() != null;
    }

    @Override
    protected void preAnalysis(CallGraph cg, WorkQueue<CGNode> q) {
        // Add all call graph nodes reachable from an entry point
        Collection<CGNode> entryPoints = cg.getEntrypointNodes();

        // These are the class initializers
        q.addAll(entryPoints);
        // Also add the fake root method (which calls main)
        q.add(cg.getFakeRootNode());

        // Queue used to initialize the data-flow work-queue
        WorkQueue<CGNode> initializationQ = new WorkQueue<>();

        initializationQ.addAll(q);
        while (!initializationQ.isEmpty()) {
            CGNode n = initializationQ.poll();
            Iterator<CGNode> iter = cg.getSuccNodes(n);
            while (iter.hasNext()) {
                CGNode succ = iter.next();
                if (!q.contains(succ)) {
                    // TODO only add reachable calls
                    // most should be reachable

                    // Add to the data-flow work-queue
                    q.add(succ);

                    // Set up initial record
                    AnalysisRecord<Unit> res = new AnalysisRecord<>(Unit.VALUE, null, true);
                    recordedResults.put(succ, res);

                    // Add to the inialization work-queue
                    initializationQ.add(succ);
                }
            }
        }

        super.preAnalysis(cg, q);
    }

    @Override
    protected void postAnalysis() {
        super.postAnalysis();
    }

    public PreciseExceptionResults getPreciseExceptionResults() {
        return preciseEx;
    }

    /**
     * Check whether an edge is reachable in the given call graph node
     * 
     * @param source
     *            edge source
     * @param target
     *            edge target
     * @param currentNode
     *            call graph node representing the method and context for the edge
     * @return true if the given edge is unreachable
     */
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target, CGNode currentNode) {
        // Check whether this is an exception edge with no exceptions
        boolean unreachableExEdge = false;
        SSACFG cfg = currentNode.getIR().getControlFlowGraph();
        PreciseExceptionResults pe = getPreciseExceptionResults();
        if (cfg.getExceptionalSuccessors(source).contains(target)) {
            unreachableExEdge = pe.getExceptions(source, target, currentNode).isEmpty();
        }

        return unreachableExEdge || getReachabilityResults().isUnreachable(source, target, currentNode);
    }

    /**
     * Check whether the basic block can terminate normally (on at least one successor edge).
     * 
     * @param bb
     *            basic block to check
     * @param n
     *            call graph node containing the method and context for the basic block
     * @return whether the basic block has a reachable normal successor
     */
    protected boolean canTerminateNormally(ISSABasicBlock bb, CGNode n) {
        for (ISSABasicBlock succ : n.getIR().getControlFlowGraph().getNormalSuccessors(bb)) {
            if (!isUnreachable(bb, succ, n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether the procedure can terminate normally (in a particular context).
     * 
     * @param n
     *            call graph node containing the method and context
     * @return whether the method can terminate normally in the given context
     */
    protected boolean canProcedureTerminateNormally(CGNode n) {
        if (n.getMethod().isNative()) {
            // assume native methods can terminate normally
            return true;
        }

        SSACFG cfg = n.getIR().getControlFlowGraph();
        ISSABasicBlock exit = cfg.exit();

        for (ISSABasicBlock pred : cfg.getNormalPredecessors(exit)) {
            if (!isUnreachable(pred, exit, n)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<ExitType, Unit> getResults(CGNode caller, CGNode callee, Unit input) {
        return UNIT_MAP;
    }

    @Override
    public ProgramDependenceGraph getAnalysisResults() {
        return pdg;
    }
}
