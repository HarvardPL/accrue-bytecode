package analysis.dataflow.interprocedural.exceptions;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.InstructionType;
import util.print.CFGWriter;
import util.print.PrettyPrinter;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction.IOperator;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction.Operator;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Results of a precise exceptions analysis mapping instructions to the set of
 * thrown exceptions
 */
public class PreciseExceptionResults {

    private final Map<CGNode, ResultsForNode> allResults = new HashMap<>();

    /**
     * Get the set of exceptions that can be thrown by the given basic block in
     * the method and context of the given call graph node.
     * 
     * @param bb
     *            basic block to get exceptions for
     * @param successor
     *            successor basic block
     * @param containingNode
     *            call graph node containing the instruction
     * @return set of exceptions thrown by the basic block
     */
    public Set<TypeReference> getExceptions(ISSABasicBlock bb, ISSABasicBlock successor, CGNode containingNode) {
        ResultsForNode results = allResults.get(containingNode);
        if (results == null) {
            return Collections.emptySet();
        }
        return results.getExceptions(bb, successor);
    }

    /**
     * Exceptions that can be thrown by the method represented by the call graph
     * node in the context contained therein
     * 
     * @param containingNode
     * @return
     */
    public Set<TypeReference> getExceptions(CGNode containingNode) {
        ResultsForNode results = allResults.get(containingNode);
        if (results == null) {
            return Collections.emptySet();
        }
        return results.getExceptions();
    }

    /**
     * Set the exceptions that can be thrown by the method represented by the
     * call graph node in the context contained therein
     * 
     * @param throwTypes
     * @param containingNode
     */
    protected void replaceExceptions(Set<TypeReference> throwTypes, CGNode containingNode) {
        ResultsForNode results = allResults.get(containingNode);
        if (results == null) {
            results = new ResultsForNode();
            allResults.put(containingNode, results);
        }
        results.replaceExceptions(throwTypes);
    }

    protected void replaceExceptions(Set<TypeReference> throwTypes, ISSABasicBlock bb, ISSABasicBlock successor,
                                    CGNode containingNode) {
        ResultsForNode results = allResults.get(containingNode);
        if (results == null) {
            results = new ResultsForNode();
            allResults.put(containingNode, results);
        }
        results.replaceExceptions(throwTypes, bb, successor);
    }

    /**
     * Successors which cannot be reached because no exception can be
     * thrown on the edge between the given basic block and the block in the
     * returned set
     * 
     * @param bb
     *            basic block to get impossible successors for
     * @param containingNode
     *            call graph node containing the basic block
     * @return set of basic block numbers for successors that can never be
     *         reached on exceptional edges
     */
    public Set<ISSABasicBlock> getImpossibleExceptions(ISSABasicBlock bb, CGNode containingNode) {
        if (bb.getLastInstructionIndex() >= 0
                                        && InstructionType.forInstruction(bb.getLastInstruction()) == InstructionType.NEW_OBJECT) {
            // This instruction can only throw errors, which we are not handling
            List<ISSABasicBlock> bbs = containingNode.getIR().getControlFlowGraph().getExceptionalSuccessors(bb);
            assert bbs.size() == 1;
            return Collections.singleton(bbs.get(0));
        }

        if (containingNode == null || allResults.get(containingNode) == null) {
            return Collections.emptySet();
        }

        return allResults.get(containingNode).getImpossibleExceptions(bb);
    }

    /**
     * Replace the set of successors from <code>source</code>
     * that are unreachable on exception edges
     * 
     * @param source
     *            source node
     * @param successors
     *            successors that are unreachable on exception edges
     * @param containingNode
     *            call graph node containing the basic blocks
     */
    protected void replaceImpossibleExceptions(ISSABasicBlock source, Set<ISSABasicBlock> unreachableByException, CGNode containingNode) {
        ResultsForNode results = allResults.get(containingNode);
        if (results == null) {
            results = new ResultsForNode();
            allResults.put(containingNode, results);
        }
        results.replaceImpossibleExceptions(source, unreachableByException);
    }

    private static class ResultsForNode {
        /**
         * Map of instruction/call graph node to the set of exceptions that can
         * be thrown by the instruction in that call graph node
         */
        private final Map<CFGEdge, Set<TypeReference>> thrownExceptions;
        /**
         * Map of instruction/call graph node to the set of successors that can
         * never be reached
         */
        private final Map<ISSABasicBlock, Set<ISSABasicBlock>> impossibleSuccessors;
        private Set<TypeReference> exceptionsForNode;

        /**
         * Initialize the precise exceptions where every instruction key is
         * mapped to an empty set of exceptions
         */
        public ResultsForNode() {
            thrownExceptions = new LinkedHashMap<>();
            impossibleSuccessors = new LinkedHashMap<>();
            exceptionsForNode = new LinkedHashSet<>();
        }

        /**
         * Replace the set of successors from <code>source</code>
         * that are unreachable on exception edges
         * 
         * @param source
         *            source node
         * @param successors
         *            successors that are unreachable on exception edges
         */
        public void replaceImpossibleExceptions(ISSABasicBlock source, Set<ISSABasicBlock> unreachableByException) {
            impossibleSuccessors.put(source, unreachableByException);
        }

        /**
         * Replace the set of exceptions that can be thrown by this node
         * 
         * @param throwTypes
         */
        public void replaceExceptions(Set<TypeReference> throwTypes) {
            exceptionsForNode = new LinkedHashSet<>();
        }

        /**
         * Get the set of exceptions that can be thrown by this node
         * 
         * @return
         */
        public Set<TypeReference> getExceptions() {
            return exceptionsForNode;
        }

