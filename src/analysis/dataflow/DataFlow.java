package analysis.dataflow;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.SingletonValueMap;
import util.WorkQueue;
import util.print.PrettyPrinter;

import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.util.intset.IntIterator;

/**
 * Base class for a context-sensitive, flow-sensitive, path-sensitive
 * intra-procedural data-flow analysis
 * 
 * @param FlowItem
 *            type for the data-flow facts propagated by this analysis, must
 *            have hashCode and equals defined
 */
public abstract class DataFlow<FlowItem> {

    /**
     * True if this is a forward analysis false if this is a backward analysis
     */
    private final boolean forward;
    /**
     * Map from basic block to output data-flow item map
     */
    private final Map<ISSABasicBlock, Map<Integer, FlowItem>> bbToOutItems;

    /**
     * Create a new dataflow
     * 
     * @param ir
     *            Code for the method the data-flow is performed over
     */
    public DataFlow(boolean forward) {
        this.forward = forward;
        bbToOutItems = new HashMap<>();
    }

    /**
     * Transfer function for the data-flow, takes a list of facts and produces a
     * new fact (possibly different for each successor) after analyzing the
     * given basic block.
     * 
     * @param inItems
     *            data-flow items on input edges
     * @param cfg
     *            Control flow graph this analysis is performed over
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact after
     *         handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flow(Set<FlowItem> inItems, SSACFG cfg, ISSABasicBlock current);

    /**
     * Perform the dataflow on the given IR
     * 
     * @param ir
     *            code for the method to perform the dataflow for
     */
    protected void dataflow(IR ir) {
        // TODO Polyglot computes SCCs and iterates through them

        SSACFG g = ir.getControlFlowGraph();

        WorkQueue<ISSABasicBlock> q = new WorkQueue<>();
        q.add(g.entry());

        while (!q.isEmpty()) {
            ISSABasicBlock current = q.poll();
            Set<FlowItem> inItems = new LinkedHashSet<>(getNumPreds(current, g));
            Map<Integer, FlowItem> oldOutItems = getOutItems(current);

            // Get all out items for predecessors
            Iterator<ISSABasicBlock> preds = getPreds(current, g);
            while (preds.hasNext()) {
                ISSABasicBlock pred = preds.next();
                Map<Integer, FlowItem> items = getOutItems(pred);
                if (items != null) {
                    FlowItem item = items.get(current.getNumber());
                    // TODO should we allow item == null?
                    if (item != null) {
                        inItems.add(item);
                    } else {
                        String edgeType = getExceptionalSuccs(pred, g).contains(current) ? "exceptional" : "normal";
                        System.err.println("null data-flow item for "
                                + PrettyPrinter.parseMethod(ir.getMethod().getReference()) + " from " + "BB"
                                + g.getNumber(pred) + " to BB" + g.getNumber(current) + " on " + edgeType + " edge");
                    }
                }
            }

            Map<Integer, FlowItem> outItems = flow(inItems, g, current);
            assert outItems != null : "Null out items for " + PrettyPrinter.basicBlockString(ir, current, "", "\n")
                    + " with inputs: " + inItems;

            if (oldOutItems != outItems && (oldOutItems == null || !oldOutItems.equals(outItems))) {
                // The data-flow computed a new item for this peer
                // add all successors to the queue
                q.addAll(getSuccs(current, g));
                putOutItems(current, outItems);
            }
        }
    }

    /**
     * Get all successors of the given basic block. If this is a forward
     * analysis these will be the successors in the control flow graph. If this
     * is a backward analysis then these will be the predecessors in the control
     * flow graph.
     * 
     * @param bb
     *            basic block to get the successors for
     * @param cfg
     *            control flow graph
     * @return iterator for data-flow successors of the given basic block
     */
    protected Iterator<ISSABasicBlock> getSuccs(ISSABasicBlock bb, SSACFG cfg) {
        return forward ? cfg.getSuccNodes(bb) : cfg.getPredNodes(bb);
    }

