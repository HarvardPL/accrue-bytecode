package analysis.dataflow;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import util.InstructionType;
import util.print.PrettyPrinter;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction.IOperator;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAComparisonInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAConversionInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSAMonitorInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;

/**
 * Data-flow that dispatched based on the type of instruction being processed
 * 
 * <F> Type of data-flow facts this analysis computes
 */
public abstract class InstructionDispatchDataFlow<F> extends DataFlow<F> {
    /**
     * Map from Instruction to record containing analysis results
     */
    private final Map<SSAInstruction, AnalysisRecord<F>> insToRecord;

    /**
     * Data-flow that dispatches based on instruction type
     * 
     * @param forward
     */
    public InstructionDispatchDataFlow(boolean forward) {
        super(forward);
        insToRecord = new LinkedHashMap<>();
    }

    /**
     * Join the given (non-empty) set of data-flow facts to get a new item
     * 
     * @param facts
     *            non-empty set of data-flow facts to merge
     * @param bb
     *            Basic block we are merging for
     * @return new data-flow item computed by merging the facts in the given set
     */
    protected abstract F confluence(Set<F> facts, ISSABasicBlock bb);

    /**
     * Get a record for a previously run analysis for the given instruction,
     * returns null if the block has never been analyzed
     * 
     * @param i
     *            instruction to get the record for
     * 
     * @return input and output data-flow facts for the instruction
     */
    protected AnalysisRecord<F> getAnalysisRecord(SSAInstruction i) {
        return insToRecord.get(i);
    }

    /**
     * Compute data-flow facts for each instruction in the basic block. The
     * handler is chosen based on the type of the instruction.
     * <p>
     * {@inheritDoc}
     */
    @Override
    protected Map<ISSABasicBlock, F> flow(Set<F> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock current) {
        Set<F> previousItems = inItems;
        Map<ISSABasicBlock, F> outItems = null;
        SSAInstruction last = null;
        if (current.iterator().hasNext()) {
            last = getLastInstruction(current);
        } else {
            // empty block, just pass through the input
            outItems = factToMap(confluence(inItems, current), current, cfg);
        }
        for (SSAInstruction i : current) {
            assert last != null : "last instruction is null";
            if (i == last) {
                // this is the last instruction of the block
                outItems = flowInstruction(i, previousItems, cfg, current);
            } else {
                // Pass the results of this "flow" into the next
                previousItems = new HashSet<>(flowInstruction(i, inItems, cfg, current).values());
            }
        }

        assert outItems != null : "Null output for " + current;
        postBasicBlock(inItems, cfg, current, outItems);
        return outItems;
    }

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param inItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow facts after analyzing this instruction
     */
    protected Map<ISSABasicBlock, F> flowInstruction(SSAInstruction i, Set<F> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        SSAInstruction last = getLastInstruction(current);
        Map<ISSABasicBlock, F> output;
        if (i == last) {
            // this is the last instruction of the block
            output = flowLastInstruction(i, inItems, cfg, current);
        } else {
            // Pass the results of this "flow" into the next
            output = factToMap(flowNonBranchingInstruction(i, inItems, cfg, current), current, cfg);
        }
        insToRecord.put(i, new AnalysisRecord<>(inItems, output));
        return output;
    }

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param inItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    private F flowNonBranchingInstruction(SSAInstruction i, Set<F> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        InstructionType type = InstructionType.forInstruction(i);
        switch (type) {
        case BINARY_OP:
            return flowBinaryOp((SSABinaryOpInstruction) i, inItems, cfg, current);
        case COMPARISON:
            return flowComparison((SSAComparisonInstruction) i, inItems, cfg, current);
        case CONVERSION:
            return flowConversion((SSAConversionInstruction) i, inItems, cfg, current);
        case GET_STATIC:
            return flowGetStatic((SSAGetInstruction) i, inItems, cfg, current);
        case GET_CAUGHT_EXCEPTION:
            return flowGetCaughtException((SSAGetCaughtExceptionInstruction) i, inItems, cfg, current);
        case INSTANCE_OF:
            return flowInstanceOf((SSAInstanceofInstruction) i, inItems, cfg, current);
        case PHI:
            return flowPhi((SSAPhiInstruction) i, inItems, cfg, current);
        case PUT_STATIC:
            return flowPutStatic((SSAPutInstruction) i, inItems, cfg, current);
        case UNARY_NEG_OP:
            return flowUnaryNegation((SSAUnaryOpInstruction) i, inItems, cfg, current);
            // These should be the last instruction in a block
        case ARRAY_LENGTH:
        case ARRAY_LOAD:
        case ARRAY_STORE:
        case CHECK_CAST:
        case CONDITIONAL_BRANCH:
        case GET_FIELD:
        case GOTO:
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_STATIC:
        case INVOKE_VIRTUAL:
        case LOAD_METADATA:
        case MONITOR:
        case NEW_OBJECT:
        case NEW_ARRAY:
        case PUT_FIELD:
        case RETURN:
        case SWITCH:
        case THROW:
            // The above all branch and should be the last instruction in a
            // basic block
            break;
        }
        throw new RuntimeException("Incorrect handling of instruction type: " + type);
    }

