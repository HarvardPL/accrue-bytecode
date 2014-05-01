package analysis.dataflow.interprocedural.reachability;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.AnalysisResults;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;

public class ReachabilityResults implements AnalysisResults {

    /**
     * Reachability results where everything is reachable
     */
    public static final ReachabilityResults ALWAYS_REACHABLE = new ReachabilityResults() {
        public boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target, CGNode containingNode) {
            return false;
        };
    };

    private final Map<CGNode, ResultsForNode> allResults = new HashMap<>();

    public void replaceUnreachable(Set<OrderedPair<ISSABasicBlock, ISSABasicBlock>> unreachableEdges,
                                    CGNode containingNode) {
        ResultsForNode results = allResults.get(containingNode);
        if (results == null) {
            results = new ResultsForNode();
            allResults.put(containingNode, results);
        }
        results.replaceUnreachable(unreachableEdges);
    }

    public boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target, CGNode containingNode) {
        ResultsForNode results = allResults.get(containingNode);
        assert results != null;
        return results.isUnreachable(source, target);
    }

    private class ResultsForNode {

        Set<OrderedPair<ISSABasicBlock, ISSABasicBlock>> unreachableEdges;

        public ResultsForNode() {
            unreachableEdges = new LinkedHashSet<>();
        }

        public boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
            return unreachableEdges.contains(new OrderedPair<>(source, target));
        }

        public void replaceUnreachable(Set<OrderedPair<ISSABasicBlock, ISSABasicBlock>> unreachableEdges) {
            this.unreachableEdges = unreachableEdges;
        }
    }

    /**
     * Will write the results for the first context for the given method
     * 
     * @param writer
     *            writer to write to
     * @param m
     *            method to write the results for
     * 
     * @throws IOException
     *             issues with the writer
     */
    public void writeResultsForMethod(Writer writer, IMethod m) throws IOException {
        for (CGNode n : allResults.keySet()) {
            if (n.getMethod().equals(m)) {
                writeResultsForNode(writer, n);
                return;
            }
        }
    }

    public void writeAllToFiles() throws IOException {
        for (CGNode n : allResults.keySet()) {
            String fileName = "tests/reachability_" + PrettyPrinter.parseCGNode(n).replace(" ", "") + ".dot";
            try (Writer w = new FileWriter(fileName)) {
                writeResultsForNode(w, n);
                System.err.println("DOT written to " + fileName);
            }
        }
    }

    private void writeResultsForNode(Writer writer, final CGNode n) throws IOException {
        final ResultsForNode results = allResults.get(n);

        CFGWriter w = new CFGWriter(n.getIR()) {
            @Override
            protected String getExceptionEdgeLabel(ISSABasicBlock source, ISSABasicBlock target, IR ir) {
                if (results.isUnreachable(source, target)) {
                    return "EX UNREACHABLE";
                }
                return "EX REACHABLE";
            }

            @Override
            protected String getNormalEdgeLabel(ISSABasicBlock source, ISSABasicBlock target, IR ir) {
                if (results.isUnreachable(source, target)) {
                    return "UNREACHABLE";
                }
                return "REACHABLE";
            }
        };

        w.writeVerbose(writer, "", "\\l");
    }
}
