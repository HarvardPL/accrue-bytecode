package util;

import util.print.PrettyPrinter;

import com.ibm.wala.shrikeBT.IBinaryOpInstruction.IOperator;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
import com.ibm.wala.shrikeBT.IInvokeInstruction.Dispatch;
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
 * Enumeration of SSA instruction types
 */
public enum InstructionType {
    /**
     * @see SSAArrayLengthInstruction
     */
    ARRAY_LENGTH,
    /**
     * v = a[i], jvm aaload
     * 
     * @see SSAArrayLoadInstruction
     */
    ARRAY_LOAD,
    /**
     * a[i] = v, jvm aastore
     * 
     * @see SSAArrayStoreInstruction
     */
    ARRAY_STORE,
    /**
     * Binary operation {@link IOperator}: ADD, SUB, MUL, DIV, REM, AND, OR, XOR
     * 
     * @see SSABinaryOpInstruction
     */
    BINARY_OP,
    /**
     * Binary operation that can throw an {@link ArithmeticException}. i.e. the
     * operator {@link IOperator} is either DIV or REM and the type is an
     * integer type.
     * 
     * @see SSABinaryOpInstruction
     */
    BINARY_OP_EX,
    /**
     * @see SSACheckCastInstruction
     */
    CHECK_CAST,
    /**
     * @see SSAComparisonInstruction
     */
    COMPARISON,
    /**
     * Conditional branch with guard which tests two values according to an
     * operator (EQ, NE, LT, GE, GT, or LE).
     * 
     * @see SSAConditionalBranchInstruction
     */
    CONDITIONAL_BRANCH,
    /**
     * @see SSAConversionInstruction
     */
    CONVERSION,
    /**
     * @see SSAGetCaughtExceptionInstruction
     */
    GET_CAUGHT_EXCEPTION,
    /**
     * @see SSAGetInstruction
     */
    GET_FIELD,
    /**
     * @see SSAGetInstruction
     */
    GET_STATIC,
    /**
     * Unconditional jump, need the control flow graph to obtain the jump target
     * 
     * @see SSAGotoInstruction
     */
    GOTO,
    /**
     * @see SSAInstanceofInstruction
     */
    INSTANCE_OF,
    /**
     * Invocation of an interface method.
     * 
     * @see {@link SSAInvokeInstruction}
     */
    INVOKE_INTERFACE,
    /**
     * Special method invocation.
     * 
     * @see {@link SSAInvokeInstruction}
     */
    INVOKE_SPECIAL,
    /**
     * Static method invocation.
     * 
     * @see {@link SSAInvokeInstruction}
     */
    INVOKE_STATIC,
    /**
     * Virtual method invocation.
     * 
     * @see SSAInvokeInstruction
     */
    INVOKE_VIRTUAL,
    /**
     * @see SSALoadMetadataInstruction
     */
    LOAD_METADATA,
    /**
     * @see {@link SSAMonitorInstruction}
     */
    MONITOR,
    /**
     * @see SSANewInstruction
     */
    NEW_ARRAY,
    /**
     * @see SSANewInstruction
     */
    NEW_OBJECT,
    /**
     * @see SSAPhiInstruction
     */
    PHI,
    /**
     * @see SSAPutInstruction
     */
    PUT_FIELD,
    /**
     * @see SSAPutInstruction
     */
    PUT_STATIC,
    /**
     * @see SSAReturnInstruction
     */
    RETURN,
    /**
     * @see SSASwitchInstruction
     */
    SWITCH,
    /**
     * @see SSAThrowInstruction
     */
    THROW,
    /**
     * Unary negation.
     * 
     * @see SSAUnaryOpInstruction
     */
    UNARY_NEG_OP;
    // Unexpected types
//  /**
//   * @see SSAPiInstruction
//   */
//  PI,
//  /**
//   * @see SSALoadIndirectInstruction
//   */
//  LOAD_INDIRECT,
//  /**
//   * @see SSAAddressOfInstruction
//   */
//  ADDRESS_OF,
//  /**
//   * @see SSAStoreIndirectInstruction
//   */
//  STORE_INDIRECT,

    public static InstructionType forInstruction(SSAInstruction i) {
        if (i instanceof SSAGotoInstruction) return GOTO;
        if (i instanceof SSAArrayLoadInstruction) return ARRAY_LOAD;
        if (i instanceof SSAArrayStoreInstruction) return ARRAY_STORE;
        if (i instanceof SSABinaryOpInstruction) {
            if (i.isPEI()) {
                // can throw an ArithmeticException
                return BINARY_OP_EX;
            }
            return BINARY_OP;
        }
        if (i instanceof SSAUnaryOpInstruction) {
            // Unary negation should be the only possibility here
            assert ((SSAUnaryOpInstruction) i).getOpcode() == com.ibm.wala.shrikeBT.IUnaryOpInstruction.Operator.NEG : "Invalid unary operator: "
                    + ((SSAUnaryOpInstruction) i).getOpcode();
            return UNARY_NEG_OP;
        }
        if (i instanceof SSAConversionInstruction) return CONVERSION;
        if (i instanceof SSAComparisonInstruction) return COMPARISON;
        if (i instanceof SSAConditionalBranchInstruction) return CONDITIONAL_BRANCH;
        if (i instanceof SSASwitchInstruction) return SWITCH;
        if (i instanceof SSAReturnInstruction) return RETURN;
        if (i instanceof SSAGetInstruction) {
            if (((SSAGetInstruction) i).isStatic()) {
                return GET_STATIC;
            }
            return GET_FIELD;
        }
        if (i instanceof SSAPutInstruction) {
            if (((SSAPutInstruction) i).isStatic()) {
                return PUT_STATIC;
            }
            return PUT_FIELD;
        }
        if (i instanceof SSAInvokeInstruction) {
            SSAInvokeInstruction inv = (SSAInvokeInstruction) i;
            IInvokeInstruction.Dispatch type = (Dispatch) inv.getCallSite().getInvocationCode();
            switch (type) {
            case VIRTUAL: return INVOKE_VIRTUAL;
            case INTERFACE: return INVOKE_INTERFACE;
            case SPECIAL: return INVOKE_SPECIAL;
            case STATIC: return INVOKE_STATIC;
            default: assert false : "Invalid invocation type: " + type;
            }
        }
        if (i instanceof SSANewInstruction) {
            if (((SSANewInstruction) i).getNewSite().getDeclaredType().isArrayType()) {
                return NEW_ARRAY;
            }
            return NEW_OBJECT;
        }
        if (i instanceof SSAArrayLengthInstruction) return ARRAY_LENGTH;
        if (i instanceof SSAThrowInstruction) return THROW;
        if (i instanceof SSACheckCastInstruction) return CHECK_CAST;
        if (i instanceof SSAInstanceofInstruction) return INSTANCE_OF;
        if (i instanceof SSAPhiInstruction) return PHI;
        if (i instanceof SSAGetCaughtExceptionInstruction) return GET_CAUGHT_EXCEPTION;
        if (i instanceof SSALoadMetadataInstruction) return LOAD_METADATA;
        if (i instanceof SSAMonitorInstruction) return MONITOR;
        String msg = "Invalid/unexpected instruction type: " + (i == null ? "NULL" : PrettyPrinter.getSimpleClassName(i));
        assert false : msg;
        throw new RuntimeException(msg);
    }

    public boolean isInvoke() {
        switch (this) {
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_STATIC:
        case INVOKE_VIRTUAL:
            return true;
        default:
            return false;
        }
    }
}
