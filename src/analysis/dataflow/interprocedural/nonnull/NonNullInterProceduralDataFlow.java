package analysis.dataflow.interprocedural.nonnull;

import java.util.HashMap;
import java.util.Map;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.accessible.AccessibleLocationResults;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.dataflow.util.VarContext;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableCache;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.types.TypeReference;

/**
 * Inter-procedureal data-flow manager for an analysis that determines when local variable are non-null
 */
public class NonNullInterProceduralDataFlow extends InterproceduralDataFlow<VarContext<NonNullAbsVal>> {

    /**
     * Results of the analysis, namely which local variables are null and when are put here
     */
    private final NonNullResults results = new NonNullResults();
    /**
     * Abstract heap locations accessible from each call graph node
     */
    final AccessibleLocationResults accessibleLocs;
    /**
     * should heap locations be tracked (flow-sensitively) in the var context
     */
    private final boolean trackHeapLocations;
    /**
     * Name of this analysis
     */
    private static final String ANALYSIS_NAME = "Non-null Analysis";

    /**
     * Create a new inter-procedural non-null analysis over the given call graph
     *
     * @param ptg points-to graph
     * @param reachable results of a reachability analysis
     * @param trackHeapLocations should heap locations be tracked (flow-sensitively) in the var context
     */
    public NonNullInterProceduralDataFlow(PointsToGraph ptg, ReachabilityResults reachable,
                                          ReferenceVariableCache rvCache, boolean trackHeapLocations,
                                          AccessibleLocationResults accessibleLocs) {
        super(ptg, reachable, rvCache);
        this.trackHeapLocations = trackHeapLocations;
        this.accessibleLocs = accessibleLocs;
    }

    @Override
    protected String getAnalysisName() {
        return ANALYSIS_NAME;
    }

    @Override
    protected Map<ExitType, VarContext<NonNullAbsVal>> analyze(CGNode n, VarContext<NonNullAbsVal> input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING:\n\t" + PrettyPrinter.cgNodeString(n) + "\n\tINPUT: " + input);
        }
        NonNullDataFlow df = new NonNullDataFlow(n, this);
        df.setOutputLevel(getOutputLevel());
        // only use the locations that are accessible from the call graph node
        return df.dataflow(input.retainAllLocations(accessibleLocs.getResults(n)));
        //        return df.dataflow(input);

    }

    @Override
    protected Map<ExitType, VarContext<NonNullAbsVal>> analyzeMissingCode(CGNode n, VarContext<NonNullAbsVal> input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING NATIVE:\n\t" + PrettyPrinter.cgNodeString(n) + "\n\tINPUT: " + input);
        }
        // TODO this is unsound if the arguments could change to null
        // We could make this sound by setting all locals in input to
        // MAYBE_NULL, but that would probably be imprecise
        VarContext<NonNullAbsVal> normal = input;
        if (!n.getMethod().getReturnType().equals(TypeReference.Void)
                                        && !n.getMethod().getReturnType().isPrimitiveType()) {
            // assume return result could be null
            normal = normal.setReturnResult(NonNullAbsVal.MAY_BE_NULL);
        }
        Map<ExitType, VarContext<NonNullAbsVal>> results = new HashMap<>();
        results.put(ExitType.NORMAL, normal);
        results.put(ExitType.EXCEPTIONAL, input.setExceptionValue(NonNullAbsVal.NON_NULL));
        return results;
    }

    @Override
    protected Map<ExitType, VarContext<NonNullAbsVal>> getDefaultOutput(VarContext<NonNullAbsVal> input) {
        Map<ExitType, VarContext<NonNullAbsVal>> res = new HashMap<>();
        res.put(ExitType.NORMAL, input.setReturnResult(NonNullAbsVal.MAY_BE_NULL));
        res.put(ExitType.EXCEPTIONAL, input.setExceptionValue(NonNullAbsVal.NON_NULL));
        return res;
    }

    @Override
    protected VarContext<NonNullAbsVal> getInputForEntryPoint() {
        return new NonNullVarContext(null, null, trackHeapLocations);
    }

    @Override
    protected boolean outputChanged(Map<ExitType, VarContext<NonNullAbsVal>> previousOutput,
                                    Map<ExitType, VarContext<NonNullAbsVal>> currentOutput) {
        assert previousOutput != null;
        assert currentOutput != null;
        return !previousOutput.equals(currentOutput);
    }

    @Override
    protected boolean existingResultSuitable(VarContext<NonNullAbsVal> newInput,
                                    AnalysisRecord<VarContext<NonNullAbsVal>> existingResults) {
        return existingResults != null && newInput.leq(existingResults.getInput());
    }

    /**
     * Get the results after running this inter-procedural analysis, these may be unsound while the analysis is running
     *
     *
     * @return which variables are non-null before each instruction
     */
    @Override
    public NonNullResults getAnalysisResults() {
        return results;
    }
}
