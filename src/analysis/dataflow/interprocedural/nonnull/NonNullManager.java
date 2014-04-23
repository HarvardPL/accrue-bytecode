package analysis.dataflow.interprocedural.nonnull;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ssa.SSAInstruction;

import analysis.WalaAnalysisUtil;
import analysis.dataflow.interprocedural.InterproceduralDataFlowManager;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.util.ExitType;
import analysis.dataflow.util.VarContext;
import analysis.pointer.graph.PointsToGraph;

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
    protected Map<ExitType, VarContext<NonNullAbsVal>> analyze(CGNode n, VarContext<NonNullAbsVal> input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING:\n\t" + PrettyPrinter.parseCGNode(n) + "\n\tINPUT: " + input);
        }
        NonNullDataFlow df = new NonNullDataFlow(n, this, preciseEx, util);
        df.setOutputLevel(getOutputLevel());
        return df.dataflow(input);
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
        for (ExitType key : previousOutput.keySet()) {
            if (!previousOutput.get(key).equals(currentOutput.get(key))) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean existingResultSuitable(VarContext<NonNullAbsVal> input,
                                    AnalysisRecord<VarContext<NonNullAbsVal>> rec) {
        return rec != null && input.leq(rec.getInput());
    }

    /**
     * Get the results after running this inter-procedural analysis
     * 
     * @return
     */
    public NonNullResults getNonNullResults() {
        return results;
    }

    public void replaceNonNull(Set<Integer> nonNulls, SSAInstruction i, CGNode containingNode) {
        results.replaceNonNull(nonNulls, i, containingNode);
    }

    @Override
    protected VarContext<NonNullAbsVal> join(VarContext<NonNullAbsVal> item1, VarContext<NonNullAbsVal> item2) {
        return VarContext.join(item1, item2);
    }
}
