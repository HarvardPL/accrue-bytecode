package analysis.dataflow.interprocedural.nonnull;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.AnalysisResults;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Results of an inter-procedural non-null analysis
 */
public class NonNullResults implements AnalysisResults {

    private final Map<CGNode, ResultsForNode> allResults = new HashMap<>();

    /**
     * Whether the variable with the given value number is non-null just <i>before</i> executing the given instruction
     *
     * @param valNum
     *            variable value number
     * @param i
     *            instruction TODO Necessary to use the SSAInstruction for non-null results? Could be memory inefficient
     * @param containingNode
     *            containing call graph node (context and code)
     * @return true if the variable represented by the value number is definitely not null
     */
    public boolean isNonNull(int valNum, SSAInstruction i, CGNode containingNode, TypeRepository types) {
        if (containingNode.getIR().getSymbolTable().isNullConstant(valNum)) {
            return false;
        }

        if (types != null && types.getType(valNum).isPrimitiveType()
                && !types.getType(valNum).equals(TypeReference.Null)) {
            // All primitives are non-null
            return true;
        }
        if (containingNode.getIR().getSymbolTable().isConstant(valNum)
                                        && !containingNode.getIR().getSymbolTable().isNullConstant(valNum)) {
            // Constants are non-null unless they are the "null" constant
            return true;
        }

        if (!containingNode.getIR().getMethod().isStatic()) {
            if (valNum == containingNode.getIR().getParameter(0)) {
                // "this" is always non-null, we can bypass the map lookup
                return true;
            }
        }

        ResultsForNode results = allResults.get(containingNode);
        if (results == null) {
            return false;
        }

        return results.isNonNull(valNum, i);
    }

    /**
     * Record that the variables with the given value numbers are non-null just <i>before</i> executing the given
     * instruction in the given call graph node
     *
     * @param nonNullValues
     *            variable value numbers for non-null values
     * @param i
     *            instruction
     * @param containingNode
     *            current call graph node
     */
    public void replaceNonNull(Set<Integer> nonNullValues, SSAInstruction i, CGNode containingNode) {
        ResultsForNode resultsForNode = allResults.get(containingNode);
        if (resultsForNode == null) {
            resultsForNode = new ResultsForNode();
            allResults.put(containingNode, resultsForNode);
        }
        resultsForNode.replaceNonNull(nonNullValues, i);
    }

    /**
     * Non-null analysis results for a particular call graph node
     */
    private static class ResultsForNode {
        /**
         * Map from instruction to value numbers that are definitely non-null just before executing the instruction.
         */
        private final Map<SSAInstruction, Set<Integer>> results = new HashMap<>();

        public ResultsForNode() {
        }

        /**
         * Whether the variable with the given value number is non-null just <i>before</i> executing the given
         * instruction
         *
         * @param valNum
         *            variable value number
         * @param i
         *            instruction
         * @return true if the variable represented by the value number is definitely not null
         */
        public boolean isNonNull(int valNum, SSAInstruction i) {
            Set<Integer> nonNulls = results.get(i);
            if (nonNulls == null) {
                return false;
            }
            return nonNulls.contains(valNum);
        }

        /**
         * Get value numbers for all non-null variables right before executing i
         *
         * @param i
         *            instruction
         * @return set of non-null value numbers
         */
        protected Set<Integer> getAllNonNull(SSAInstruction i) {
            Set<Integer> res = results.get(i);
            if (res == null) {
                return Collections.emptySet();
            }
            return res;
        }

        /**
         * Record that the variable with the given value number is non-null just <i>before</i> executing the given
         * instruction, replace the current set of values if there are any
         *
         * @param nonNullValues
         *            variable value number for non-null value
         * @param i
         *            instruction
         */
        public void replaceNonNull(Set<Integer> nonNullValues, SSAInstruction i) {
            results.put(i, nonNullValues);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((results == null) ? 0 : results.hashCode());
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
            if (results == null) {
                if (other.results != null) {
                    return false;
                }
            } else if (!results.equals(other.results)) {
                return false;
            }
            return true;
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
            String fileName = directory + "/nonnull_" + cgString + ".dot";
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
                for (Integer val : results.getAllNonNull(i)) {
                    strings.add(pp.valString(val));
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
}
