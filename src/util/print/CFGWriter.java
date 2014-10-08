package util.print;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import analysis.AnalysisUtil;
import analysis.dataflow.DataFlow;
import analysis.dataflow.interprocedural.ExitType;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;

/**
 * Write out a control flow graph for a specific method
 */
public class CFGWriter {

    /**
     * Code for method to be written
     */
    private final IR ir;
    /**
     * If true then code will be included in CFG, otherwise it will just be basic block numbers
     */
    private boolean verbose;
    /**
     * Holds the string representation of the basic blocks
     */
    private final Map<ISSABasicBlock, String> bbStrings = new HashMap<>();
    /**
     * String to prepend to instructions
     */
    private String prefix;
    /**
     * String to append to instructions
     */
    private String postfix;
    /**
     * Pretty printer
     */
    private PrettyPrinter pp;

    /**
     * Create a writer for the given IR
     *
     * @param ir
     *            IR for the method to be printed
     * @param writer
     *            output will be written to this writer; will not be closed
     */
    public CFGWriter(IR ir) {
        assert ir != null : "Cannot print CFG for null IR";
        this.ir = ir;
        this.pp = new PrettyPrinter(ir);
    }

    /**
     * Write out the pretty printed code to the given writer
     *
     * @param writer
     *            writer to write the code to
     * @param prefix
     *            prepended to each instruction (e.g. "\t" to indent)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place each instruction on a new line)
     * @throws IOException
     *             writer issues
     */
    public final void write(Writer writer, String prefix, String postfix) throws IOException {
        this.prefix = prefix;
        this.postfix = postfix;
        double spread = 1.0;
        writer.write("digraph G {\n" + "node [shape=record];\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread
                                        + ";\n" + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n"
                                        + "edge [fontsize=10]" + ";\n");

        writeGraph(writer);

        writer.write("\n};\n");
    }

    /**
     * Write the cfg for the given IR to a dot file in the "tests" directory with the filename equal to the method name
     * prepended with "cfg_"
     *
     * @param ir
     *            to write
     */
    public static final void writeToFile(IR ir) {
        CFGWriter cfg = new CFGWriter(ir);
        String dir = AnalysisUtil.getOutputDirectory();
        String fullFilename = dir + "/cfg_" + PrettyPrinter.methodString(ir.getMethod()) + ".dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            cfg.writeVerbose(out, "", "\\l");
            System.err.println("DOT written to: " + fullFilename);
        } catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    /**
     * Write the cfg for the given method to a dot file in the "tests" directory with the filename equal to the method
     * name prepended with "cfg_"
     *
     * @param m
     *            method to write CFG for
     */
    public static final void writeToFile(IMethod m) {
        writeToFile(AnalysisUtil.getIR(m));
    }

    /**
     * Write the cfg for the given IR to a dot file in the tests directory with the given name
     *
     * @param ir
     *            to write
     * @param filename
     *            file to be saved in "tests" directory with .dot appended
     */
    public static final void writeToFile(IR ir, String filename) {
        CFGWriter cfg = new CFGWriter(ir);
        String fullFilename = filename + ".dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            cfg.writeVerbose(out, "", "\\l");
            System.err.println("DOT written to: " + fullFilename);
        } catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    /**
     * Write the cfg for the given method to a dot file in the tests directory with the given name
     *
     * @param m
     *            to write CFG for
     * @param filename
     *            file to be saved in "tests" directory with .dot appended
     */
    public static final void writeToFile(IMethod m, String filename) {
        writeToFile(AnalysisUtil.getIR(m), filename);
    }

    /**
     * Write out the control flow graph in graphviz dot format with the code for the basic block written on each node.
     *
     * @param writer
     *            writer to write the code to
     * @param prefix
     *            prepended to each instruction (e.g. "\t" to indent)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place each instruction on a new line)
     * @throws IOException
     *             writer issues
     */
    public final void writeVerbose(Writer writer, String prefix, String postfix) throws IOException {
        this.verbose = true;
        write(writer, prefix, postfix);
    }

