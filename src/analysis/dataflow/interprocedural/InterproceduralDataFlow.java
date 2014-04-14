package analysis.dataflow.interprocedural;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import analysis.dataflow.InstructionDispatchDataFlow;
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
 * Inter-procedural extension of a data-flow analysis
 * 
 * @param <FlowItem>
 *            Type of the data-flow facts
 */
public abstract class InterproceduralDataFlow<FlowItem> extends InstructionDispatchDataFlow<FlowItem> {

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
     * Data-flow fact before analyzing the entry block
     */
    private FlowItem input;
    /**
     * Data-flow facts upon exit, TODO map from something to something else
     */
    private FlowItem output;

    /**
     * Create a new Inter-procedural data-flow
     * 
     * @param df
     *            intra-procedural data-flow this extends
     */
    public InterproceduralDataFlow(CGNode currentNode, CallGraph cg, PointsToGraph ptg) {
        // only forward interprocedural data-flows are supported
        super(true);
        this.currentNode = currentNode;
        this.cg = cg;
        this.ptg = ptg;
    }

    /**
     * Kick off an inter-procedural data-flow analysis for the current call
     * graph node with the given initial data-flow item
     * 
     * @param initial
     *            initial data-flow item
     */
    public FlowItem dataflow(FlowItem initial) {
        this.input = initial;
        dataflow(currentNode.getIR());
        return this.output;
    }

    @Override
    protected Map<Integer, FlowItem> flow(Set<FlowItem> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
            ISSABasicBlock current) {
        if (current.isEntryBlock()) {
            inItems = Collections.singleton(input);
        }
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
    protected void post(IR ir) {
        // TODO collect the results from the entries to the exit block put them
        // in out items
        // might need confluence here?
    }

    /**
     * Join the given set of data-flow items to get a new item
     * 
     * @param items
     *            items to merge
     * @return new data-flow item computed by merging the items in the given set
     */
    protected abstract FlowItem confluence(Set<FlowItem> items);

    /**
     * Join the two given data-flow items to produce a new item
     * 
     * @param item1
     *            first data-flow item
     * @param item2
     *            second data-flow item
     * @return item computed by merging item1 and item2
     */
    protected final FlowItem confluence(FlowItem item1, FlowItem item2) {
        if (item1 == null) {
            return item2;
        } 
        if (item2 == null) {
            return item1;
        }
        
        Set<FlowItem> items = new LinkedHashSet<>();
        items.add(item1);
        items.add(item2);
        return confluence(items);
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
