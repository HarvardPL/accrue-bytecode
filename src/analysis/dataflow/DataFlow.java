package analysis.dataflow;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import util.SingletonValueMap;
import util.print.PrettyPrinter;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.impl.InvertedGraph;
import com.ibm.wala.util.graph.traverse.SCCIterator;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;

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
     * Perform the dataflow on the given IR
     * 
     * @param ir
     *            code for the method to perform the dataflow for
     */
    protected final void dataflow(IR ir) {
        ControlFlowGraph<SSAInstruction, ISSABasicBlock> g = ir.getControlFlowGraph();
        Graph<ISSABasicBlock> flowGraph = g;
        if (!forward) {
            flowGraph = new InvertedGraph<ISSABasicBlock>(flowGraph);
        }
        
        // Compute SCCs and iterate through them
        SCCIterator<ISSABasicBlock> sccs = new SCCIterator<>(flowGraph);
        
        
        while (sccs.hasNext()) {
            Set<ISSABasicBlock> scc = sccs.next();
            boolean changed = true;
            int iterations = 0;
            
            // Loop over the strongly connected component until a fixed point is reached
            while (changed) {
                changed = false;
                for (ISSABasicBlock current : scc) {
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
                                System.err.println("null data-flow item in "
                                        + PrettyPrinter.parseMethod(ir.getMethod().getReference()) + " from BB"
                                        + g.getNumber(pred) + " to BB" + g.getNumber(current) + " on " + edgeType + " edge");
                            }
                        }
                    }

                    Map<Integer, FlowItem> outItems = flow(inItems, g, current);
                    assert outItems != null : "Null out items for " + PrettyPrinter.basicBlockString(ir, current, "", "\n")
                            + " with inputs: " + inItems;

                    if (oldOutItems != outItems && (oldOutItems == null || !oldOutItems.equals(outItems))) {
                        changed = true;
                        putOutItems(current, outItems);
                    }
                }
                iterations++;
                if (iterations >= 100) {
                    throw new RuntimeException("Analyzed the same SCC 100 times for method: " + PrettyPrinter.parseMethod(ir.getMethod().getReference()));
                }
            }
        }

        post(ir);
    }

    /**
     * Perform any data-flow specific operations after analyzing the procedure
     * 
     * @param ir
     *            procedure being analyzed
     */
    protected abstract void post(IR ir);

    /**
     * Transfer function for the data-flow, takes a list of facts and produces a
     * new fact (possibly different for each successor) after analyzing the
     * given basic block.
     * 
     * @param inItems
     *            data-flow items on input edges
     * @param cfg
     *            Control flow graph this analysis is performed over
     * @param i
     *            current instruction
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current basic block
     */
    protected abstract Map<Integer, FlowItem> flow(Set<FlowItem> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> g, ISSABasicBlock current);

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
    protected final Iterator<ISSABasicBlock> getSuccs(ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
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
    protected final Collection<ISSABasicBlock> getNormalSuccs(ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
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
    protected final Collection<ISSABasicBlock> getExceptionalSuccs(ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
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
    protected final IntSet getSuccNodeNumbers(ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return forward ? cfg.getSuccNodeNumbers(bb) : cfg.getPredNodeNumbers(bb);
    }

    /**
     * Get an unmodifiable mapping from the ID for each successor of the given
     * basic block to a single data-flow fact. This is used when analyzing a
     * basic block that sends the same item to each of its successors.
     * 
     * @param item
     *            item to associate with each successor
     * @param bb
     *            basic block to get the successors ids for
     * @param cfg
     *            control flow graph
     * @return unmodifiable mapping from each successor id to the single given
     *         value
     */
    protected final Map<Integer, FlowItem> itemToMap(FlowItem item, ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        Set<Integer> succs = new LinkedHashSet<>();
        IntIterator iter = getSuccNodeNumbers(bb, cfg).intIterator();
        while (iter.hasNext()) {
            succs.add(iter.next());
        }
        return new SingletonValueMap<>(succs, item);
    }

    /**
     * Get a modifiable mapping from the ID for each successor of the given
     * basic block to a single data-flow fact. This is used when analyzing a
     * basic block that sends the same item to each of its successors.
     * 
     * @param item
     *            item to associate with each successor
     * @param bb
     *            basic block to get the successors ids for
     * @param cfg
     *            control flow graph
     * @return modifiable mapping from each successor id to the single given
     *         value
     */
    protected final Map<Integer, FlowItem> itemToModifiableMap(FlowItem item, ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        Map<Integer, FlowItem> res = new LinkedHashMap<>();
        IntIterator iter = getSuccNodeNumbers(bb, cfg).intIterator();
        while (iter.hasNext()) {
            res.put(iter.next(), item);
        }
        return res;
    }

    /**
     * Get a mapping from the ID for each successor of the given basic block to
     * data-flow facts. There will be a single fact for all normal successors
     * and another (possibly different) fact for exceptional successors.
     * 
     * @param normalItem
     *            item to associate with each normal successor
     * @param exceptionalItem
     *            item to associate with each exceptional successor
     * @param bb
     *            basic block to get the successors ids for
     * @param cfg
     *            control flow graph
     * @return mapping from each successor id to the corresponding data-flow
     *         item
     */
    protected final Map<Integer, FlowItem> itemToMapWithExceptions(FlowItem normalItem, FlowItem exceptionItem,
            ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        Map<Integer, FlowItem> ret = new LinkedHashMap<>();
        for (ISSABasicBlock succ : getNormalSuccs(bb, cfg)) {
            ret.put(succ.getGraphNodeId(), normalItem);
        }
        for (ISSABasicBlock succ : getExceptionalSuccs(bb, cfg)) {
            ret.put(succ.getGraphNodeId(), exceptionItem);
        }
        return ret;
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
    private final Iterator<ISSABasicBlock> getPreds(ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
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
    protected final int getNumPreds(ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return forward ? cfg.getPredNodeCount(bb) : cfg.getSuccNodeCount(bb);
    }

    /**
     * Get number of successors for the given basic block. If this is a forward
     * analysis this will be the successors in the control flow graph. If this
     * is a backward analysis then this will be the predecessors in the control
     * flow graph.
     * 
     * @param bb
     *            basic block to get the number of successors for
     * @param cfg
     *            control flow graph
     * @return number of data-flow successors for the given basic block.
     */
    protected final int getNumSuccs(ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return forward ? cfg.getSuccNodeCount(bb) : cfg.getPredNodeCount(bb);
    }

    /**
     * Associate a basic block with a data-flow item
     * 
     * @param basic
     *            basic block
     * @param outItems
     *            data-flow facts for targets of edges leaving the basic block
     */
    private final void putOutItems(ISSABasicBlock bb, Map<Integer, FlowItem> outItems) {
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
    protected final Map<Integer, FlowItem> getOutItems(ISSABasicBlock bb) {
        return bbToOutItems.get(bb);
    }

    /**
     * Get the first block to be analyzed in the data-flow. For a forward
     * analysis this is the entry block and for a backward analysis this is the
     * exit block.
     * 
     * @param cfg
     *            control flow graph the data-flow is performed over
     * @return the initial node to analyze
     */
    protected final ISSABasicBlock getInitialBlock(ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return forward ? cfg.entry() : cfg.exit();
    }

    /**
     * Get the control-flow successor on the "true" edge leaving a branch
     * 
     * @param bb
     *            branching basic block
     * @param cfg
     *            control-flow graph
     * @return ID for basic block on the outgoing "true" edge
     */
    protected final int getTrueSuccessor(ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return getTrueFalseSuccessors(bb, cfg).fst();
    }

    /**
     * Get the control-flow successor on the "false" edge leaving a branch
     * 
     * @param bb
     *            branching basic block
     * @param cfg
     *            control-flow graph
     * @return ID for basic block on the outgoing "false" edge
     */
    protected final int getFalseSuccessor(ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return getTrueFalseSuccessors(bb, cfg).snd();
    }

    /**
     * Get the control-flow successors on the "true" and "false" edges leaving a
     * branch
     * 
     * @param bb
     *            branching basic block
     * @param cfg
     *            control-flow graph
     * @return pair of basic block number for "true" successor and "false"
     *         successor in that order
     */
    private final OrderedPair<Integer, Integer> getTrueFalseSuccessors(ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        if (cfg.getSuccNodeCount(bb) != 2) {
            throw new RuntimeException("Branches have exactly 2 successors. This has " + cfg.getSuccNodeCount(bb));
        }
        int trueBranch = -1;
        int falseBranch = bb.getGraphNodeId() + 1;
        IntSet set = cfg.getSuccNodeNumbers(bb);
        assert set.contains(falseBranch) : "False branch should be the next block number.";

        IntIterator iter = set.intIterator();
        while (iter.hasNext()) {
            int branch = iter.next();
            if (branch != falseBranch) {
                assert trueBranch == -1 : "No false branch?";
                trueBranch = branch;
            }
        }
        return new OrderedPair<Integer, Integer>(trueBranch, falseBranch);
    }
}
