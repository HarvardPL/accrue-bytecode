package util.print;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import analysis.dataflow.DataFlow;
import analysis.dataflow.ExitType;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.intset.IntIterator;

/**
 * Write out a control flow graph for a specific method
 */
public class CFGWriter extends DataFlow<OrderedPair<ExitType, String>> {

    /**
     * Output will be written to this writer
     */
    private Writer writer;
    /**
     * Code for method to be written
     */
    private final IR ir;
    /**
     * String to prepend to instructions
     */
    private String prefix;
    /**
     * String to append to instructions
     */
    private String postfix;
    /**
     * If true then code will be included in CFG, otherwise it will just be
     * basic block numbers
     */
    private boolean verbose;

    /**
     * Create a writer for the given IR
     * 
     * @param ir
     *            IR for the method to be printed
     * @param writer
     *            output will be written to this writer; will not be closed
     */
    public CFGWriter(IR ir) {
        super(true /* forward analysis */);
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
        this.writer = writer;
        this.prefix = prefix;
        this.postfix = postfix;

        double spread = 1.0;
        writer.write("strict digraph G {\n" + "node [shape=record];\n" + "nodesep=" + spread + ";\n" + "ranksep="
                + spread + ";\n" + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n" + "edge [fontsize=10]"
                + ";\n");

        dataflow(ir);

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
     * The flow item a pair of (edge label,node label)
     * <p>
     * {@inheritDoc}
     */
    @Override
    protected final Map<Integer, OrderedPair<ExitType, String>> flow(Set<OrderedPair<ExitType, String>> inItems,
            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        try (StringWriter sw = new StringWriter()) {
            String exit = current.isExitBlock() ? "\\lEXIT" : "";
            String entry = current.isEntryBlock() ? "\\lENTRY" : "";
            sw.write("BB" + current.getNumber() + entry + exit + "\\l");

            if (verbose) {
                for (SSAInstruction i : current) {
                    sw.write(getPrefix(i) + PrettyPrinter.instructionString(ir, i) + getPostfix(i));
                }
            }

            String bbString = escapeDot(sw.toString());
            for (OrderedPair<ExitType, String> predPair : inItems) {
                String predNode = predPair.snd();
                String predEdge = predPair.fst().toString();
                String edgeLabel = "[label=\"" + predEdge + "\"]";
                writer.write("\t\"" + predNode + "\" -> \"" + bbString + "\" " + edgeLabel + ";\n");
            }
            Map<Integer, OrderedPair<ExitType, String>> result = new LinkedHashMap<>();

            Collection<ISSABasicBlock> normalSuccs = getNormalSuccs(current, cfg);
            IntIterator iter = getSuccNodeNumbers(current, cfg).intIterator();
            while (iter.hasNext()) {
                Integer bbNum = iter.next();
                ExitType edge = normalSuccs.contains(cfg.getNode(bbNum)) ? ExitType.NORM_TERM : ExitType.EXCEPTION;
                OrderedPair<ExitType, String> item = new OrderedPair<>(edge, bbString);
                result.put(bbNum, item);
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException();
        }
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

    @Override
    protected void post(IR ir) {
        // Intentionally blank
    }
    
    /**
     * Can be overridden by subclass
     * @param i
     * @return
     */
    public String getPostfix(SSAInstruction i) {
        return postfix;
    }
    
    public String getPrefix(SSAInstruction i) {
        return prefix;
    }
}