    /**
     * Data-flow transfer function for the last, possibly branching, instruction
     * of a basic block
     * 
     * @param i
     *            instruction
     * @param inItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    private Map<ISSABasicBlock, F> flowLastInstruction(SSAInstruction i, Set<F> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        InstructionType type = InstructionType.forInstruction(i);
        switch (type) {
        case ARRAY_LENGTH:
            return flowArrayLength((SSAArrayLengthInstruction) i, inItems, cfg, current);
        case ARRAY_LOAD:
            return flowArrayLoad((SSAArrayLoadInstruction) i, inItems, cfg, current);
        case ARRAY_STORE:
            return flowArrayStore((SSAArrayStoreInstruction) i, inItems, cfg, current);
        case CHECK_CAST:
            return flowCheckCast((SSACheckCastInstruction) i, inItems, cfg, current);
        case CONDITIONAL_BRANCH:
            return flowConditionalBranch((SSAConditionalBranchInstruction) i, inItems, cfg, current);
        case GET_FIELD:
            return flowGetField((SSAGetInstruction) i, inItems, cfg, current);
        case GOTO:
            return flowGoto((SSAGotoInstruction) i, inItems, cfg, current);
        case INVOKE_INTERFACE:
            return flowInvokeInterface((SSAInvokeInstruction) i, inItems, cfg, current);
        case INVOKE_SPECIAL:
            return flowInvokeSpecial((SSAInvokeInstruction) i, inItems, cfg, current);
        case INVOKE_STATIC:
            return flowInvokeStatic((SSAInvokeInstruction) i, inItems, cfg, current);
        case INVOKE_VIRTUAL:
            return flowInvokeVirtual((SSAInvokeInstruction) i, inItems, cfg, current);
        case LOAD_METADATA:
            return flowLoadMetadata((SSALoadMetadataInstruction) i, inItems, cfg, current);
        case MONITOR:
            return flowMonitor((SSAMonitorInstruction) i, inItems, cfg, current);
        case NEW_OBJECT:
            return flowNewObject((SSANewInstruction) i, inItems, cfg, current);
        case NEW_ARRAY:
            return flowNewArray((SSANewInstruction) i, inItems, cfg, current);
        case PUT_FIELD:
            return flowPutField((SSAPutInstruction) i, inItems, cfg, current);
        case RETURN:
            return flowReturn((SSAReturnInstruction) i, inItems, cfg, current);
        case SWITCH:
            return flowSwitch((SSASwitchInstruction) i, inItems, cfg, current);
        case THROW:
            return flowThrow((SSAThrowInstruction) i, inItems, cfg, current);
        case BINARY_OP:
        case COMPARISON:
        case CONVERSION:
        case GET_CAUGHT_EXCEPTION:
        case GET_STATIC:
        case INSTANCE_OF:
        case PHI:
        case PUT_STATIC:
        case UNARY_NEG_OP:
            assert cfg.getSuccNodeCount(current) == 1 : "Instructions of this type should never branch: " + type;
            return factToMap(flowNonBranchingInstruction(i, inItems, cfg, current), current, cfg);
        }
        throw new RuntimeException("Invalid instruction type: " + PrettyPrinter.getSimpleClassName(i));
    }

    /**
     * Method called after processing a basic block. By default it ensures that
     * all reachable successors get results.
     * <p>
     * Subclasses should override for more functionality if desired.
     * 
     * @param inItems
     *            data-flow facts before processing basic block
     * @param cfg
     *            control flow graph
     * @param justProcessed
     *            basic block that was just analyzed
     * @param outItems
     *            data-flow item for each edge
     */
    protected void postBasicBlock(Set<F> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock justProcessed, Map<ISSABasicBlock, F> outItems) {

        for (ISSABasicBlock normalSucc : cfg.getNormalSuccessors(justProcessed)) {
            if (outItems.get(normalSucc) == null && !isUnreachable(justProcessed, normalSucc)) {
                throw new RuntimeException("No fact for normal successor from BB" + justProcessed.getGraphNodeId()
                                                + " to BB" + normalSucc.getGraphNodeId() + "\n" + justProcessed);
            }
        }

        for (ISSABasicBlock exceptionalSucc : cfg.getExceptionalSuccessors(justProcessed)) {
            if (outItems.get(exceptionalSucc) == null && !isUnreachable(justProcessed, exceptionalSucc)) {
                throw new RuntimeException("No fact for exceptional successor from BB" + justProcessed.getGraphNodeId()
                                                + " to BB" + exceptionalSucc.getGraphNodeId() + "\n" + justProcessed);
            }
        }
    }

