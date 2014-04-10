package analysis.dataflow.interprocedural;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.SingletonValueMap;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariable;
import analysis.pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.intset.IntIterator;

/**
 * Inter-procedural extension of a data-flow analysis
 * 
 * @param <FlowItem>
 *            Type of the data-flow facts
 */
public abstract class InterproceduralDataFlow<FlowItem> extends InstructionDispatchDataFlow<FlowItem> {

    protected final CallGraph cg;
    protected final PointsToGraph ptg;
    protected final CGNode currentNode;
    
    private Set<FlowItem> input;
    private Map<Integer, FlowItem> output;


    /**
     * Create a new Interprocedural data-flow
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
    protected abstract Map<Integer, FlowItem> call(Set<FlowItem> inItems, SSAInvokeInstruction instruction, IR ir,
            SSACFG cfg, ISSABasicBlock bb);

    /**
     * Perform the data-flow
     */
    protected final void run() {
        super.dataflow(currentNode.getIR());
    }

    @Override
    protected void post(IR ir) {
        // TODO collect the results from the entries to the exit block put them
        // in out items
        // might need confluence here?
    }

    protected abstract FlowItem confluence(Set<FlowItem> items);

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
    protected Map<Integer, FlowItem> mergeAndCreateMap(Set<FlowItem> items, SSACFG cfg, ISSABasicBlock bb) {
        FlowItem item = confluence(items);
        return itemToMap(item, bb, cfg);
    }
    
    /**
     * Get the abstract locations for a field access
     * 
     * @param reciever
     *            value number for the local variable for the receiver of a
     *            field access
     * @param field
     *            field being accessed
     * @return set of abstract locations for the field
     */
    protected Set<AbstractLocation> LocationsForField(int reciever, FieldReference field) {
        ReferenceVariable rv = ptg.getLocal(reciever, currentNode.getIR());
        Set<InstanceKey> pointsTo = ptg.getPointsToSet(new ReferenceVariableReplica(currentNode.getContext(), rv));
        Set<AbstractLocation> ret = new LinkedHashSet<>();
        for (InstanceKey o : pointsTo) {
            AbstractLocation loc = new AbstractLocation(o, field);
            ret.add(loc);
        }
        return ret;
    }
}
