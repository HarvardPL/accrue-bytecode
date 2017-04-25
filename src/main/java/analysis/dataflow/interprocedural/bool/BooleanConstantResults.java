package analysis.dataflow.interprocedural.bool;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.AnalysisResults;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;

/**
 * Record of which value numbers correspond to variables that are boolean constants
 */
public class BooleanConstantResults implements AnalysisResults {

    /**
     * Map recording which booleans are constant right <i>before</i> executing a particular instruction, and what that
     * constant value is
     */
    private final Map<SSAInstruction, Map<Integer, Boolean>> constants;
    /**
     * Call graph node used to generate these results
     */
    private final CGNode n;

    protected BooleanConstantResults(CGNode n) {
        constants = new HashMap<>();
        this.n = n;
    }

    /**
     * Check whether the given value number corresponds to a <code>true</code> constant right <i>before</i> executing
     * the given instruction
     *
     * @param i
     *            instruction
     * @param value
     *            value number of variable to check
     * @return true if the given value number corresponds to a <code>true</code> constant right before executing
     *         <code>i</code>
     */
    public boolean isTrueConstant(SSAInstruction i, int value) {
        if (n.getIR().getSymbolTable().isOneOrTrue(value)) {
            return true;
        }

        Map<Integer, Boolean> vals = constants.get(i);
        if (vals == null) {
            return false;
        }
        Boolean val = vals.get(value);
        if (val == null) {
            return false;
        }
        return val;
    }

    /**
     * Check whether the given value number corresponds to a <code>false</code> constant right <i>before</i> executing
     * the given instruction
     *
     * @param i
     *            instruction
     * @param value
     *            value number of variable to check
     * @return true if the given value number corresponds to a <code>false</code> constant right before executing
     *         <code>i</code>
     */
    public boolean isFalseConstant(SSAInstruction i, int value) {
        if (n.getIR().getSymbolTable().isZeroOrFalse(value)) {
            return true;
        }

        Map<Integer, Boolean> vals = constants.get(i);
        if (vals == null) {
            return false;
        }
        Boolean val = vals.get(value);
        if (val == null) {
            return false;
        }
        return !val;
    }

    /**
     * Check whether the given value number corresponds to a boolean constant right <i>before</i> executing the given
     * instruction
     *
     * @param i
     *            instruction
     * @param value
     *            value number of variable to check
     * @return true if the given value number corresponds to a boolean constant right before executing <code>i</code>
     */
    public boolean isConstant(SSAInstruction i, int value) {
        if (n.getIR().getSymbolTable().isOneOrTrue(value)) {
            return true;
        }
        if (n.getIR().getSymbolTable().isZeroOrFalse(value)) {
            return true;
        }

        Map<Integer, Boolean> vals = constants.get(i);
        if (vals == null) {
            return false;
        }
        return vals.containsKey(value);
    }

    /**
     * If the value is constant then get the constant value. Returns null if called on a non-constant
     *
     * @param i
     *            instruction
     * @param value
     *            value number of variable to check
     * @return constant boolean value (if any) or null (if non-constant or non-boolean)
     */
    public Boolean getConstant(SSAInstruction i, int value) {
        if (n.getIR().getSymbolTable().isOneOrTrue(value)) {
            return true;
        }

        if (n.getIR().getSymbolTable().isZeroOrFalse(value)) {
            return false;
        }

        Map<Integer, Boolean> vals = constants.get(i);
        if (vals == null) {
            return null;
        }
        return vals.get(value);
    }

    /**
     * Record that a value number corresponds to a variable that is a constant boolean right before executing
     * <code>i</code>
     *
     * @param i
     *            instruction
     * @param valueNumber
     *            value number of constant boolean
     * @param b
     *            value of the variable
     */
    protected void recordConstant(SSAInstruction i, int valueNumber, boolean b) {
        Map<Integer, Boolean> vals = constants.get(i);
        if (vals == null) {
            vals = new HashMap<>();
            constants.put(i, vals);
        }
        vals.put(valueNumber, b);
    }

    /**
     * Write the results in dot format to a file in the tests directory prepended with "bool"
     *
     * @throws IOException
     *             file troubles
     */
    public void writeResultsToFile(String directory) {
        String cgString = PrettyPrinter.cgNodeString(n);
        if (cgString.length() > 200) {
            cgString = cgString.substring(0, 200);
        }
        String fileName = directory + "/bool_" + cgString + ".dot";
        try (Writer w = new FileWriter(fileName)) {
            writeResults(w);
            System.err.println("DOT written to " + fileName);
        } catch (IOException e) {
            System.err.println("Could not write DOT to file " + fileName + ", " + e.getMessage());
        }
    }

    private void writeResults(Writer writer) throws IOException {
        final IR ir = n.getIR();
        CFGWriter w = new CFGWriter(ir) {

            PrettyPrinter pp = new PrettyPrinter(ir);

            @SuppressWarnings("synthetic-access")
            @Override
            public String getPrefix(SSAInstruction i) {
                Set<String> strings = new HashSet<>();
                Map<Integer, Boolean> vals = constants.get(i);
                if (vals == null) {
                    // Nothing constant before this instruction
                    return "[]\\l";
                }

                for (Integer val : vals.keySet()) {
                    strings.add(pp.valString(val) + " = " + constants.get(i).get(val));
                }
                return strings + "\\l";
            }

            @Override
            protected Set<ISSABasicBlock> getUnreachableSuccessors(ISSABasicBlock bb,
                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
                return Collections.emptySet();
            }
        };

        w.writeVerbose(writer, "", "\\l");
    }
}