        /**
         * Replace the exception types for the edge from i to the successor
         * basic block
         * 
         * @param throwTypes
         * @param bb
         * @param successor
         */
        public void replaceExceptions(Set<TypeReference> throwTypes, ISSABasicBlock bb, ISSABasicBlock successor) {
            CFGEdge key = new CFGEdge(bb, successor);
            thrownExceptions.put(key, throwTypes);
        }

        /**
         * Get the set of exceptions that can be thrown on the edge to the given
         * successor basic block
         * 
         * @param bb
         *            basic block to get exceptions for
         * @return set of exceptions thrown by the basic block to the given
         *         successor
         */
        public Set<TypeReference> getExceptions(ISSABasicBlock bb, ISSABasicBlock successor) {
            CFGEdge key = new CFGEdge(bb, successor);
            Set<TypeReference> exceptions = thrownExceptions.get(key);
            if (exceptions == null) {
                exceptions = Collections.emptySet();
            }
            return exceptions;
        }

        /**
         * Successors which cannot be reached because no exception can be
         * thrown on the edge between the given basic block and the block in the
         * returned set
         * 
         * @param bb
         *            basic block to get impossible successors for
         * @return set of basic block numbers for successors that can never be
         *         reached on exceptional edges
         */
        public Set<ISSABasicBlock> getImpossibleExceptions(ISSABasicBlock bb) {
            Set<ISSABasicBlock> succs = impossibleSuccessors.get(bb);
            if (succs == null) {
                succs = Collections.emptySet();
            }
            return succs;
        }
    }

    /**
     * Key into the exception map, based on instruction and call graph node
     */
    private static class CFGEdge {
        /**
         * Basic block
         */
        private final ISSABasicBlock bb;
        /**
         * Successor basic block
         */
        private final ISSABasicBlock successor;

        /**
         * Create a key into the exception map
         * 
         * @param i
         *            instruction
         * @param succNum
         *            Successor basic block number
         */
        public CFGEdge(ISSABasicBlock bb, ISSABasicBlock successor) {
            this.bb = bb;
            this.successor = successor;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((bb == null) ? 0 : bb.hashCode());
            result = prime * result + ((successor == null) ? 0 : successor.hashCode());
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
            CFGEdge other = (CFGEdge) obj;
            if (bb == null) {
                if (other.bb != null)
                    return false;
            } else if (!bb.equals(other.bb))
                return false;
            if (successor == null) {
                if (other.successor != null)
                    return false;
            } else if (!successor.equals(other.successor))
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
    private static final Collection<TypeReference> nullPointerException = Collections
                                    .singleton(TypeReference.JavaLangNullPointerException);
    /**
     * Singleton collection containing the arithmetic exception type
     */
    private static final Collection<TypeReference> arithmeticException = Collections
                                    .singleton(TypeReference.JavaLangArithmeticException);
    /**
     * Singleton collection containing the class cast exception type
     */
    private static final Collection<TypeReference> classCastException = Collections
                                    .singleton(TypeReference.JavaLangClassCastException);
    /**
     * Singleton collection containing the negative array index exception type
     */
    private static final Collection<TypeReference> negativeArraySizeException = Collections
                                    .singleton(TypeReference.JavaLangNegativeArraySizeException);
    /**
     * Singleton collection containing the negative array index exception type
     */
    private static final Collection<TypeReference> classNotFoundException = Collections
                                    .singleton(TypeReference.JavaLangClassNotFoundException);

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
        switch (type) {
        case ARRAY_LENGTH:
        case GET_FIELD:
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_VIRTUAL:
            // TODO WrongMethodTypeException
        case MONITOR:
            // Not handling IllegalMonitorStateException for monitorexit
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
            SSABinaryOpInstruction binop = (SSABinaryOpInstruction) i;
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
            // Throws an error, but no exceptions
        case PHI:
        case PUT_STATIC:
        case RETURN:
            // Not handling IllegalMonitorStateException
        case SWITCH:
        case UNARY_NEG_OP:
            return Collections.emptySet();
        }
        throw new RuntimeException("Unhandled instruction type: " + type);
    }

    /**
     * Will write the results for all contexts for the given method
     * <p>
     * TODO not sure what this looks like in dot if there is more than one node
     * 
     * @param writer
     *            writer to write to
     * @param m
     *            method to write the results for
     * 
     * @throws IOException
     *             issues with the writer
     */
    public void writeResultsForMethod(Writer writer, IMethod m) throws IOException {
        for (CGNode n : allResults.keySet()) {
            if (n.getMethod().equals(m)) {
                writeResultsForNode(writer, n);
            }
        }
    }

    private void writeResultsForNode(Writer writer, final CGNode n) throws IOException {
        final ResultsForNode results = allResults.get(n);

        CFGWriter w = new CFGWriter(n.getIR()) {
            @Override
            protected String getExceptionEdgeLabel(ISSABasicBlock source, ISSABasicBlock target, IR ir) {
                StringBuilder sb = new StringBuilder();
                sb.append("[");
                Iterator<TypeReference> iter = results.getExceptions(source, target).iterator();
                TypeReference t;
                if (iter.hasNext()) {
                    t = iter.next();
                    sb.append(PrettyPrinter.parseType(t));
                }
                while (iter.hasNext()) {
                    t = iter.next();
                    sb.append(", " + PrettyPrinter.parseType(t));
                }
                sb.append("]");
                return sb.toString();
            }

            @Override
            protected Set<ISSABasicBlock> getUnreachableExceptions(ISSABasicBlock bb,
                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
                return results.getImpossibleExceptions(bb);
            }
        };

        w.writeVerbose(writer, "", "\\l");
    }
}
