package util.print;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.InstructionType;
import analysis.dataflow.util.ExitType;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
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
     * If true then code will be included in CFG, otherwise it will just be
     * basic block numbers
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
     * Create a writer for the given IR
     * 
     * @param ir
     *            IR for the method to be printed
     * @param writer
     *            output will be written to this writer; will not be closed
     */
    public CFGWriter(IR ir) {
        this.ir = ir;
    }

    /**
     * Write out the pretty printed code to the given writer
     * 
     * @param writer
     *            writer to write the code to
     * @param prefix
     *            prepended to each instruction (e.g. "\t" to indent)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place
     *            each instruction on a new line)
     * @throws IOException
     *             writer issues
     */
    public final void write(Writer writer, String prefix, String postfix) throws IOException {
        this.prefix = prefix;
        this.postfix = postfix;
        double spread = 1.0;
        writer.write("strict digraph G {\n" + "node [shape=record];\n" + "nodesep=" + spread + ";\n" + "ranksep="
                                        + spread + ";\n" + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n"
                                        + "edge [fontsize=10]" + ";\n");

        writeGraph(writer);

        writer.write("\n};\n");
    }

    /**
     * Write out the control flow graph in graphviz dot format with the code for
     * the basic block written on each node.
     * 
     * @param writer
     *            writer to write the code to
     * @param prefix
     *            prepended to each instruction (e.g. "\t" to indent)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place
     *            each instruction on a new line)
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
                String succString = getStringForBasicBlock(succ);
                String edgeLabel = "[label=\"" + getNormalEdgeLabel(current, succ, ir) + "\"]";
                writer.write("\t\"" + currentString + "\" -> \"" + succString + "\" " + edgeLabel + ";\n");
            }

            for (ISSABasicBlock succ : cfg.getExceptionalSuccessors(current)) {
                if (getUnreachableExceptions(current, cfg).contains(succ)) {
                    continue;
                }
                String succString = getStringForBasicBlock(succ);
                String edgeLabel = "[label=\"" + getExceptionEdgeLabel(current, succ, ir) + "\"]";
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
            }
            if (bb.isExitBlock()) {
                sb.append("EXIT\\l");
            }

            if (verbose) {
                for (SSAInstruction i : bb) {
                    sb.append(getPrefix(i) + PrettyPrinter.instructionString(i, ir) + getPostfix(i));
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
    private String escapeDot(String s) {
        return s.replace("\"", "\\\"").replace("\n", "\\l");
    }

    /**
     * Can be overridden by subclass
     * 
     * @param i
     * @return
     */
    protected String getPostfix(SSAInstruction i) {
        return postfix;
    }

    /**
     * Can be overridden by subclass
     * 
     * @param i
     * @return
     */
    protected String getPrefix(SSAInstruction i) {
        return prefix;
    }

    /**
     * Can be overridden by subclass
     * 
     * @param bb
     * @param cfg
     * @return
     */
    protected Set<ISSABasicBlock> getUnreachableExceptions(ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        if (bb.getLastInstructionIndex() >= 0
                                        && InstructionType.forInstruction(bb.getLastInstruction()) == InstructionType.NEW_OBJECT) {
            // This instruction can only throw errors, which we are not handling
            List<ISSABasicBlock> bbs = cfg.getExceptionalSuccessors(bb);
            assert bbs.size() == 1;
            return Collections.singleton(bbs.get(0));
        }

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
    protected String getExceptionEdgeLabel(ISSABasicBlock source, ISSABasicBlock target, IR ir) {
        return ExitType.EXCEPTION.toString();
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
        return ExitType.NORM_TERM.toString();
    }
}
