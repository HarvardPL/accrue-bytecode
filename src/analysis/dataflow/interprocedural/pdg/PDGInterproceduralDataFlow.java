package analysis.dataflow.interprocedural.pdg;

import java.util.Map;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.AnalysisTriggerInterproceduralDataFlow;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.interprocedural.nonnull.NonNullResults;
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
import com.ibm.wala.types.TypeReference;

public class PDGInterproceduralDataFlow extends AnalysisTriggerInterproceduralDataFlow {

    private final ProgramDependenceGraph pdg;
    private final PreciseExceptionResults preciseEx;
    private final NonNullResults nonNull;

    /**
     * Analysis that creates a program dependence graph for the entire program (with a call graph described by the
     * points-to graph)
     *
     * @param ptg points-to graph
     * @param preciseEx results of a precise exceptions analysis
     * @param reachable results of a reachability analysis
     * @param nonNull results of a non-null analysis
     * @param rvCache mapping of local variables to reference variables (used by the points-to graph)
     */
    public PDGInterproceduralDataFlow(PointsToGraph ptg, PreciseExceptionResults preciseEx,
                                      ReachabilityResults reachable, NonNullResults nonNull,
                                      ReferenceVariableCache rvCache) {
        super(ptg, reachable, rvCache);
        this.pdg = new ProgramDependenceGraph();
        this.preciseEx = preciseEx;
        this.nonNull = nonNull;
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
        PDGComputeNodesDataflow df = new PDGComputeNodesDataflow(n, this);
        df.setOutputLevel(getOutputLevel());
        df.dataflow();
        return UNIT_MAP;
    }

    @Override
    protected Map<ExitType, Unit> analyzeMissingCode(CGNode n, Unit input) {
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
            // Don't add an explicit edge to the exception
            //pdg.addEdge(formal, exExit.getExceptionNode(), PDGEdgeType.MISSING);
        }

        return UNIT_MAP;
    }

    public PreciseExceptionResults getPreciseExceptionResults() {
        return preciseEx;
    }

    public NonNullResults getNonNullResults() {
        return nonNull;
    }

    @Override
    public ProgramDependenceGraph getAnalysisResults() {
        return pdg;
    }
}
