package analysis.dataflow.interprocedural.exceptions;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.InstructionType;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction.IOperator;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction.Operator;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Results of a precise exceptions analysis mapping instructions to the set of
 * thrown exceptions
 */
public class PreciseExceptions {

    /**
     * Map of instruction/call graph node to the set of exceptions that can be
     * thrown by the instruction in that call graph node
     */
    private final Map<InstructionKey, Set<TypeReference>> thrownExceptions;
    /**
     * Map of instruction/call graph node to the set of successors that can
     * never be reached
     */
    private final Map<InstructionKey, Set<ISSABasicBlock>> impossibleSuccessors;

    /**
     * Initialize the precise exceptions where every instruction key is mapped
     * to an empty set of exceptions
     */
    public PreciseExceptions() {
        thrownExceptions = new LinkedHashMap<>();
        impossibleSuccessors = new LinkedHashMap<>();
    }

    /**
     * Get the set of exceptions that can be thrown by the given instruction in
     * the method and context of the given call graph node.
     * 
     * @param i
     *            instruction to get exceptions for
     * @param containingNode
     *            call graph node containing the instruction
     * @return set of exceptions thrown by the instruction
     */
    public Set<TypeReference> getExceptions(SSAInstruction i, CGNode containingNode) {
        Set<TypeReference> exceptions = thrownExceptions.get(new InstructionKey(i, containingNode));
        if (exceptions == null && implicitExceptions(i).isEmpty()) {
            exceptions = Collections.emptySet();
        }
        return exceptions;
    }
    
    /**
     * Successors which cannot be reached either because no exception can be
     * thrown on the edge from the instruction or because the instruction cannot
     * terminate normally.
     * 
     * @param i
     *            instruction to get impossible successors for
     * @param containingBB
     *            basic block containing the instruction
     * @param containingNode
     *            call graph node containing the instruction
     * @return set of basic block numbers for successors that can never be
     *         reached
     */
    public Set<ISSABasicBlock> getImpossibleSuccessors(SSAInstruction i, ISSABasicBlock containingBB,
            CGNode containingNode) {
        if (InstructionType.forInstruction(i) == InstructionType.NEW_OBJECT) {
            // This instruction can only throw errors, which we are not handling
            List<ISSABasicBlock> bbs = containingNode.getIR().getControlFlowGraph()
                    .getExceptionalSuccessors(containingBB);
            assert bbs.size() == 1;
            return Collections.singleton(bbs.get(0));
        }

        Set<ISSABasicBlock> succs = impossibleSuccessors.get(new InstructionKey(i, containingNode));
        if (succs == null) {
            succs = Collections.emptySet();
        }
        return succs;
    }

    /**
     * Key into the exception map, based on instruction and call graph node
     */
    private static class InstructionKey {
        /**
         * Instruction
         */
        private final SSAInstruction i;
        /**
         * Containing call graph node
         */
        private final CGNode node;

        /**
         * Create a key into the exception map
         * 
         * @param i
         *            instruction
         * @param node
         *            containing call graph node
         */
        public InstructionKey(SSAInstruction i, CGNode node) {
            this.i = i;
            this.node = node;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((i == null) ? 0 : i.hashCode());
            result = prime * result + ((node == null) ? 0 : node.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            InstructionKey other = (InstructionKey) obj;
            if (i == null) {
                if (other.i != null)
                    return false;
            } else if (!i.equals(other.i))
                return false;
            if (node == null) {
                if (other.node != null)
                    return false;
            } else if (!node.equals(other.node))
                return false;
            return true;
        }
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
     * Singleton collection containing the negative array index exception type
     */
    private static final Collection<TypeReference> classNotFoundException = Collections.singleton(TypeReference.JavaLangClassNotFoundException);
    
    /**
     * Get the exceptions that may be implicitly thrown by this instruction
     * 
     * @param i
     *            instruction
     * 
     * @return collection of implicit exception types
     */
    public static Collection<TypeReference> implicitExceptions(SSAInstruction i) {
        InstructionType type = InstructionType.forInstruction(i);
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
        case LOAD_METADATA:
            return classNotFoundException;
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
