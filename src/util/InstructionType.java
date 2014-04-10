package util;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import com.ibm.wala.shrikeBT.IBinaryOpInstruction.IOperator;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction.Operator;
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
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Enumeration of SSA instruction types
 */
public enum InstructionType {
    /**
     * Unconditional jump, need the control flow graph to obtain the jump target
     * 
     * @see SSAGotoInstruction
     */
    GOTO,
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
     * Binary operator {@link IOperator}: ADD, SUB, MUL, DIV, REM, AND, OR, XOR
     * 
     * @see SSABinaryOpInstruction
     */
    BINARY_OP,
    /**
     * Unary negation.
     * 
     * @see SSAUnaryOpInstruction
     */
    UNARY_NEG_OP,
    /**
     * @see SSAConversionInstruction
     */
    CONVERSION,
    /**
     * @see SSAComparisonInstruction
     */
    COMPARISON,
    /**
     * Conditional branch with guard which tests two values according to an operator (EQ, NE, LT, GE, GT, or LE).
     * 
     * @see SSAConditionalBranchInstruction
     */
    CONDITIONAL_BRANCH,
    /**
     * @see SSASwitchInstruction
     */
    SWITCH,
    /**
     * @see SSAReturnInstruction
     */
    RETURN,
    /**
     * @see SSAGetInstruction
     */
    GET_FIELD,
    /**
     * @see SSAGetInstruction
     */
    GET_STATIC,
    /**
     * @see SSAPutInstruction
     */
    PUT_FIELD,
    /**
     * @see SSAPutInstruction
     */
    PUT_STATIC,
    /**
     * @see SSANewInstruction
     */
    NEW_OBJECT,
    /**
     * @see SSANewInstruction
     */
    NEW_ARRAY,
    /**
     * @see SSAArrayLengthInstruction
     */
    ARRAY_LENGTH,
    /**
     * @see SSAThrowInstruction
     */
    THROW,
    /**
     * @see SSACheckCastInstruction
     */
    CHECK_CAST,
    /**
     * @see SSAInstanceofInstruction
     */
    INSTANCE_OF,
    /**
     * @see SSAPhiInstruction
     */
    PHI,
    /**
     * @see SSAGetCaughtExceptionInstruction
     */
    GET_CAUGHT_EXCEPTION,
    /**
     * @see SSALoadMetadataInstruction
     */
    LOAD_METADATA,
    /**
     * Virtual method invocation.
     * 
     * @see SSAInvokeInstruction
     */
    INVOKE_VIRTUAL,
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
    INVOKE_STATIC;
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
//   * @see SSAMonitorInstruction
//   */
//  MONITOR,
//  /**
//   * @see SSAStoreIndirectInstruction
//   */
//  STORE_INDIRECT,

    public static InstructionType forInstruction(SSAInstruction i) {
        if (i instanceof SSAGotoInstruction) return GOTO;
        if (i instanceof SSAArrayLoadInstruction) return ARRAY_LOAD;
        if (i instanceof SSAArrayStoreInstruction) return ARRAY_STORE;
        if (i instanceof SSABinaryOpInstruction) return BINARY_OP;
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
        String msg = "Invalid/unexpected instruction type: " + i.getClass().getCanonicalName();
        assert false : msg;
        throw new RuntimeException(msg);
    }
    
    /**
     * Exceptions that may be thrown by an array load
     */
    private static Collection<TypeReference> arrayLoadExeptions;
    /**
     * Exceptions that may be thrown by an array store
     */
    private static Collection<TypeReference> arrayStoreExeptions;
    /**
     * Singleton collection containing the null pointer exception type
     */
    private static final Collection<TypeReference> nullPointerException = Collections.singleton(TypeReference.JavaLangNullPointerException);
    /**
     * Singleton collection containing the arithmetic exception type
     */
    private static final Collection<TypeReference> arithmeticException = Collections.singleton(TypeReference.JavaLangArithmeticException);
    /**
     * Singleton collection containing the class cast exception type
     */
    private static final Collection<TypeReference> classCastException = Collections.singleton(TypeReference.JavaLangClassCastException);
    /**
     * Singleton collection containing the negative array index exception type
     */
    private static final Collection<TypeReference> negativeArraySizeException = Collections.singleton(TypeReference.JavaLangNegativeArraySizeException);
    
    /**
     * Get the exceptions that may be implicitly thrown by this instruction
     * 
     * @param i
     *            instruction
     * 
     * @return collection of implicit exception types
     */
    public static Collection<TypeReference> implicitExceptions(SSAInstruction i) {
        InstructionType type = forInstruction(i);
        switch(type) {

        case ARRAY_LENGTH:
        case GET_FIELD:
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_VIRTUAL:
            // TODO WrongMethodTypeException
        case PUT_FIELD:
        case THROW: // if the object thrown is null
            return nullPointerException;
            
        case CHECK_CAST:
            return classCastException;
            
        case NEW_ARRAY:
            return negativeArraySizeException;
            
        case ARRAY_LOAD:
            if (arrayLoadExeptions == null) {
                Set<TypeReference> es = new LinkedHashSet<>();
                es.add(TypeReference.JavaLangNullPointerException);
                es.add(TypeReference.JavaLangArrayIndexOutOfBoundsException);
                arrayLoadExeptions = Collections.unmodifiableCollection(es);
            }
            return arrayLoadExeptions;
            
        case ARRAY_STORE:
            if (arrayStoreExeptions == null) {
                Set<TypeReference> es = new LinkedHashSet<>();
                es.add(TypeReference.JavaLangNullPointerException);
                es.add(TypeReference.JavaLangArrayIndexOutOfBoundsException);
                es.add(TypeReference.JavaLangArrayStoreException);
                arrayStoreExeptions = Collections.unmodifiableCollection(es);
            }
            return arrayStoreExeptions;
            
        case BINARY_OP:
            SSABinaryOpInstruction binop = (SSABinaryOpInstruction)i;
            IOperator opType = binop.getOperator();
            if (binop.mayBeIntegerOp() && (opType == Operator.DIV || opType == Operator.REM)) {
                return arithmeticException;
            }
                    
        // No implicit exceptions thrown by the following     
        case COMPARISON:
        case CONDITIONAL_BRANCH:
        case CONVERSION:
        case GET_STATIC:
        case GET_CAUGHT_EXCEPTION:
        case GOTO:
        case INSTANCE_OF:
        case INVOKE_STATIC:
        case LOAD_METADATA:
            // TODO Exceptions for reflection?
        case NEW_OBJECT:
        case PHI:
        case PUT_STATIC:
        case RETURN:
            // TODO IllegalMonitorStateException
        case SWITCH:
        case UNARY_NEG_OP:
            return Collections.emptySet();
        default:
            throw new IllegalArgumentException("Undefined instruction type: " + type);
        }
    }
}
