package analysis.dataflow.interprocedural.interval;

import java.util.HashMap;
import java.util.Map;

import analysis.dataflow.interprocedural.AnalysisResults;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.SSAInstruction;

public class IntervalResults implements AnalysisResults {

    private final Map<CGNode, ResultsForNode> allResults = new HashMap<>();

    private static class ResultsForNode {
        /**
         * Map from instruction to a map from variables to their value intervals.
         */
        private final Map<SSAInstruction, Map<Integer, IntervalAbsVal>> results = new HashMap<>();
    }
}
