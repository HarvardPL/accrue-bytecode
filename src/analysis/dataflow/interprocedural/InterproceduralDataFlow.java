package analysis.dataflow.interprocedural;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import analysis.dataflow.ExitType;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.util.AbstractLocation;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariable;
import analysis.pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.FieldReference;

/**
 * Intra-procedural part of an inter-procedural data-flow analysis
 * 
 * @param <FlowItem>
 *            Type of the data-flow facts
 */
public abstract class InterproceduralDataFlow<FlowItem> extends InstructionDispatchDataFlow<FlowItem> {

    /**
     * Input items for each instruction analyzed
     */
    protected final Map<SSAInstruction, Set<FlowItem>> inputItems = new LinkedHashMap<>();
    /**
     * Output item for each basic block analyzed
     */
    protected final Map<ISSABasicBlock, Map<Integer, FlowItem>> outputItems = new LinkedHashMap<>();
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
    protected final InterproceduralDataFlowManager<FlowItem> manager;
    /**
     * Data-flow fact before analyzing the entry block
     */
    private FlowItem input;
    /**
     * Data-flow facts upon exit, one for normal termination and one for
     * exception
     */
    private Map<ExitType, FlowItem> output;

    public InterproceduralDataFlow(CGNode currentNode, InterproceduralDataFlowManager<FlowItem> manager) {
        // only forward inter-procedural data-flows are supported
        super(true);
        this.currentNode = currentNode;
        this.manager = manager;
        this.cg = manager.getCallGraph();
        this.ptg = manager.getPointsToGraph();
    }

    /**
     * Kick off an intra-procedural data-flow analysis for the current call
     * graph node with the given initial data-flow item, this will use the
     * {@link InterproceduralDataFlowManager} to get results for calls to other
     * call graph nodes.
     * 
     * @param initial
     *            initial data-flow item
     */
    public Map<ExitType, FlowItem> dataflow(FlowItem initial) {
        this.input = initial;
        dataflow(currentNode.getIR());
        return this.output;
    }

    @Override
    protected Map<Integer, FlowItem> flowInstruction(SSAInstruction i, Set<FlowItem> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        inputItems.put(i, inItems);
        return super.flowInstruction(i, inItems, cfg, current);
    }

    @Override
    protected Map<Integer, FlowItem> flow(Set<FlowItem> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock current) {
        if (current.isEntryBlock()) {
            inItems = Collections.singleton(input);
        }
        assert inItems != null && !inItems.isEmpty();
        
        // TODO make sure static initializers get analyzed
        return super.flow(inItems, cfg, current);
    }

    /**
     * Process a procedure invocation
     * 
     * @param inItems
     *            data-flow facts on edges entering the call
     * @param instruction
     *            invocation instruction in the caller
     * @param ir
     *            caller IR
     * @param cfg
     *            caller control flow graph
     * @param bb
     *            basic block containing the call
     * @return Data-flow fact for each successor after processing the call
     */
    protected abstract Map<Integer, FlowItem> call(SSAInvokeInstruction instruction, Set<FlowItem> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb);

    @Override
    protected void postBasicBlock(Set<FlowItem> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock justProcessed, Map<Integer, FlowItem> outItems) {
        outputItems.put(justProcessed, outItems);
    }

    @Override
    protected void post(IR ir) {

        output = new LinkedHashMap<>();

        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
        ISSABasicBlock exit = cfg.exit();
        Integer exitNum = exit.getGraphNodeId();
        Set<FlowItem> normals = new LinkedHashSet<>();
        for (ISSABasicBlock bb : cfg.getNormalPredecessors(exit)) {
            normals.add(outputItems.get(bb).get(exitNum));
        }
        output.put(ExitType.NORM_TERM, confluence(normals));

        Set<FlowItem> exceptions = new LinkedHashSet<>();
        for (ISSABasicBlock bb : cfg.getExceptionalPredecessors(exit)) {
            exceptions.add(outputItems.get(bb).get(exitNum));
        }
        output.put(ExitType.EXCEPTION, confluence(exceptions));
    }

    /**
     * Merge given items to create a new data-flow item and map each successor
     * node number to that item.
     * 
     * @param items
     *            items to merge
     * @param cfg
     *            current control flow graph
     * @param bb
     *            current basic block
     * @return map with the same merged value for each key
     */
    protected Map<Integer, FlowItem> mergeAndCreateMap(Set<FlowItem> items,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb) {
        FlowItem item = confluence(items);
        return itemToMap(item, bb, cfg);
    }

    /**
     * Get the abstract locations for a field access
     * 
     * @param receiver
     *            value number for the local variable for the receiver of a
     *            field access
     * @param field
     *            field being accessed
     * @return set of abstract locations for the field
     */
    protected Set<AbstractLocation> locationsForField(int receiver, FieldReference field) {
        Set<InstanceKey> pointsTo = ptg.getPointsToSet(getReplica(receiver));
        Set<AbstractLocation> ret = new LinkedHashSet<>();
        for (InstanceKey o : pointsTo) {
            AbstractLocation loc = new AbstractLocation(o, field);
            ret.add(loc);
        }
        return ret;
    }

    /**
     * Get the reference variable replica for the given local variable in the
     * current context
     * 
     * @param local
     *            value number of the local variable
     * 
     * @return Reference variable replica in the current context for the local
     */
    protected ReferenceVariableReplica getReplica(int local) {
        ReferenceVariable rv = ptg.getLocal(local, currentNode.getIR());
        return new ReferenceVariableReplica(currentNode.getContext(), rv);
    }
}
