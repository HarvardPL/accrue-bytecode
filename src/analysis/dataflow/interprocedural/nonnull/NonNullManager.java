package analysis.dataflow.interprocedural.nonnull;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import util.WalaAnalysisUtil;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ssa.SSAInstruction;

import analysis.dataflow.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlowManager;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptions;
import analysis.dataflow.util.VarContext;
import analysis.pointer.graph.PointsToGraph;

/**
 * Inter-procedureal data-flow manager for an analysis that determines when
 * local variable are non-null
 */
public class NonNullManager extends InterproceduralDataFlowManager<VarContext<NonNullAbsVal>> {

    private final PreciseExceptions preciseEx;
    private final WalaAnalysisUtil util;
    /**
     * Results of the analysis, namely which local variables are null and when
     * are put here
     */
    private final NonNullResults results = new NonNullResults();

    public NonNullManager(CallGraph cg, PointsToGraph ptg, PreciseExceptions preciseEx, WalaAnalysisUtil util) {
        super(cg, ptg);
        this.preciseEx = preciseEx;
        this.util = util;
    }

    @Override
    protected VarContext<NonNullAbsVal> join(VarContext<NonNullAbsVal> item1, VarContext<NonNullAbsVal> item2) {
        return item1.join(item2);
    }

    @Override
    protected Map<ExitType, VarContext<NonNullAbsVal>> analyze(CGNode n, VarContext<NonNullAbsVal> input) {
        NonNullDataFlow df = new NonNullDataFlow(n, this, preciseEx, util);
        df.setOutputLevel(getOutputLevel());
        return df.dataflow(input);
    }

    @Override
    protected Map<ExitType, VarContext<NonNullAbsVal>> getDefaultOutput() {
        Map<ExitType, VarContext<NonNullAbsVal>> res = new HashMap<ExitType, VarContext<NonNullAbsVal>>();
        res.put(ExitType.NORM_TERM, new NonNullVarContext());
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> getInputForRoot() {
        return new NonNullVarContext();
    }

    @Override
    protected boolean outputChanged(Map<ExitType, VarContext<NonNullAbsVal>> previousOutput,
                                    Map<ExitType, VarContext<NonNullAbsVal>> currentOutput) {
        assert previousOutput != null;
        assert currentOutput  != null;
        for (ExitType key : previousOutput.keySet()) {
            if (!previousOutput.get(key).equals(currentOutput.get(key))) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean existingResultSuitable(
                                    VarContext<NonNullAbsVal> input,
                                    InterProcAnalysisRecord<VarContext<NonNullAbsVal>> rec) {
        return rec != null && input.leq(rec.getInput());
    }

    public NonNullResults getNonNullResults(){
        return results;
    }
    
    public void replaceNonNull(Set<Integer> nonNulls, SSAInstruction i, CGNode containingNode) {
        results.replaceNonNull(nonNulls, i, containingNode);
    }
}
