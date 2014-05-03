package analysis.dataflow.interprocedural;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.util.AbstractValue;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;

/**
 * Intra-procedural part of an inter-procedural data-flow analysis
 * 
 * @param <F>
 *            Type of the data-flow facts
 */
public abstract class IntraproceduralDataFlow<F extends AbstractValue<F>> extends InstructionDispatchDataFlow<F> {

    /**
     * Procedure call graph
     */
    protected final CallGraph cg;
    /**
     * Points-to graph
     */
    protected final PointsToGraph ptg;
    /**
     * Call graph node currently being analyzed
     */
    protected final CGNode currentNode;
    /**
     * Inter-procedural manager
     */
    protected final InterproceduralDataFlow<F> interProc;
    /**
     * Data-flow fact before analyzing the entry block
     */
    private F input;
    /**
     * Data-flow facts upon exit, one for normal termination and one for
     * exception
     */
    private Map<ExitType, F> output;

    /**
     * Intra-procedural part of an inter-procedural data-flow analysis
     * 
     * @param currentNode
     *            node this will analyze
     * @param interProc
     *            used to get results for calls to other call graph nodes
     */
    public IntraproceduralDataFlow(CGNode currentNode, InterproceduralDataFlow<F> interProc) {
        // only forward inter-procedural data-flows are supported
        super(true);
        this.currentNode = currentNode;
        this.interProc = interProc;
        this.cg = interProc.getCallGraph();
        this.ptg = interProc.getPointsToGraph();
    }

    /**
     * Kick off an intra-procedural data-flow analysis for the current call
     * graph node with the given initial data-flow fact, this will use the
     * {@link InterproceduralDataFlow} to get results for calls to other call
     * graph nodes.
     * 
     * @param initial
     *            initial data-flow fact
     */
    public Map<ExitType, F> dataflow(F initial) {
        this.input = initial;
        dataflow(currentNode.getIR());
        return this.output;
    }

    @Override
    protected Map<ISSABasicBlock, F> flow(Set<F> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock current) {
        if (current.isEntryBlock()) {
            inItems = Collections.singleton(input);
        }
        return super.flow(inItems, cfg, current);
    }

    /**
     * Process a procedure invocation
     * 
     * @param inItems
     *            data-flow facts on edges entering the call
     * @param i
     *            invocation instruction in the caller
     * @param ir
     *            caller IR
     * @param cfg
     *            caller control flow graph
     * @param bb
     *            caller basic block containing the call
     * @return Data-flow fact for each successor after processing the call
     */
    protected abstract Map<ISSABasicBlock, F> call(SSAInvokeInstruction i, Set<F> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb);

    @Override
    protected void post(IR ir) {
        output = new LinkedHashMap<>();

        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
        ISSABasicBlock exit = cfg.exit();
        Set<F> normals = new LinkedHashSet<>();
        for (ISSABasicBlock bb : cfg.getNormalPredecessors(exit)) {
            if (!isUnreachable(bb, exit)) {
                normals.add(getAnalysisRecord(bb).getOutput().get(exit));
            }
        }

        if (normals.isEmpty()) {
            // There are no normal predecessors of the exit so this procedure
            // cannot terminate normally
            output.put(ExitType.NORMAL, null);
        } else {
            output.put(ExitType.NORMAL, confluence(normals, exit));
        }

        if (cfg.getExceptionalPredecessors(exit).isEmpty()) {
            // No exceptions can be thrown by this procedure
            output.put(ExitType.EXCEPTIONAL, null);
            return;
        }

        Set<F> exceptions = new LinkedHashSet<>();
        for (ISSABasicBlock bb : cfg.getExceptionalPredecessors(exit)) {
            if (!isUnreachable(bb, exit)) {
                exceptions.add(getAnalysisRecord(bb).getOutput().get(exit));
            }
        }

        if (exceptions.isEmpty()) {
            // There are no reachable exception edges into the exit node so no
            // exceptions can be thrown by this procedure
            output.put(ExitType.EXCEPTIONAL, null);
        } else {
            output.put(ExitType.EXCEPTIONAL, confluence(exceptions, exit));
        }
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        return interProc.getReachabilityResults().isUnreachable(source, target, currentNode);
    }
}
