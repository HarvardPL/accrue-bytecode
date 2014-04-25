package analysis.dataflow.interprocedural.nonnull;

import java.util.HashMap;
import java.util.Map;

import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.dataflow.interprocedural.InterproceduralDataFlowManager;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.util.ExitType;
import analysis.dataflow.util.VarContext;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.types.TypeReference;

/**
 * Inter-procedureal data-flow manager for an analysis that determines when
 * local variable are non-null
 */
public class NonNullManager extends InterproceduralDataFlowManager<VarContext<NonNullAbsVal>> {

    /**
     * Results of a precise exception analysis
     */
    private final PreciseExceptionResults preciseEx;
    /**
     * WALA analysis classes
     */
    private final WalaAnalysisUtil util;
    /**
     * Results of the analysis, namely which local variables are null and when
     * are put here
     */
    private final NonNullResults results = new NonNullResults();
    /**
     * Name of this analysis
     */
    private static final String ANALYSIS_NAME = "Non-null Analysis";

    /**
     * Create a new inter-procedural non-null analysis over the given call graph
     * 
     * @param cg
     *            call graph
     * @param ptg
     *            points-to graph
     * @param preciseEx
     *            results of a precise exception analysis
     * @param util
     *            WALA analysis classes
     */
    public NonNullManager(CallGraph cg, PointsToGraph ptg, PreciseExceptionResults preciseEx, WalaAnalysisUtil util) {
        super(cg, ptg);
        this.preciseEx = preciseEx;
        this.util = util;
    }

    @Override
    protected String getAnalysisName() {
        return ANALYSIS_NAME;
    }

    @Override
    protected Map<ExitType, VarContext<NonNullAbsVal>> analyze(CGNode n, VarContext<NonNullAbsVal> input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING:\n\t" + PrettyPrinter.parseCGNode(n) + "\n\tINPUT: " + input);
        }
        NonNullDataFlow df = new NonNullDataFlow(n, this, preciseEx, util);
        df.setOutputLevel(getOutputLevel());
        return df.dataflow(input);
    }

    @Override
    protected Map<ExitType, VarContext<NonNullAbsVal>> analyzeNative(CGNode n, VarContext<NonNullAbsVal> input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING NATIVE:\n\t" + PrettyPrinter.parseCGNode(n) + "\n\tINPUT: " + input);
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
        results.put(ExitType.NORM_TERM, normal);
        results.put(ExitType.EXCEPTION, input.setExceptionValue(NonNullAbsVal.NON_NULL));
        return results;
    }

    @Override
    protected Map<ExitType, VarContext<NonNullAbsVal>> getDefaultOutput(VarContext<NonNullAbsVal> input) {
        Map<ExitType, VarContext<NonNullAbsVal>> res = new HashMap<ExitType, VarContext<NonNullAbsVal>>();
        res.put(ExitType.NORM_TERM, input.setReturnResult(NonNullAbsVal.MAY_BE_NULL));
        res.put(ExitType.EXCEPTION, input.setExceptionValue(NonNullAbsVal.NON_NULL));
        return res;
    }

    @Override
    protected VarContext<NonNullAbsVal> getInputForRoot() {
        return new NonNullVarContext(null, null);
    }

    @Override
    protected boolean outputChanged(Map<ExitType, VarContext<NonNullAbsVal>> previousOutput,
                                    Map<ExitType, VarContext<NonNullAbsVal>> currentOutput) {
        assert previousOutput != null;
        assert currentOutput != null;
        return previousOutput.equals(currentOutput);
    }

    @Override
    protected boolean existingResultSuitable(VarContext<NonNullAbsVal> newInput,
                                    AnalysisRecord<VarContext<NonNullAbsVal>> existingResults) {
        return existingResults != null && newInput.leq(existingResults.getInput());
    }

    /**
     * Get the results after running this inter-procedural analysis
     * 
     * @return which variables are non-null before each instruction
     */
    public NonNullResults getNonNullResults() {
        return results;
    }

    @Override
    protected VarContext<NonNullAbsVal> join(VarContext<NonNullAbsVal> item1, VarContext<NonNullAbsVal> item2) {
        return VarContext.join(item1, item2);
    }
}
