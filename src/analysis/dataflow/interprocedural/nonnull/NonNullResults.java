package analysis.dataflow.interprocedural.nonnull;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.CFGWriter;
import util.print.PrettyPrinter;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;

/**
 * Results of an inter-procedural non-null analysis
 */
public class NonNullResults {

    private final Map<CGNode, ResultsForNode> allResults = new HashMap<>();

    /**
     * Whether the variable with the given value number is non-null just
     * <i>before</i> executing the given instruction
     * 
     * @param valNum
     *            variable value number
     * @param i
     *            instruction
     * @param containingNode
     *            containing call graph node (context and code)
     * @return true if the variable represented by the value number is
     *         definitely not null
     */
    public boolean isNonNull(int valNum, SSAInstruction i, CGNode containingNode) {
        if (TypeRepository.getType(valNum, containingNode.getIR()).isPrimitiveType()) {
            // All primitives are non-null
            return true;
        }
        if (containingNode.getIR().getSymbolTable().isConstant(valNum)
                                        && !containingNode.getIR().getSymbolTable().isNullConstant(valNum)) {
            // Constants are non-null unless they are the "null" constant
            return true;
        }
        // TODO this is commented out while testing
        // if (!containingNode.getIR().getMethod().isStatic()) {
        // if (valNum == containingNode.getIR().getParameter(0)) {
        // // "this" is always non-null, we can bypass the map lookup
        // return true;
        // }
        // }

        ResultsForNode results = allResults.get(containingNode);
        if (results == null) {
            System.err.println("WARNING: empty non-null results for " + PrettyPrinter.parseCGNode(containingNode));
            return false;
        }

        return results.isNonNull(valNum, i, containingNode.getIR());
    }

    /**
     * Record that the variables with the given value numbers are non-null just
     * <i>before</i> executing the given instruction in the given call graph
     * node
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
         * Map from instruction to value numbers that are definitely non-null
         * just before executing the instruction.
         */
        private final Map<SSAInstruction, Set<Integer>> results = new HashMap<>();

        /**
         * Whether the variable with the given value number is non-null just
         * <i>before</i> executing the given instruction
         * 
         * @param valNum
         *            variable value number
         * @param i
         *            instruction
         * @param ir
         *            containing code
         * @return true if the variable represented by the value number is
         *         definitely not null
         */
        public boolean isNonNull(int valNum, SSAInstruction i, IR ir) {
            Set<Integer> nonNulls = results.get(i);
            if (nonNulls == null) {
                System.err.println("WARNING: empty non-null results for instruction "
                                                + PrettyPrinter.instructionString(i, ir) + " in "
                                                + PrettyPrinter.parseMethod(ir.getMethod()));
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
        private Set<Integer> getAllNonNull(SSAInstruction i) {
            return results.get(i);
        }

        /**
         * Record that the variable with the given value number is non-null just
         * <i>before</i> executing the given instruction, replace the current
         * set of values if there are any
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
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ResultsForNode other = (ResultsForNode) obj;
            if (results == null) {
                if (other.results != null)
                    return false;
            } else if (!results.equals(other.results))
                return false;
            return true;
        }
    }

    /**
     * Will write the results for all contexts for the given method
     * <p>
     * TODO not sure what this looks like in dot
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
            }
        }
    }

    private void writeResultsForNode(Writer writer, final CGNode n) throws IOException {
        final ResultsForNode results = allResults.get(n);

        CFGWriter w = new CFGWriter(n.getIR()) {
            @Override
            public String getPrefix(SSAInstruction i) {
                Set<String> strings = new HashSet<>();
                for (Integer val : results.getAllNonNull(i)) {
                    strings.add(PrettyPrinter.valString(n.getIR(), val));
                }
                return strings + "\\l";
            }
        };

        w.writeVerbose(writer, "", "\\l");
    }
}
