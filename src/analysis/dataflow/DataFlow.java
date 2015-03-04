package analysis.dataflow;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.InstructionType;
import util.OrderedPair;
import util.SingletonValueMap;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.ReverseIterator;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.impl.InvertedGraph;
import com.ibm.wala.util.graph.traverse.SCCIterator;

/**
 * Base class for a context-sensitive, flow-sensitive, path-sensitive intra-procedural data-flow analysis
 *
 * @param <F>
 *            type for the data-flow facts propagated by this analysis, must have hashCode and equals defined
 */
public abstract class DataFlow<F> {

    /**
     * True if this is a forward analysis false if this is a backward analysis
     */
    private final boolean forward;
    /**
     * Map from basic block to record containing analysis input and results
     */
    private final Map<ISSABasicBlock, AnalysisRecord<F>> bbToRecord;
    /**
     * determines printing volume
     */
    protected int outputLevel = 0;
    /**
     * Output level for all dataflow subclasses
     */
    protected static int staticOutputLevel = 0;

    /**
     * Create a new intra-procedural data-flow
     *
     * @param ir
     *            Code for the method the data-flow is performed over
     */
    public DataFlow(boolean forward) {
        this.forward = forward;
        bbToRecord = new HashMap<>();
    }

    /**
     * Perform the dataflow on the given IR
     *
     * @param ir
     *            code for the method to perform the dataflow for
     */
    protected final void dataflow(IR ir) {
        if (outputLevel >= 1) {
            CFGWriter.writeToFile(ir);
        }
        ControlFlowGraph<SSAInstruction, ISSABasicBlock> g = ir.getControlFlowGraph();
        Graph<ISSABasicBlock> flowGraph = g;
        if (!forward) {
            flowGraph = new InvertedGraph<>(flowGraph);
        }

        // Compute SCCs and iterate through them
        SCCIterator<ISSABasicBlock> sccs = new SCCIterator<>(flowGraph);
        while (sccs.hasNext()) {
            Set<ISSABasicBlock> scc = sccs.next();
            boolean changed = true;
            int iterations = 0;

            // Loop over the strongly connected component until a fixed point is
            // reached
            while (changed) {
                changed = false;
                for (ISSABasicBlock current : scc) {
                    Set<F> inItems = new LinkedHashSet<>(getNumPreds(current, g));
                    AnalysisRecord<F> previousResults = getAnalysisRecord(current);
                    Map<ISSABasicBlock, F> oldOutItems = previousResults == null ? null : previousResults.getOutput();

                    // If all incoming edges unreachable then this block is
                    // unreachable and we do not need to analyze
                    // Note that all entry blocks are considered reachable
                    boolean isBasicBlockunreachable = !current.isEntryBlock();

                    // Get all out items for predecessors
                    Iterator<ISSABasicBlock> preds = getPreds(current, g);
                    while (preds.hasNext()) {
                        ISSABasicBlock pred = preds.next();

                        boolean isUnreachableEdge = isUnreachable(pred, current);
                        isBasicBlockunreachable &= isUnreachableEdge;
                        if (isUnreachableEdge || getAnalysisRecord(pred) == null) {
                            // There is no input on this edge if current is
                            // unreachable from the predecessor

                            // The output items could be null for a predecessor
                            // if there is a back edge and that predecessor has
                            // not been analyzed yet
                            continue;
                        }

                        Map<ISSABasicBlock, F> items = getAnalysisRecord(pred).getOutput();
                        F item = items.get(current);
                        inItems.add(item);
                        if (item == null) {
                            // We do not allow null output for reachable
                            // successors
                            String edgeType = getExceptionalSuccs(pred, g).contains(current) ? "exceptional" : "normal";
                            throw new RuntimeException("null data-flow item in "
                                                            + PrettyPrinter.methodString(ir.getMethod()) + " from BB"
                                                            + g.getNumber(pred) + " to BB" + g.getNumber(current)
                                                            + " on " + edgeType + " edge");
                        }
                        inItems.add(item);
                    }

                    Map<ISSABasicBlock, F> outItems = null;
                    if (isBasicBlockunreachable) {
                        // Do not analyze this block if it cannot be reached
                        // from any predecessor
                        if (outputLevel >= 2) {
                            System.err.println("UNREACHABLE basic block: BB" + current.getNumber() + " in "
                                                            + PrettyPrinter.methodString(ir.getMethod()));
                        }
                        outItems = flowUnreachableBlock(inItems, current, g);
                        if (outItems == null) {
                            continue;
                        }
                    }

                    if (previousResults != null && existingResultsSuitable(inItems, previousResults)) {
                        // no need to reanalyze we can re-use the results
                        continue;
                    }

                    if (outputLevel >= 3) {
                        System.err.println("FLOWING BB" + current.getNumber() + ": in "
                                                        + PrettyPrinter.methodString(ir.getMethod()));
                    }

                    if (inItems.isEmpty() && getPreds(current, g).hasNext()) {
                        if (outputLevel >= 2) {
                            System.err.print("NO INPUT for BB" + current.getGraphNodeId() + " in "
                                                            + PrettyPrinter.methodString(ir.getMethod())
                                                            + " SKIPPING. Preds: [");
                            Iterator<ISSABasicBlock> iter = getPreds(current, g);
                            ISSABasicBlock first = iter.next();
                            System.err.print("BB" + first.getNumber());
                            while (iter.hasNext()) {
                                System.err.print(", BB" + iter.next().getNumber());
                            }
                            System.err.println("]");
                        }
                        // This is reachable and there is no input which means
                        // we are not starting at a BB in this SCC that is
                        // connected to the previous SCC. Continue looking until
                        // we do.
                        continue;
                    }

                    if (!isBasicBlockunreachable) {
                        outItems = flow(inItems, g, current);
                    }

                    assert outItems != null : "Null out items for " + current.getNumber() + " with inputs: " + inItems;

                    AnalysisRecord<F> newResults = new AnalysisRecord<>(inItems, outItems);
                    putRecord(current, newResults);

                    if (oldOutItems == null || !oldOutItems.equals(outItems)) {
                        if (outputLevel >= 3) {
                            System.err.println("OUTPUT BB" + current.getNumber() + ":\n\t" + outItems);
                        }
                        changed = true;
                    }
                }
                iterations++;
                if (iterations >= 100) {
                    throw new RuntimeException("Analyzed the same SCC 100 times for method: "
                                                    + PrettyPrinter.methodString(ir.getMethod()));
                }
            }
        }
        post(ir);
    }

