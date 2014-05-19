package analysis.dataflow.interprocedural.exceptions;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.InstructionType;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.AnalysisResults;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction.IOperator;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction.Operator;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Results of a precise exceptions analysis mapping instructions to the set of
 * thrown exceptions
 */
public class PreciseExceptionResults implements AnalysisResults {

    private final IClassHierarchy cha;

    public PreciseExceptionResults(IClassHierarchy cha) {
        this.cha = cha;
    }

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
     *            call graph node containing the basic block
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
     * Check whether a basic block can throw exceptions of the given type
     * 
     * @param type
     *            type to check
     * @param bb
     *            basic block
     * @param n
     *            call graph node containing the basic block
     * 
     * @return true if the basic block can throw the given exception type
     */
    public boolean canThrowException(TypeReference type, ISSABasicBlock bb, CGNode n) {
        SSACFG cfg = n.getIR().getControlFlowGraph();
        for (ISSABasicBlock succ : cfg.getExceptionalSuccessors(bb)) {
            if (getExceptions(bb, succ, n).contains(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether a method (in a particular context) can throw exceptions of
     * the given type
     * 
     * @param type
     *            type to check
     * @param n
     *            method and context
     * @return true if the call graph node can throw the given exception type
     */
    public boolean canProcedureThrowException(TypeReference type, CGNode n) {
        if (n.getMethod().isNative()) {
            IClass exClass = cha.lookupClass(type);
            if (cha.isAssignableFrom(cha.lookupClass(TypeReference.JavaLangRuntimeException), exClass)) {
                // assume native methods can throw RTE
                return true;
            }
            try {
                for (TypeReference declEx : n.getMethod().getDeclaredExceptions()) {
                    IClass declClass = cha.lookupClass(declEx);
                    if (cha.isAssignableFrom(declClass, exClass)) {
                        // precise throw type could be any subtype of the
                        // declared exceptions
                        return true;
                    }
                }
            } catch (UnsupportedOperationException | InvalidClassFileException e) {
                throw new RuntimeException(e);
            }
            return false;
        }

        SSACFG cfg = n.getIR().getControlFlowGraph();
        ISSABasicBlock exit = cfg.exit();
        for (ISSABasicBlock pred : cfg.getExceptionalPredecessors(exit)) {
            if (getExceptions(pred, exit, n).contains(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether a basic block can throw exceptions
     * 
     * @param bb
     *            basic block to check
     * @param n
     *            call graph node containing the basic block
     * @return true if the basic block can throw any exception
     */
    public boolean canThrowAnyException(ISSABasicBlock bb, CGNode n) {
        SSACFG cfg = n.getIR().getControlFlowGraph();
        for (ISSABasicBlock succ : cfg.getExceptionalSuccessors(bb)) {
            if (!getExceptions(bb, succ, n).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether a method (in a particular context) can throw exceptions
     * 
     * @param n
     *            method and context to check
     * @return true if the call graph node can throw any exception
     */
    public boolean canProcedureThrowAnyException(CGNode n) {
        if (n.getMethod().isNative()) {
            // assume native methods can throw something
            return true;
        }

        SSACFG cfg = n.getIR().getControlFlowGraph();
        ISSABasicBlock exit = cfg.exit();
        for (ISSABasicBlock pred : cfg.getExceptionalPredecessors(exit)) {
            if (!getExceptions(pred, exit, n).isEmpty()) {
                return true;
            }
        }
        return false;
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

    private static class ResultsForNode {
        /**
         * Map of instruction/call graph node to the set of exceptions that can
         * be thrown by the instruction in that call graph node
         */
        private final Map<CFGEdge, Set<TypeReference>> thrownExceptions;

        /**
         * Initialize the precise exceptions where every instruction key is
         * mapped to an empty set of exceptions
         */
        public ResultsForNode() {
            thrownExceptions = new LinkedHashMap<>();
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
            // Not handling IllegalMonitorStateException for monitor
        case PUT_FIELD:
        case THROW: // if the object thrown is null
            // Not handling IllegalMonitorStateException for throw
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
        case BINARY_OP_EX:
            SSABinaryOpInstruction binop = (SSABinaryOpInstruction) i;
            IOperator opType = binop.getOperator();
            if (binop.mayBeIntegerOp() && (opType == Operator.DIV || opType == Operator.REM)) {
                return arithmeticException;
            }

            // No implicit exceptions thrown by the following
        case BINARY_OP:
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
     * Will write the results for the first context for the given method
     * 
     * @param writer
     *            writer to write to
     * @param m
     *            method to write the results for
     * @param reachable
     *            results of a reachability analysis
     * 
     * @throws IOException
     *             issues with the writer
     */
    public void writeResultsForMethod(Writer writer, IMethod m, ReachabilityResults reachable) throws IOException {
        for (CGNode n : allResults.keySet()) {
            if (n.getMethod().equals(m)) {
                writeResultsForNode(writer, n, reachable);
                return;
            }
        }
    }

    public void writeAllToFiles(ReachabilityResults reachable) throws IOException {
        for (CGNode n : allResults.keySet()) {
            String fileName = "tests/preciseex_" + PrettyPrinter.cgNodeString(n) + ".dot";
            try (Writer w = new FileWriter(fileName)) {
                writeResultsForNode(w, n, reachable);
                System.err.println("DOT written to " + fileName);
            }
        }
    }

    private void writeResultsForNode(Writer writer, final CGNode n, final ReachabilityResults reachable)
                                    throws IOException {
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
                    sb.append(PrettyPrinter.typeString(t));
                }
                while (iter.hasNext()) {
                    t = iter.next();
                    sb.append(", " + PrettyPrinter.typeString(t));
                }
                sb.append("]");
                return sb.toString();
            }

            @Override
            protected Set<ISSABasicBlock> getUnreachableSuccessors(ISSABasicBlock bb,
                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
                Set<ISSABasicBlock> unreachable = new LinkedHashSet<>();
                for (ISSABasicBlock next : cfg.getNormalSuccessors(bb)) {
                    if (reachable.isUnreachable(bb, next, n)) {
                        unreachable.add(next);
                    }
                }

                for (ISSABasicBlock next : cfg.getExceptionalSuccessors(bb)) {
                    if (reachable.isUnreachable(bb, next, n)) {
                        unreachable.add(next);
                    }
                }
                return unreachable;
            }
        };

        w.writeVerbose(writer, "", "\\l");
    }
}
