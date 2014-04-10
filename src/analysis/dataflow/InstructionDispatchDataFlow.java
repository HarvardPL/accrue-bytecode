package analysis.dataflow;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import util.InstructionType;
import util.print.PrettyPrinter;

import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACFG;
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
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;

/**
 * Data-flow that dispatched based on the type of instruction being processed
 */
public abstract class InstructionDispatchDataFlow<FlowItem> extends DataFlow<FlowItem> {

    /**
     * Data-flow that dispatches based on instruction type
     * 
     * @param forward
     */
    public InstructionDispatchDataFlow(boolean forward) {
        super(forward);
    }

    /**
     * Compute data-flow facts for each instruction in the basic block. The
     * handler is chosen based on the type of the instruction.
     * <p>
     * {@inheritDoc}
     */
    @Override
    protected final Map<Integer, FlowItem> flow(Set<FlowItem> inItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO basic blocks should be analyzed on SCC at a time
        Set<FlowItem> previousItems = inItems;
        SSAInstruction last = current.getLastInstruction();
        for (SSAInstruction i : current) {
            if (i == last) {
                // this is the last instruction of the block
                return flowInstruction(i, previousItems, cfg, current);
            } else {
                // Pass the results of this "flow" into the next
                previousItems = new HashSet<>(flowInstruction(i, inItems, cfg, current).values());
            }
        }
        assert false : "Something has to be the last instruction.";
        throw new RuntimeException("Something has to be the last instruction.");
    }

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param inItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected Map<Integer, FlowItem> flowInstruction(SSAInstruction i, Set<FlowItem> inItems, SSACFG cfg,
            ISSABasicBlock current) {
        SSAInstruction last = current.getLastInstruction();
        if (i == last) {
            // this is the last instruction of the block
            return flowLastInstruction(i, inItems, cfg, current);
        } else {
            // Pass the results of this "flow" into the next
            return itemToMap(flowOtherInstruction(i, inItems, cfg, current), current, cfg);
        }
    }

    /**
     * Data-flow transfer function for the last instruction of a basic block
     * 
     * @param i
     *            instruction
     * @param inItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    private Map<Integer, FlowItem> flowLastInstruction(SSAInstruction i, Set<FlowItem> inItems, SSACFG cfg,
            ISSABasicBlock current) {
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
            return itemToMap(flowOtherInstruction(i, inItems, cfg, current), current, cfg);
        }
        throw new RuntimeException("Invalid instruction type: " + PrettyPrinter.getSimpleClassName(i));
    }

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowThrow(SSAThrowInstruction i, Set<FlowItem> previousItems, SSACFG cfg,
            ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowSwitch(SSASwitchInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowReturn(SSAReturnInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for a non-static put instruction o.f = x
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowPutField(SSAPutInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowNewObject(SSANewInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowNewArray(SSANewInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowLoadMetadata(SSALoadMetadataInstruction i,
            Set<FlowItem> previousItems, SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowInvokeVirtual(SSAInvokeInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowInvokeStatic(SSAInvokeInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowInvokeSpecial(SSAInvokeInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowInvokeInterface(SSAInvokeInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowGoto(SSAGotoInstruction i, Set<FlowItem> previousItems, SSACFG cfg,
            ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowGetField(SSAGetInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowConditionalBranch(SSAConditionalBranchInstruction i,
            Set<FlowItem> previousItems, SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return map from target of successor edge to the data-flow fact on that
     *         edge after handling the current instruction
     */
    protected abstract Map<Integer, FlowItem> flowCheckCast(SSACheckCastInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param inItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    private FlowItem flowOtherInstruction(SSAInstruction i, Set<FlowItem> inItems, SSACFG cfg, ISSABasicBlock current) {
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
        case NEW_OBJECT:
        case NEW_ARRAY:
        case PUT_FIELD:
        case RETURN:
        case SWITCH:
        case THROW:
            // The above all branch and should be the last instruction in a basic block
            break;
        }
        throw new RuntimeException("Incorrect handling of instruction type: " + type);
    }

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract FlowItem flowUnaryNegation(SSAUnaryOpInstruction i, Set<FlowItem> previousItems, SSACFG cfg,
            ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract FlowItem flowPutStatic(SSAPutInstruction i, Set<FlowItem> previousItems, SSACFG cfg,
            ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract FlowItem flowPhi(SSAPhiInstruction i, Set<FlowItem> previousItems, SSACFG cfg,
            ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract FlowItem flowInstanceOf(SSAInstanceofInstruction i, Set<FlowItem> previousItems, SSACFG cfg,
            ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract FlowItem flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract FlowItem flowGetStatic(SSAGetInstruction i, Set<FlowItem> previousItems, SSACFG cfg,
            ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract FlowItem flowConversion(SSAConversionInstruction i, Set<FlowItem> previousItems, SSACFG cfg,
            ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract FlowItem flowComparison(SSAComparisonInstruction i, Set<FlowItem> previousItems, SSACFG cfg,
            ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract FlowItem flowBinaryOp(SSABinaryOpInstruction i, Set<FlowItem> previousItems, SSACFG cfg,
            ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract Map<Integer, FlowItem> flowArrayStore(SSAArrayStoreInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract Map<Integer, FlowItem> flowArrayLoad(SSAArrayLoadInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);

    /**
     * Data-flow transfer function for an instruction with only one successor
     * 
     * @param i
     *            instruction
     * @param previousItems
     *            input data-flow items
     * @param cfg
     *            control flow graph
     * @param current
     *            current basic block
     * @return data-flow fact after analyzing this instruction
     */
    protected abstract Map<Integer, FlowItem> flowArrayLength(SSAArrayLengthInstruction i, Set<FlowItem> previousItems,
            SSACFG cfg, ISSABasicBlock current);
}