    /**
     * Handle a block that the analysis has determined is unreachable. This could be because it has no predecesors in
     * the CFG or because a more complex analysis has determined that it is unreachable for another reason (e.g. if it
     * is a catch block for an exception that a precise exception analysis determines can never be thrown).
     *
     * @param inItems input facts (if any, could be null or empty)
     * @param current basic block that is unreachable and needs handling
     * @param g control flow graph
     * @return facts for each successor basic block, or null if this block should be ignored entirely
     */
    @SuppressWarnings("unused")
    protected Map<ISSABasicBlock, F> flowUnreachableBlock(Set<F> inItems, ISSABasicBlock current,
                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> g) {
        // By default ignore unreachable blocks
        return null;
    }

    /**
     * Determine whether existing analysis results can be reused.
     *
     * @param newInput
     *            new input items
     * @param previousResults
     *            non-null results that have been already computed
     * @return whether the existing results can be re-used
     */
    protected boolean existingResultsSuitable(Set<F> newInput, AnalysisRecord<F> previousResults) {
        assert previousResults != null;
        // Currently insists that the input was identical, but could be
        // overridden in a subclass
        return previousResults.getInput().equals(newInput);
    }

    /**
     * Perform any data-flow specific operations after analyzing the procedure
     *
     * @param ir
     *            procedure being analyzed
     */
    protected abstract void post(IR ir);

    /**
     * Transfer function for the data-flow, takes a set of facts and produces a new fact (possibly different for each
     * successor) after analyzing the given basic block.
     *
     * @param inItems
     *            data-flow facts on input edges
     * @param cfg
     *            Control flow graph this analysis is performed over
     * @param i
     *            current instruction
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that edge after handling the current basic
     *         block
     */
    protected abstract Map<ISSABasicBlock, F> flow(Set<F> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> g,
                                    ISSABasicBlock current);

