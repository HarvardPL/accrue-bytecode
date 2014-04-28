package util.print;

import java.io.Writer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import analysis.dataflow.DataFlow;
import analysis.dataflow.util.Unit;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;

/**
 * Print the code for a given method
 */
public class IRWriter extends DataFlow<Unit> {
    /**
     * Set of visited basic blocks to prevent revisiting
     */
    private final Set<ISSABasicBlock> visited = new HashSet<>();
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
     * Create a writer for the given IR
     * 
     * @param ir
     *            IR for the method to be printed
     * @param writer
     *            output will be written to this writer; will not be closed
     */
    public IRWriter(IR ir) {
        super(true /* forward analysis */);
        this.ir = ir;
    }

    /**
     * Write out the pretty printed code to the given writer
     * 
     * @param writer
     *            writer to write the code to, will not be closed
     * @param prefix
     *            prepended to each instruction (e.g. "\t" to indent)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place
     *            each instruction on a new line)
     */
    public void write(Writer writer, String prefix, String postfix) {
        this.writer = writer;
        this.prefix = prefix;
        this.postfix = postfix;
        dataflow(ir);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flow(Set<Unit> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock current) {
        if (!visited.contains(current)) {
            PrettyPrinter.writeBasicBlock(ir, current, writer, prefix, postfix);
            visited.add(current);
        }
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected void post(IR ir) {
        // Intentionally left blank
    }
}
