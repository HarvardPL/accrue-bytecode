package analysis.dataflow.interprocedural.interval;

import java.util.HashMap;
import java.util.Map;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.AnalysisResults;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.dataflow.util.VarContext;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableCache;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.types.TypeReference;

/**
 * Inter-procedural data-flow manager for an analysis that determines the value range of variables
 */
public class IntervalInterProceduralDataFlow extends InterproceduralDataFlow<VarContext<IntervalAbsVal>> {

    /**
     * Name of this analysis
     */
    private static final String ANALYSIS_NAME = "Interval Analysis";

    /**
     * Results of the Analysis
     */
    private final IntervalResults results = new IntervalResults();

    /**
     * should heap locations be tracked (flow-sensitively) in the var context
     */
    private final boolean trackHeapLocations;

    public IntervalInterProceduralDataFlow(PointsToGraph ptg, ReachabilityResults reachable,
                                           ReferenceVariableCache rvCache, boolean trackHeapLocations) {
        super(ptg, reachable, rvCache);
        this.trackHeapLocations = trackHeapLocations;
    }

    @Override
    protected String getAnalysisName() {
        return ANALYSIS_NAME;
    }

    @Override
    public AnalysisResults getAnalysisResults() {
        return results;
    }

    @Override
    protected Map<ExitType, VarContext<IntervalAbsVal>> analyze(CGNode n, VarContext<IntervalAbsVal> input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING:\n\t" + PrettyPrinter.cgNodeString(n) + "\n\tINPUT: " + input);
        }
        IntervalDataFlow df = new IntervalDataFlow(n, this);
        df.setOutputLevel(getOutputLevel());
        return df.dataflow(input);
    }

    @Override
    protected Map<ExitType, VarContext<IntervalAbsVal>> analyzeMissingCode(CGNode n, VarContext<IntervalAbsVal> input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING NATIVE:\n\t" + PrettyPrinter.cgNodeString(n) + "\n\tINPUT: " + input);
        }
        Map<ExitType, VarContext<IntervalAbsVal>> results = new HashMap<>();
        if (!n.getMethod().getReturnType().equals(TypeReference.Void)
                && !n.getMethod().getReturnType().isPrimitiveType()) {
            results.put(ExitType.NORMAL, input);
        }
        else {
            results.put(ExitType.NORMAL, input.setReturnResult(IntervalAbsVal.TOP_ELEMENT));
        }
        results.put(ExitType.EXCEPTIONAL, input);
        return results;
    }

    @Override
    protected Map<ExitType, VarContext<IntervalAbsVal>> getDefaultOutput(VarContext<IntervalAbsVal> input) {
        Map<ExitType, VarContext<IntervalAbsVal>> res = new HashMap<>();
        res.put(ExitType.NORMAL, input.setReturnResult(IntervalAbsVal.BOTTOM_ELEMENT));
        res.put(ExitType.EXCEPTIONAL, input);
        return res;
    }

    @Override
    protected VarContext<IntervalAbsVal> getInputForEntryPoint() {
        return new IntervalVarContext(null, null, trackHeapLocations);
    }

    @Override
    protected boolean outputChanged(Map<ExitType, VarContext<IntervalAbsVal>> previousOutput,
                                    Map<ExitType, VarContext<IntervalAbsVal>> currentOutput) {
        assert previousOutput != null;
        assert currentOutput != null;
        return !previousOutput.equals(currentOutput);
    }

    @Override
    protected boolean existingResultSuitable(VarContext<IntervalAbsVal> newInput,
                                             analysis.dataflow.interprocedural.InterproceduralDataFlow.AnalysisRecord<VarContext<IntervalAbsVal>> existingResults) {
        return existingResults != null && newInput.leq(existingResults.getInput());
    }

}
