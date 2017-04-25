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

    public void replaceIntervalExitMapForLocals(Map<Integer, IntervalAbsVal> intervalExitMap, SSAInstruction i,
                                                CGNode containingNode) {
        ResultsForNode resultsForNode = allResults.get(containingNode);
        if (resultsForNode == null) {
            resultsForNode = new ResultsForNode();
            allResults.put(containingNode, resultsForNode);
        }
        resultsForNode.replaceIntervalExitMapForLocals(intervalExitMap, i);
    }


    void replaceIntervalMapForLocals(Map<Integer, IntervalAbsVal> intervalMap, SSAInstruction i,
                                            CGNode containingNode) {
        ResultsForNode resultsForNode = allResults.get(containingNode);
        if (resultsForNode == null) {
            resultsForNode = new ResultsForNode();
            allResults.put(containingNode, resultsForNode);
        }
        resultsForNode.replaceIntervalMapForLocals(intervalMap, i);
    }

    void replaceIntervalMapForLocations(Map<AbstractLocation, IntervalAbsVal> intervalMap, SSAInstruction i,
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

                if (integerMapLocals != null) {
                    for (Integer val : integerMapLocals.keySet()) {
                        if (!pp.valString(val).contains("java.lang.String")) {
                            strings.add(pp.valString(val) + "->" + integerMapLocals.get(val));
                        }
                    }
                }

                if (integerMapLocations != null) {
                    for (AbstractLocation loc : integerMapLocations.keySet()) {
                        if (!loc.toString().contains("java.lang.String")) {
                            strings.add(loc + "->" + integerMapLocations.get(loc));
                        }
                    }
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

    /**
     * Get the interval for the given local right before executing the given instruction. Returns null if the local is
     * not a primitive or the results are not found.
     */
    public IntervalAbsVal getLocalIntervalBefore(int local, SSAInstruction i, CGNode n) {
        ResultsForNode res = allResults.get(n);
        if (res == null) {
            return null;
        }

        Map<Integer, IntervalAbsVal> intervalMapForLocals = res.getIntervalMapForLocals(i);
        if (intervalMapForLocals == null) {
            return null;
        }

        IntervalAbsVal interval = intervalMapForLocals.get(local);
        if (interval == null) {
            return null;
        }
        return interval;
    }

    /**
     * Get the interval for the given local right after executing the given instruction. Returns null if the local is
     * not a primitive or the results are not found.
     */
    public IntervalAbsVal getLocalIntervalAfter(int local, SSAInstruction i, CGNode n) {
        ResultsForNode res = allResults.get(n);
        if (res == null) {
            return null;
        }

        Map<Integer, IntervalAbsVal> intervalMapForLocals = res.getIntervalExitMapForLocals(i);
        if (intervalMapForLocals == null) {
            return null;
        }

        IntervalAbsVal interval = intervalMapForLocals.get(local);
        if (interval == null) {
            return null;
        }
        return interval;
    }

    /**
     * Get the interval for the given location right before executing the given instruction. Returns null if the
     * location is not a primitive or the results are not found.
     */
    public IntervalAbsVal getLocationlIntervalBefore(AbstractLocation location, SSAInstruction i, CGNode n) {
        ResultsForNode res = allResults.get(n);
        if (res == null) {
            return null;
        }

        Map<AbstractLocation, IntervalAbsVal> intervalMapForLocations = res.getIntervalMapForLocations(i);
        if (intervalMapForLocations == null) {
            return null;
        }

        IntervalAbsVal interval = intervalMapForLocations.get(location);
        if (interval == null) {
            return null;
        }
        return interval;
    }

    private static class ResultsForNode {
        /**
         * Map from instruction to a map from variables to their value intervals.
         */
        private final Map<SSAInstruction, Map<Integer, IntervalAbsVal>> resultLocals = new HashMap<>();
        /**
         * Map from instruction to a map from variables to their value right after the instruction.
         */
        private final Map<SSAInstruction, Map<Integer, IntervalAbsVal>> resultExitLocals = new HashMap<>();
        /**
         * Map from instruction to a map from abstract locations to their value intervals.
         */
        private final Map<SSAInstruction, Map<AbstractLocation, IntervalAbsVal>> resultLocations = new HashMap<>();

        public ResultsForNode() {
            // intentionally blank
        }

        public void replaceIntervalExitMapForLocals(Map<Integer, IntervalAbsVal> intervalExitMap, SSAInstruction i) {
            resultExitLocals.put(i, intervalExitMap);
        }

        public Map<Integer, IntervalAbsVal> getIntervalExitMapForLocals(SSAInstruction i) {
            return resultExitLocals.get(i);
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
            result = prime * result + ((this.resultExitLocals == null) ? 0 : this.resultExitLocals.hashCode());
            result = prime * result + ((this.resultLocals == null) ? 0 : this.resultLocals.hashCode());
            result = prime * result + ((this.resultLocations == null) ? 0 : this.resultLocations.hashCode());
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
            if (this.resultExitLocals == null) {
                if (other.resultExitLocals != null) {
                    return false;
                }
            }
            else if (!this.resultExitLocals.equals(other.resultExitLocals)) {
                return false;
            }
            if (this.resultLocals == null) {
                if (other.resultLocals != null) {
                    return false;
                }
            }
            else if (!this.resultLocals.equals(other.resultLocals)) {
                return false;
            }
            if (this.resultLocations == null) {
                if (other.resultLocations != null) {
                    return false;
                }
            }
            else if (!this.resultLocations.equals(other.resultLocations)) {
                return false;
            }
            return true;
        }
    }
}