    /**
     * Get all successors of the given basic block. If this is a forward analysis these will be the successors in the
     * control flow graph. If this is a backward analysis then these will be the predecessors in the control flow graph.
     *
     * @param bb
     *            basic block to get the successors for
     * @param cfg
     *            control flow graph
     * @return iterator for data-flow successors of the given basic block
     */
    protected final Iterator<ISSABasicBlock> getSuccs(ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return forward ? cfg.getSuccNodes(bb) : cfg.getPredNodes(bb);
    }

    /**
     * Get all normal (i.e. non-exceptional) successors of the given basic block. If this is a forward analysis these
     * will be the successors in the control flow graph. If this is a backward analysis then these will be the normal
     * predecessors in the control flow graph.
     *
     * @param bb
     *            basic block to get the successors for
     * @param cfg
     *            control flow graph
     * @return the basic blocks which are data-flow successors of bb via normal control flow edges
     */
    protected final Collection<ISSABasicBlock> getNormalSuccs(ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return forward ? cfg.getNormalSuccessors(bb) : cfg.getNormalPredecessors(bb);
    }

    /**
     * Get all exceptional successors of the given basic block. If this is a forward analysis these will be the
     * successors in the control flow graph. If this is a backward analysis then these will be the exceptional
     * predecessors in the control flow graph.
     *
     * @param bb
     *            basic block to get the successors for
     * @param cfg
     *            control flow graph
     * @return the basic blocks which are data-flow successors of bb via exceptional control flow edges
     */
    protected final Collection<ISSABasicBlock> getExceptionalSuccs(ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return forward ? cfg.getExceptionalSuccessors(bb) : cfg.getExceptionalPredecessors(bb);
    }

    /**
     * Get an unmodifiable mapping from each successor of the given basic block to a single data-flow fact. This is used
     * when analyzing a basic block that sends the same item to each of its successors.
     *
     * @param fact data-flow fact to associate with each successor
     * @param current basic block to get the successors ids for
     * @param cfg control flow graph
     * @return unmodifiable mapping from each successor to the single given value
     */
    protected final Map<ISSABasicBlock, F> factToMap(F fact, ISSABasicBlock current,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        Set<ISSABasicBlock> succs = new LinkedHashSet<>();
        Iterator<ISSABasicBlock> iter = getSuccs(current, cfg);
        while (iter.hasNext()) {
            succs.add(iter.next());
        }
        return new SingletonValueMap<>(succs, fact);
    }

    /**
     * Get a modifiable mapping from each successor of the given basic block to a single data-flow fact.
     *
     * @param fact
     *            fact to associate with each successor
     * @param current
     *            basic block to get the successors ids for
     * @param cfg
     *            control flow graph
     * @return modifiable mapping from each successor id to the single given value
     */
    protected final Map<ISSABasicBlock, F> factToModifiableMap(F fact, ISSABasicBlock current,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        Map<ISSABasicBlock, F> res = new LinkedHashMap<>();
        Iterator<ISSABasicBlock> iter = getSuccs(current, cfg);
        while (iter.hasNext()) {
            res.put(iter.next(), fact);
        }
        return res;
    }

