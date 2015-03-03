package analysis.dataflow.interprocedural.collect;

import java.util.Map;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.AnalysisResults;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.interval.IntervalResults;
import analysis.dataflow.interprocedural.nonnull.NonNullResults;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.dataflow.util.Unit;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableCache;

import com.ibm.wala.ipa.callgraph.CGNode;

public class CollectResultsInterproceduralDataFlow extends AnalysisTriggerInterproceduralDataFlow {

    private final NonNullResults nonnull;
    private final IntervalResults interval;
    private final CollectedResults collectedResults;

    public CollectResultsInterproceduralDataFlow(PointsToGraph ptg, ReachabilityResults reachable,
                                                 ReferenceVariableCache rvCache, NonNullResults nonnull,
                                                 IntervalResults interval) {
        super(ptg, reachable, rvCache);
        this.nonnull = nonnull;
        this.interval = interval;
        this.collectedResults = new CollectedResults();
    }

    @Override
    protected String getAnalysisName() {
        return "Collect results data-flow";
    }

    @Override
    public AnalysisResults getAnalysisResults() {
        return collectedResults;
    }

    @Override
    protected Map<ExitType, Unit> analyze(CGNode n, Unit input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING:\n\t" + PrettyPrinter.cgNodeString(n));
        }
        CollectResultsDataFlow df = new CollectResultsDataFlow(n, this, nonnull, interval);
        df.setOutputLevel(getOutputLevel());
        df.dataflow(Unit.VALUE);
        return UNIT_MAP;
    }

    @Override
    protected Map<ExitType, Unit> analyzeMissingCode(CGNode n, Unit input) {
        return UNIT_MAP;
    }

    public void recordNullPointerException() {
        collectedResults.recordNullPointerException();
    }

    public void recordArithmeticException() {
        collectedResults.recordArithmeticException();
    }

    public void recordCastRemoval() {
        collectedResults.recordCastRemoval();
    }

    public void recordPossibleNullPointerException() {
        collectedResults.recordPossibleNullPointerException();
    }

    public void recordPossibleArithmeticException() {
        collectedResults.recordPossibleArithmeticException();
    }

    public void recordCast() {
        collectedResults.recordCast();
    }
}
