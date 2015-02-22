package analysis.dataflow.interprocedural.interval;

import java.util.HashMap;
import java.util.Map;

import analysis.dataflow.interprocedural.AnalysisResults;
import analysis.dataflow.util.AbstractLocation;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.SSAInstruction;

public class IntervalResults implements AnalysisResults {

    private final Map<CGNode, ResultsForNode> allResults = new HashMap<>();

    public void replaceIntervalMapForLocals(Map<Integer, IntervalAbsVal> intervalMap, SSAInstruction i,
                                            CGNode containingNode) {
        ResultsForNode resultsForNode = allResults.get(containingNode);
        if (resultsForNode == null) {
            resultsForNode = new ResultsForNode();
            allResults.put(containingNode, resultsForNode);
        }
        resultsForNode.replaceIntervalMapForLocals(intervalMap, i);
    }

    public void replaceIntervalMapForLocations(Map<AbstractLocation, IntervalAbsVal> intervalMap, SSAInstruction i,
                                        CGNode containingNode) {
        ResultsForNode resultsForNode = allResults.get(containingNode);
        if (resultsForNode == null) {
            resultsForNode = new ResultsForNode();
            allResults.put(containingNode, resultsForNode);
        }
        resultsForNode.replaceIntervalMapForLocations(intervalMap, i);
    }

    private static class ResultsForNode {
        /**
         * Map from instruction to a map from variables to their value intervals.
         */
        private final Map<SSAInstruction, Map<Integer, IntervalAbsVal>> resultLocals = new HashMap<>();
        /**
         * Map from instruction to a map from abstract locations to their value intervals.
         */
        private final Map<SSAInstruction, Map<AbstractLocation, IntervalAbsVal>> resultLocations = new HashMap<>();

        public ResultsForNode() {
            // intentionally blank
        }

        public IntervalAbsVal getInterval(int valNum, SSAInstruction i) {
            Map<Integer, IntervalAbsVal> m = resultLocals.get(i);
            if (m == null) {
                return null;
            }
            return m.get(valNum);
        }

        public IntervalAbsVal getInterval(AbstractLocation loc, SSAInstruction i) {
            Map<AbstractLocation, IntervalAbsVal> m = resultLocations.get(i);
            if (m == null) {
                return null;
            }
            return m.get(loc);
        }

        public void replaceIntervalMapForLocals(Map<Integer, IntervalAbsVal> intervalMap, SSAInstruction i) {
            resultLocals.put(i, intervalMap);
        }

        public void replaceIntervalMapForLocations(Map<AbstractLocation, IntervalAbsVal> intervalMap, SSAInstruction i) {
            resultLocations.put(i, intervalMap);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((resultLocals == null) ? 0 : resultLocals.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ResultsForNode other = (ResultsForNode) obj;
            if (resultLocals == null) {
                if (other.resultLocals != null) {
                    return false;
                }
            }
            else if (!resultLocals.equals(other.resultLocals)) {
                return false;
            }
            return true;
        }
    }

}