    /**
     * Get a mapping from each successor of the given basic block to data-flow facts. There will be a single fact for
     * all normal successors and another (possibly different) fact for exceptional successors.
     *
     * @param normalItem
     *            fact to associate with each normal successor
     * @param exceptionalItem
     *            fact to associate with each exceptional successor
     * @param current
     *            basic block this computes the output for
     * @param cfg
     *            control flow graph
     * @return mapping from each successor to the corresponding data-flow fact
     */
    protected final Map<ISSABasicBlock, F> factsToMapWithExceptions(F normalItem, F exceptionItem, ISSABasicBlock current,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        Map<ISSABasicBlock, F> ret = new LinkedHashMap<>();
        for (ISSABasicBlock succ : getNormalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                ret.put(succ, normalItem);
            }
        }
        for (ISSABasicBlock succ : getExceptionalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                ret.put(succ, exceptionItem);
            }
        }
        return ret;
    }

    /**
     * Get all predecessors for the given basic block. If this is a forward analysis this will be the predecessors in
     * the control flow graph. If this is a backward analysis then this will be the successors in the control flow
     * graph.
     *
     * @param bb
     *            basic block to get the predecessors for
     * @param cfg
     *            control flow graph
     * @return data-flow predecessors for the given basic block.
     */
    private final Iterator<ISSABasicBlock> getPreds(ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return forward ? cfg.getPredNodes(bb) : cfg.getSuccNodes(bb);
    }

    /**
     * Get number of predecessors for the given basic block. If this is a forward analysis this will be the predecessors
     * in the control flow graph. If this is a backward analysis then this will be the successors in the control flow
     * graph.
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
     * Get number of successors for the given basic block. If this is a forward analysis this will be the successors in
     * the control flow graph. If this is a backward analysis then this will be the predecessors in the control flow
     * graph.
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
     * Associate a basic block with a record of analysis results
     *
     * @param basic
     *            basic block
     * @param record
     *            input and output data-flow facts for the basic block
     */
    private final void putRecord(ISSABasicBlock bb, AnalysisRecord<F> record) {
        bbToRecord.put(bb, record);
    }

    /**
     * Get a record for a previously run analysis for the given basic block, returns null if the block has never been
     * analyzed
     *
     * @param bb
     *            basic block to get the record for
     *
     * @return input and output data-flow facts for the basic block
     */
    protected final AnalysisRecord<F> getAnalysisRecord(ISSABasicBlock bb) {
        return bbToRecord.get(bb);
    }

    /**
     * Get the first block to be analyzed in the data-flow. For a forward analysis this is the entry block and for a
     * backward analysis this is the exit block.
     *
     * @param cfg
     *            control flow graph the data-flow is performed over
     * @return the initial node to analyze
     */
    protected final ISSABasicBlock getInitialBlock(ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return forward ? cfg.entry() : cfg.exit();
    }

    /**
     * Get the control-flow successor on the "true" edge leaving a branch. Note that for a backward analysis this is a
     * data-flow predecessor.
     *
     * @param bb branching basic block
     * @param cfg control-flow graph
     * @return basic block on the outgoing "true" edge (could be null)
     */
    public static final ISSABasicBlock getTrueSuccessor(ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return getTrueFalseSuccessors(bb, cfg).fst();
    }

    /**
     * Get the control-flow successor on the "false" edge leaving a branch. Note that for a backward analysis this is a
     * data-flow predecessor.
     *
     * @param bb branching basic block
     * @param cfg control-flow graph
     * @return basic block on the outgoing "false" edge (could be null)
     */
    public static final ISSABasicBlock getFalseSuccessor(ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        return getTrueFalseSuccessors(bb, cfg).snd();
    }

    /**
     * Get the control-flow successors on the "true" and "false" edges leaving a branch
     *
     * @param bb branching basic block
     * @param cfg control-flow graph
     * @return pair of basic block for "true" successor and "false" successor in that order, either could be null if
     *         that branch doesn't exist (this is rare and is probably due to some compiler optimization).
     */
    private static final OrderedPair<ISSABasicBlock, ISSABasicBlock> getTrueFalseSuccessors(ISSABasicBlock bb,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        if (staticOutputLevel > 0 && cfg.getSuccNodeCount(bb) != 2) {
            System.err.println("WARNING: Conditional branch, BB" + bb.getNumber() + ", has " + cfg.getSuccNodeCount(bb)
                    + " successor(s) in method " + PrettyPrinter.methodString(cfg.getMethod()));
        }

        ISSABasicBlock trueBranch = null;
        ISSABasicBlock falseBranch = null;
        int falseBranchNum = bb.getGraphNodeId() + 1;

        Iterator<ISSABasicBlock> iter = cfg.getSuccNodes(bb);
        while (iter.hasNext()) {
            ISSABasicBlock branch = iter.next();
            if (branch.getNumber() != falseBranchNum) {
                trueBranch = branch;
            } else {
                falseBranch = branch;
            }
        }

        return new OrderedPair<>(trueBranch, falseBranch);
    }

    /**
     * Determine whether a data-flow edge is unreachable, e.g. it could be the normal edge leaving a call that can only
     * throw an exception
     *
     * @param source
     *            edge source
     * @param target
     *            edge target
     */
    protected abstract boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target);

    /**
     * Get the successor basic blocks that may be able to be reached by throwing a particular type of exception.
     * <p>
     * This set is computed conservatively (i.e. this may report reachable successors that are not actually reachable at
     * run-time).
     *
     * @param exType
     *            exception type
     * @param cfg
     *            control flow graph
     * @param current
     *            basic bloc that throws the exception
     * @return set of basic blocks that may
     */
    protected static Set<ISSABasicBlock> getSuccessorsForExceptionType(TypeReference exType,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO redo exception successors in a cleaner way
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();

        Set<ISSABasicBlock> result = new LinkedHashSet<>();

        IClass thrown = cha.lookupClass(exType);
        assert thrown != null;
        InstructionType throwerType = InstructionType.forInstruction(getLastInstruction(current));
        boolean isCaught = false;

        // Find successor catch blocks
        List<ISSABasicBlock> catchBlocks = cfg.getExceptionalSuccessors(current);

        // See if there is a catch block that catches this exception
        for (ISSABasicBlock cb : catchBlocks) {
            if (cb.isExitBlock()) {
                // handle exit blocks after this loop
                continue;
            }
            Iterator<TypeReference> caughtTypes = cb.getCaughtExceptionTypes();
            while (caughtTypes.hasNext()) {
                TypeReference caughtType = caughtTypes.next();
                IClass caught = cha.lookupClass(caughtType);
                if (TypeRepository.isAssignableFrom(caught, thrown)) {
                    result.add(cb);
                    isCaught = true;
                } else if (throwerType.isInvoke() && TypeRepository.isAssignableFrom(thrown, caught)) {
                    // The catch type is a subtype of the exception being thrown
                    // so it could be caught (due to imprecision for exceptions
                    // thrown by native calls)

                    // TODO keep track of imprecise exception types
                    result.add(cb);
                }
            }
        }

        if (!isCaught) {
            // might not be caught so it might flow to the exit node
            result.add(cfg.exit());
        }

        return result;
    }

    /**
     * Set the level of output
     *
     * @param level
     *            level of the output, higher means more output
     */
    public void setOutputLevel(int level) {
        outputLevel = level;
    }

    /**
     * Get the output level
     *
     * @return integer verbosity level
     */
    protected int getOutputLevel() {
        return outputLevel;
    }

    /**
     * Get the last instruction of the given basic block, null if there is are no instructions in the basic block.
     * <p>
     * There are "null" instructions inserted into the SSA basic blocks to keep the instruction indexes the same as for
     * WALA's stack-based Shrike intermediate language, so we cannot just call bb.getLastInstruction() as this might
     * return null if the instruction at the last Shrike index was translated away when compiling to SSA.
     *
     * @param bb
     *            basic block to get the last instruction for
     * @return the last instruction of the basic block, null if the basic block contains no instructions (e.g. if it is
     *         an entry or exit block).
     */
    public static SSAInstruction getLastInstruction(ISSABasicBlock bb) {
        SSAInstruction last = null;
        Iterator<SSAInstruction> iter = bb.iterator();
        if (!iter.hasNext()) {
            // There are no instructions return null
            return null;
        }
        Iterator<SSAInstruction> reverse = ReverseIterator.reverse(iter);
        while (reverse.hasNext()) {
            last = reverse.next();
            if (last != null) {
                return last;
            }
        }
        assert false : "No last instruction in non-empty basic block.";
        return null;
    }

    /**
     * Analysis input and output for a particular basic block, used to determine if re-analysis is necessary
     */
    protected static class AnalysisRecord<Fact> {

        /**
         * Input facts
         */
        private final Set<Fact> input;
        /**
         * Output facts, one for each successor
         */
        private final Map<ISSABasicBlock, Fact> output;

        /**
         * Create a new record of analysis results for a particular basic block
         *
         * @param input
         *            Input facts
         * @param output
         *            Output facts, one for each successor
         */
        public AnalysisRecord(Set<Fact> input, Map<ISSABasicBlock, Fact> output) {
            this.input = input;
            this.output = output;
        }

        /**
         * Input facts
         *
         * @return Set of data-flow input facts
         */
        public Set<Fact> getInput() {
            return input;
        }

        /**
         * Output facts, one for each successor
         *
         * @return Output facts, one for each successor
         */
        public Map<ISSABasicBlock, Fact> getOutput() {
            return output;
        }

        @Override
        public String toString() {
            return "{input=" + input + ", output=" + output + "}";
        }
    }
}
