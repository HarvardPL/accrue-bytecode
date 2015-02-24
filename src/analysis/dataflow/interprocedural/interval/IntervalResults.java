package analysis.dataflow.interprocedural.interval;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.AnalysisResults;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.dataflow.util.AbstractLocation;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.ISSABasicBlock;
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

    /**
     * Will write the results for the first context for the given method
     *
     * @param writer writer to write to
     * @param m method to write the results for
     *
     * @throws IOException issues with the writer
     */
    public void writeResultsForMethod(Writer writer, String m, ReachabilityResults reachable) throws IOException {
        for (CGNode n : allResults.keySet()) {
            if (PrettyPrinter.methodString(n.getMethod()).contains(m)) {
                writeResultsForNode(writer, n, reachable);
                return;
            }
        }
    }

    /**
     * Write the results for each call graph node to the sepcified directory
     *
     * @param reachable results of a reachability analysis
     * @param directory directory to print to
     * @throws IOException file trouble
     */
    public void writeAllToFiles(ReachabilityResults reachable, String directory) throws IOException {
        for (CGNode n : allResults.keySet()) {
            String cgString = PrettyPrinter.cgNodeString(n);
            if (cgString.length() > 200) {
                cgString = cgString.substring(0, 200);
            }
            String fileName = directory + "/interval_" + cgString + ".dot";
            try (Writer w = new FileWriter(fileName)) {
                writeResultsForNode(w, n, reachable);
                System.err.println("DOT written to " + fileName);
            }
        }
    }

    private void writeResultsForNode(Writer writer, final CGNode n, final ReachabilityResults reachable)
                                                                                                        throws IOException {
        final ResultsForNode results = allResults.get(n);

        CFGWriter w = new CFGWriter(n.getIR()) {

            PrettyPrinter pp = new PrettyPrinter(n.getIR());

            @Override
            public String getPrefix(SSAInstruction i) {
                if (results == null) {
                    // nothing is non-null
                    return "[]\\l";
                }

                Set<String> strings = new HashSet<>();
                Map<Integer, IntervalAbsVal> integerMapLocals = results.getIntervalMapForLocals(i);
                Map<AbstractLocation, IntervalAbsVal> integerMapLocations = results.getIntervalMapForLocations(i);
                for (Integer val : integerMapLocals.keySet()) {
                    strings.add(pp.valString(val) + "->" + integerMapLocals.get(val).toString());
                }
                for (AbstractLocation loc : integerMapLocations.keySet()) {
                    strings.add(loc.toString() + "->" + integerMapLocations.get(loc).toString());
                }
                return strings + "\\l";
            }

            @Override
            protected Set<ISSABasicBlock> getUnreachableSuccessors(ISSABasicBlock bb,
                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
                Set<ISSABasicBlock> unreachable = new LinkedHashSet<>();
                for (ISSABasicBlock next : cfg.getNormalSuccessors(bb)) {
                    if (reachable.isUnreachable(bb, next, n)) {
                        unreachable.add(next);
                    }
                }

                for (ISSABasicBlock next : cfg.getExceptionalSuccessors(bb)) {
                    if (reachable.isUnreachable(bb, next, n)) {
                        unreachable.add(next);
                    }
                }
                return unreachable;
            }
        };

        w.writeVerbose(writer, "", "\\l");
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

        public Map<Integer, IntervalAbsVal> getIntervalMapForLocals(SSAInstruction i) {
            return resultLocals.get(i);
        }

        public Map<AbstractLocation, IntervalAbsVal> getIntervalMapForLocations(SSAInstruction i) {
            return resultLocations.get(i);
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