    /**
     * Get all normal (i.e. non-exceptional) successors of the given basic
     * block. If this is a forward analysis these will be the successors in the
     * control flow graph. If this is a backward analysis then these will be the
     * normal predecessors in the control flow graph.
     * 
     * @param bb
     *            basic block to get the successors for
     * @param cfg
     *            control flow graph
     * @return the basic blocks which are data-flow successors of bb via normal
     *         control flow edges
     */
    protected Collection<ISSABasicBlock> getNormalSuccs(ISSABasicBlock bb, SSACFG cfg) {
        return forward ? cfg.getNormalSuccessors(bb) : cfg.getNormalPredecessors(bb);
    }

    /**
     * Get all exceptional successors of the given basic block. If this is a
     * forward analysis these will be the successors in the control flow graph.
     * If this is a backward analysis then these will be the exceptional
     * predecessors in the control flow graph.
     * 
     * @param bb
     *            basic block to get the successors for
     * @param cfg
     *            control flow graph
     * @return the basic blocks which are data-flow successors of bb via
     *         exceptional control flow edges
     */
    protected Collection<ISSABasicBlock> getExceptionalSuccs(ISSABasicBlock bb, SSACFG cfg) {
        return forward ? cfg.getExceptionalSuccessors(bb) : cfg.getExceptionalPredecessors(bb);
    }

    /**
     * Get all successors of the given basic block. If this is a forward
     * analysis these will be the successors in the control flow graph. If this
     * is a backward analysis then these will be the predecessors in the control
     * flow graph.
     * 
     * @param bb
     *            basic block to get the successors for
     * @param cfg
     *            control flow graph
     * @return iterator for data-flow successors of the given basic block
     */
    protected IntIterator getSuccNodeNumbers(ISSABasicBlock bb, SSACFG cfg) {
        return forward ? cfg.getSuccNodeNumbers(bb).intIterator() : cfg.getPredNodeNumbers(bb).intIterator();
    }

    /**
     * Get a mapping from the ID for each successor of the given basic block to
     * a single data-flow fact. This is used when analyzing a basic block that
     * sends the same item to each of its successors.
     * 
     * @param item
     *            item to associate with each successor
     * @param bb
     *            basic block to get the successors ids for
     * @param cfg
     *            control flow graph
     * @return mapping from each successor id to the single given value
     */
    protected Map<Integer, FlowItem> itemToMap(FlowItem item, ISSABasicBlock bb, SSACFG cfg) {
        Set<Integer> succs = new LinkedHashSet<>();
        IntIterator iter = getSuccNodeNumbers(bb, cfg);
        while (iter.hasNext()) {
            succs.add(iter.next());
        }
        return new SingletonValueMap<>(succs, item);
    }

    /**
     * Get all predecessors for the given basic block. If this is a forward
     * analysis this will be the predecessors in the control flow graph. If this
     * is a backward analysis then this will be the successors in the control
     * flow graph.
     * 
     * @param bb
     *            basic block to get the predecessors for
     * @param cfg
     *            control flow graph
     * @return data-flow predecessors for the given basic block.
     */
    private Iterator<ISSABasicBlock> getPreds(ISSABasicBlock bb, SSACFG cfg) {
        return forward ? cfg.getPredNodes(bb) : cfg.getSuccNodes(bb);
    }

    /**
     * Get number of predecessors for the given basic block. If this is a
     * forward analysis this will be the predecessors in the control flow graph.
     * If this is a backward analysis then this will be the successors in the
     * control flow graph.
     * 
     * @param bb
     *            basic block to get the number of predecessors for
     * @param cfg
     *            control flow graph
     * @return number of data-flow predecessors for the given basic block.
     */
    private int getNumPreds(ISSABasicBlock bb, SSACFG cfg) {
        return forward ? cfg.getPredNodeCount(bb) : cfg.getSuccNodeCount(bb);
    }

    /**
     * Associate a basic block with a data-flow item
     * 
     * @param basic
     *            basic block
     * @param outItems
     *            data-flow facts for targets of edges leaving the basic block
     */
    private void putOutItems(ISSABasicBlock bb, Map<Integer, FlowItem> outItems) {
        bbToOutItems.put(bb, outItems);
    }

    /**
     * Get the data-flow item produced when exiting the given basic block
     * 
     * @param bb
     *            basic block to get the exit item for
     * 
     * @return data-flow fact for targets of edges leaving the basic block
     */
    private Map<Integer, FlowItem> getOutItems(ISSABasicBlock bb) {
        return bbToOutItems.get(bb);
    }
}
