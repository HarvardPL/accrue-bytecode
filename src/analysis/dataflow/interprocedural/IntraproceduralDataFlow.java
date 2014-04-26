package analysis.dataflow.interprocedural;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
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
 * @param <F>
 *            Type of the data-flow facts
 */
public abstract class IntraproceduralDataFlow<F> extends InstructionDispatchDataFlow<F> {

    /**
     * Input facts for each instruction analyzed
     */
    protected final Map<SSAInstruction, Set<F>> inputItems = new LinkedHashMap<>();
    /**
     * Output fact for each basic block analyzed
     */
    protected final Map<ISSABasicBlock, Map<Integer, F>> outputItems = new LinkedHashMap<>();
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
     * Results of a reachability analysis
     */
    private final ReachabilityResults reachable;

    /**
     * Intra-procedural part of an inter-procedural data-flow analysis
     * 
     * @param currentNode
     *            node this will analyze
     * @param interProc
     *            used to get results for calls to other call graph nodes
     */
    public IntraproceduralDataFlow(CGNode currentNode, InterproceduralDataFlow<F> interProc,
                                    ReachabilityResults reachable) {
        // only forward inter-procedural data-flows are supported
        super(true);
        this.currentNode = currentNode;
        this.interProc = interProc;
        this.cg = interProc.getCallGraph();
        this.ptg = interProc.getPointsToGraph();
        this.reachable = reachable;
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
    protected Map<Integer, F> flowInstruction(SSAInstruction i, Set<F> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        inputItems.put(i, inItems);
        return super.flowInstruction(i, inItems, cfg, current);
    }

    @Override
    protected Map<Integer, F> flow(Set<F> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
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
    protected abstract Map<Integer, F> call(SSAInvokeInstruction i, Set<F> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb);

    @Override
    protected void postBasicBlock(Set<F> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock justProcessed, Map<Integer, F> outItems) {

        for (ISSABasicBlock normalSucc : cfg.getNormalSuccessors(justProcessed)) {
            if (outItems.get(normalSucc.getGraphNodeId()) == null) {
                System.err.println("No fact for normal successor from BB"
                                                + justProcessed.getGraphNodeId()
                                                + " to BB"
                                                + normalSucc.getGraphNodeId()
                                                + "\n"
                                                + PrettyPrinter.basicBlockString(currentNode.getIR(), justProcessed,
                                                                                "\t", "\n"));
            }
        }

        for (ISSABasicBlock exceptionalSucc : cfg.getExceptionalSuccessors(justProcessed)) {
            if (outItems.get(exceptionalSucc.getGraphNodeId()) == null) {
                System.err.println("No fact for exceptional successor from BB"
                                                + justProcessed.getGraphNodeId()
                                                + " to BB"
                                                + exceptionalSucc.getGraphNodeId()
                                                + "\n"
                                                + PrettyPrinter.basicBlockString(currentNode.getIR(), justProcessed,
                                                                                "\t", "\n"));
            }
        }

        outputItems.put(justProcessed, outItems);
    }

    @Override
    protected void post(IR ir) {
        output = new LinkedHashMap<>();

        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
        ISSABasicBlock exit = cfg.exit();
        Integer exitNum = exit.getGraphNodeId();
        Set<F> normals = new LinkedHashSet<>();
        for (ISSABasicBlock bb : cfg.getNormalPredecessors(exit)) {
            if (outputItems.get(bb).get(exitNum) != null) {
                normals.add(outputItems.get(bb).get(exitNum));
            }
        }
        output.put(ExitType.NORMAL, confluence(normals));

        Set<F> exceptions = new LinkedHashSet<>();
        for (ISSABasicBlock bb : cfg.getExceptionalPredecessors(exit)) {
            if (outputItems.get(bb).get(exitNum) != null) {
                exceptions.add(outputItems.get(bb).get(exitNum));
            }
        }
        output.put(ExitType.EXCEPTIONAL, confluence(exceptions));
    }

    /**
     * Merge given facts to create a new data-flow fact and map each successor
     * node number to that fact.
     * 
     * @param facts
     *            facts to merge
     * @param cfg
     *            current control flow graph
     * @param bb
     *            current basic block
     * @return map with the same merged value for each key
     */
    protected Map<Integer, F> mergeAndCreateMap(Set<F> facts, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock bb) {
        F fact = confluence(facts);
        return factToMap(fact, bb, cfg);
    }

    /**
     * Get the abstract locations for a non-static field
     * 
     * @param receiver
     *            value number for the local variable for the receiver of a
     *            field access
     * @param field
     *            field
     * @return set of abstract locations for the field
     */
    protected Set<AbstractLocation> getLocationsForNonStaticField(int receiver, FieldReference field) {
        Set<InstanceKey> pointsTo = ptg.getPointsToSet(getReplica(receiver));
        if (pointsTo.isEmpty()) {
            throw new RuntimeException("Field target doesn't point to anything. "
                                            + PrettyPrinter.parseType(field.getDeclaringClass()) + "."
                                            + field.getName());
        }

        Set<AbstractLocation> ret = new LinkedHashSet<>();
        for (InstanceKey o : pointsTo) {
            AbstractLocation loc = AbstractLocation.createNonStatic(o, field);
            ret.add(loc);
        }
        return ret;
    }

    /**
     * Get the abstract locations for the contents of an array
     * 
     * @param arary
     *            value number for the local variable for the array
     * @return set of abstract locations for the contents of the array
     */
    protected Set<AbstractLocation> getLocationsForArrayContents(int array) {
        Set<InstanceKey> pointsTo = ptg.getPointsToSet(getReplica(array));
        if (pointsTo.isEmpty()) {
            ptg.getPointsToSet(getReplica(array));
            throw new RuntimeException("Array doesn't point to anything. "
                                            + PrettyPrinter.valString(currentNode.getIR(), array) + " in "
                                            + PrettyPrinter.parseCGNode(currentNode));
        }

        Set<AbstractLocation> ret = new LinkedHashSet<>();
        for (InstanceKey o : pointsTo) {
            AbstractLocation loc = AbstractLocation.createArrayContents(o);
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

    /**
     * Results of an inter-procedural reachability analysis
     * 
     * @return results of a reachability analysis
     */
    protected ReachabilityResults getReachable() {
        return reachable;
    }
}