    /**
     * Write all the edges in the CFG to the given writer
     *
     * @param writer
     *            writer to write the graph to
     * @throws IOException
     *             writer issues
     */
    private void writeGraph(Writer writer) throws IOException {
        SSACFG cfg = ir.getControlFlowGraph();
        for (ISSABasicBlock current : cfg) {
            String currentString = getStringForBasicBlock(current);
            for (ISSABasicBlock succ : cfg.getNormalSuccessors(current)) {
                String edge;
                if (getUnreachableSuccessors(current, cfg).contains(succ)) {
                    edge = "UNREACHABLE " + getNormalEdgeLabel(current, succ, ir);
                } else {
                    edge = getNormalEdgeLabel(current, succ, ir);
                }
                String succString = getStringForBasicBlock(succ);
                String edgeLabel = "[label=\"" + edge + "\"]";
                writer.write("\t\"" + currentString + "\" -> \"" + succString + "\" " + edgeLabel + ";\n");
            }

            for (ISSABasicBlock succ : cfg.getExceptionalSuccessors(current)) {
                String edge;
                if (getUnreachableSuccessors(current, cfg).contains(succ)) {
                    edge = "EX UNREACHABLE";
                } else {
                    edge = getExceptionEdgeLabel(current, succ, ir);
                }
                String succString = getStringForBasicBlock(succ);
                String edgeLabel = "[label=\"" + edge + "\"]";
                writer.write("\t\"" + currentString + "\" -> \"" + succString + "\" " + edgeLabel + ";\n");
            }
        }
    }

    /**
     * Get the string representation of the basic block
     *
     * @param bb
     *            basic block to get a string for
     * @return string for <code>bb</code>
     */
    private String getStringForBasicBlock(ISSABasicBlock bb) {
        String bbString = bbStrings.get(bb);
        if (bbString == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("BB" + bb.getNumber() + "\\l");
            if (bb.isEntryBlock()) {
                sb.append("ENTRY\\l");
                for (int j = 0; j < ir.getNumberOfParameters(); j++) {
                    sb.append(pp.valString(ir.getParameter(j)) + " = param(" + j + ")\\l");
                }
            }
            if (bb.isExitBlock()) {
                sb.append("EXIT\\l");
            }

            if (verbose) {
                for (SSAInstruction i : bb) {
                    sb.append(getPrefix(i) + pp.instructionString(i) + getPostfix(i));
                }
            }
            bbString = escapeDot(sb.toString());
            bbStrings.put(bb, bbString);
        }
        return bbString;
    }

    /**
     * Properly escape the string so it will be properly formatted in dot
     *
     * @param s
     *            string to escape
     * @return dot-safe string
     */
    private static String escapeDot(String s) {
        return s.replace("\"", "\\\"").replace("\n", "\\l");
    }

    /**
     * Can be overridden by subclass
     *
     * @param i
     * @return
     */
    protected String getPostfix(@SuppressWarnings("unused") SSAInstruction i) {
        return postfix;
    }

    /**
     * Can be overridden by subclass
     *
     * @param i
     * @return
     */
    protected String getPrefix(@SuppressWarnings("unused") SSAInstruction i) {
        return prefix;
    }

    /**
     * Can be overridden by subclass
     *
     * @param bb
     * @param cfg
     * @return
     */
    @SuppressWarnings({ "unused", "static-method" })
    protected Set<ISSABasicBlock> getUnreachableSuccessors(ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return Collections.emptySet();
    }

    /**
     * Can be overridden by subclass
     *
     * @param source
     * @param target
     * @param ir
     * @return
     */
    @SuppressWarnings("unused")
    protected String getExceptionEdgeLabel(ISSABasicBlock source, ISSABasicBlock target, IR ir) {
        return ExitType.EXCEPTIONAL.toString();
    }

    /**
     * Can be overridden by subclass
     *
     * @param source
     * @param target
     * @param ir
     * @return
     */
    protected String getNormalEdgeLabel(ISSABasicBlock source, ISSABasicBlock target, IR ir) {
        if (DataFlow.getLastInstruction(source) instanceof SSAConditionalBranchInstruction) {
            ISSABasicBlock trueSucc = DataFlow.getTrueSuccessor(source, ir.getControlFlowGraph());
            ISSABasicBlock falseSucc = DataFlow.getFalseSuccessor(source, ir.getControlFlowGraph());
            if (trueSucc != null && target.equals(trueSucc)) {
                return "TRUE";
            }
            else if (falseSucc != null && target.equals(falseSucc)) {
                return "FALSE";
            } else {
                throw new RuntimeException("Something besides a true or false successor for a branch.");
            }

        }
        return ExitType.NORMAL.toString();
    }
}