    /**
     * binary operation on primitives, binary operator {@link IOperator}: ADD,
     * SUB, MUL, DIV, REM, AND, OR, XOR
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract F flowBinaryOp(SSABinaryOpInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Comparison for equality between floats, longs, or doubles
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract F flowComparison(SSAComparisonInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Converts one primitive type to another primitive type
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract F flowConversion(SSAConversionInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Entry to catch block assigning an exception to a local variable
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract F flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Assign from a static field to a local variable, x = ClassName.f
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract F flowGetStatic(SSAGetInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Dynamic type check
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract F flowInstanceOf(SSAInstanceofInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Evaluate a phi function and assign the result to a local variable, a phi
     * function is created at a join point in the control flow graph. The
     * function chooses one of its arguments based on which branch was taken
     * into the join point.
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract F flowPhi(SSAPhiInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Assign from a local variable into a static field, ClassName.f = x
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract F flowPutStatic(SSAPutInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Negation of a primitive value
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract F flowUnaryNegation(SSAUnaryOpInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Get the length of an array, a.length
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow facts after analyzing this instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowArrayLength(SSAArrayLengthInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Assignment from an array entry into a local variable, x = a[i]
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowArrayLoad(SSAArrayLoadInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Assignment from a local variable into an array entry, a[i] = x
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowArrayStore(SSAArrayStoreInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Dynamic type cast
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowCheckCast(SSACheckCastInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Comparison that branches based on the results, tests two values according
     * to an operator (EQ, NE, LT, GE, GT, or LE). The targets of the branches
     * must be retrieved from the control flow graph.
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<F> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                    ISSABasicBlock current);

    /**
     * Assignment from a non-static field to a local variable, x = o.f
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowGetField(SSAGetInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Call to an interface method
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowInvokeInterface(SSAInvokeInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Call to a "special" method, this is either an instance initializer, a
     * call to "super", or a private method, the target of a "special" call is
     * known statically
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowInvokeSpecial(SSAInvokeInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Call to a static method
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowInvokeStatic(SSAInvokeInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Call to virtual method
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowInvokeVirtual(SSAInvokeInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Unconditional jump. The target must be retrieved from the control flow
     * graph.
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowGoto(SSAGotoInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Call to some reflection thing, like loadClass
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowLoadMetadata(SSALoadMetadataInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Entrance or exit for a monitor (used to implement synchronized)
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowMonitor(SSAMonitorInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * New array, possibly with many dimensions, a = new ClassName[][]
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowNewArray(SSANewInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Allocation of a new object, x = new Foo. The instance initializer
     * (constructor) will be called using a later instruction.
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowNewObject(SSANewInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Assign from a local variable into a non-static field, o.f = x
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowPutField(SSAPutInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Return statement
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowReturn(SSAReturnInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Switch statement
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowSwitch(SSASwitchInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Unconditional exception throw
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow facts
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<ISSABasicBlock, F> flowThrow(SSAThrowInstruction i, Set<F> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current);

    /**
     * Merge given facts to create a new data-flow fact and map each successor
     * node number to that fact.
     * 
     * @param facts
     *            facts to merge
     * @param bb
     *            current basic block
     * @param cfg
     *            current control flow graph
     * @return map with the same merged value for each key
     */
    protected Map<ISSABasicBlock, F> mergeAndCreateMap(Set<F> facts, ISSABasicBlock bb, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
        F fact = confluence(facts, bb);
        return factToMap(fact, bb, cfg);
    }

}
