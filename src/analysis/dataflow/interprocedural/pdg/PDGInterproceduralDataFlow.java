package analysis.dataflow.interprocedural.pdg;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.interprocedural.pdg.graph.PDGEdgeType;
import analysis.dataflow.interprocedural.pdg.graph.ProgramDependenceGraph;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.ProcedureSummaryNodes;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.dataflow.util.Unit;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;

public class PDGInterproceduralDataFlow extends InterproceduralDataFlow<Unit> {

    private final ProgramDependenceGraph pdg;
    private final PreciseExceptionResults preciseEx;
    private final WalaAnalysisUtil util;
    private static final Map<ExitType, Unit> UNIT_MAP = new HashMap<>();
    static {
        UNIT_MAP.put(ExitType.EXCEPTIONAL, Unit.VALUE);
        UNIT_MAP.put(ExitType.NORMAL, Unit.VALUE);
    }
    private final Map<CGNode, ProcedureSummaryNodes> summaries = new LinkedHashMap<>();
    
    public PDGInterproceduralDataFlow(PointsToGraph ptg, PreciseExceptionResults preciseEx,
                                    ReachabilityResults reachable, WalaAnalysisUtil util) {
        super(ptg, reachable);
        this.pdg = new ProgramDependenceGraph();
        this.preciseEx = preciseEx;
        this.util = util;
    }

    @Override
    protected String getAnalysisName() {
        return "PDG data-flow";
    }

    @Override
    protected Map<ExitType, Unit> analyze(CGNode n, Unit input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING:\n\t" + PrettyPrinter.parseCGNode(n));
        }
        PDGNodeDataflow df = new PDGNodeDataflow(n, this, getProcedureSummary(n), util);
        df.dataflow();
        return UNIT_MAP;
    }

    @Override
    protected Map<ExitType, Unit> analyzeNative(CGNode n, Unit input) {
        // Create edges from the input context and formals to the output context
        // This is unsound if the method has heap side effects, but is sound if
        // it doesn't
        ProcedureSummaryNodes summary = getProcedureSummary(n);
        PDGContext entry = summary.getEntryContext();
        PDGContext normExit = summary.getNormalExitContext();
        PDGContext exExit = summary.getExceptionalExitContext();

        pdg.addEdge(entry.getPCNode(), normExit.getPCNode(), PDGEdgeType.CONJUNCTION);
        pdg.addEdge(entry.getPCNode(), exExit.getPCNode(), PDGEdgeType.CONJUNCTION);

        for (PDGNode formal : summary.getFormals()) {
            pdg.addEdge(formal, normExit.getReturnNode(), PDGEdgeType.MISSING);
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
                    AnalysisRecord<Unit> res = new AnalysisRecord<Unit>(getInitialContext(succ), null, true);
                    recordedResults.put(succ, res);

                    // Add to the inialization work-queue
                    initializationQ.add(succ);
                }
            }
        }

        super.preAnalysis(cg, q);
    }

    private Unit getInitialContext(CGNode succ) {
        return Unit.VALUE;
    }

    @Override
    protected void postAnalysis() {
        super.postAnalysis();
    }

    public PreciseExceptionResults getPreciseExceptionResults() {
        return preciseEx;
    }

    @Override
    public Map<ExitType, Unit> getResults(CGNode caller, CGNode callee, Unit input) {
        return UNIT_MAP;
    }

    @Override
    public ProgramDependenceGraph getAnalysisResults() {
        return pdg;
    }

    public ProcedureSummaryNodes getProcedureSummary(CGNode n) {
        ProcedureSummaryNodes summary = summaries.get(n);
        if (summary == null) {
            summary = new ProcedureSummaryNodes(n);
            summaries.put(n, summary);
        }
        return summary;
    }
}
