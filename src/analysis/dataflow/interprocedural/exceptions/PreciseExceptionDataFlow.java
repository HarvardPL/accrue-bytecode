package analysis.dataflow.interprocedural.exceptions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.IntraproceduralDataFlow;
import analysis.dataflow.interprocedural.nonnull.NonNullResults;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
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
import com.ibm.wala.types.TypeReference;

public class PreciseExceptionDataFlow extends IntraproceduralDataFlow<PreciseExceptionAbsVal> {

    /**
     * Class hierarchy
     */
    private final IClassHierarchy cha;
    /**
     * Results of a non-null analysis
     */
    private final NonNullResults nonNullResults;
    /**
     * Map from array to list of integer dimensions for the array (from outside
     * in), value in the list is null if the dimension is not a static integer
     */
    private final Map<Integer, List<Integer>> arrayDimensions = new HashMap<>();

    public PreciseExceptionDataFlow(NonNullResults nonNullResults, CGNode currentNode,
                                    InterproceduralDataFlow<PreciseExceptionAbsVal> interProc, IClassHierarchy cha) {
        super(currentNode, interProc);
        this.nonNullResults = nonNullResults;
        this.cha = cha;
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> call(SSAInvokeInstruction i,
                                    Set<PreciseExceptionAbsVal> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb) {
        Set<TypeReference> throwables = new LinkedHashSet<>(PreciseExceptionResults.implicitExceptions(i));
        if (!i.isStatic() && nonNullResults.isNonNull(i.getReceiver(), i, currentNode)) {
            throwables.remove(TypeReference.JavaLangNullPointerException);
        }

        PreciseExceptionAbsVal exceptionResult = null;
        for (CGNode callee : cg.getPossibleTargets(currentNode, i.getCallSite())) {
            Map<ExitType, PreciseExceptionAbsVal> out = interProc.getResults(currentNode, callee,
                                            PreciseExceptionAbsVal.EMPTY);

            // There should be no exceptions on normal exit
            assert out.get(ExitType.NORMAL) == null || out.get(ExitType.NORMAL).isBottom();

            Set<PreciseExceptionAbsVal> exceptions = new LinkedHashSet<>();
            exceptions.add(exceptionResult);
            exceptions.add(out.get(ExitType.EXCEPTIONAL));
            exceptionResult = confluence(exceptions, bb);
        }

        if (exceptionResult != null) {
            throwables.addAll(exceptionResult.getThrowables());
        }
        return computeResults(throwables, cfg, bb);
    }

    @Override
    protected PreciseExceptionAbsVal confluence(Set<PreciseExceptionAbsVal> items, ISSABasicBlock bb) {
        assert !items.isEmpty();
        PreciseExceptionAbsVal val = null;
        for (PreciseExceptionAbsVal item : items) {
            val = item.join(val);
        }
        return val;
    }

    @Override
    protected void postBasicBlock(Set<PreciseExceptionAbsVal> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock justProcessed,
                                    Map<ISSABasicBlock, PreciseExceptionAbsVal> outItems) {

        for (ISSABasicBlock next : cfg.getNormalSuccessors(justProcessed)) {
            if (outItems.get(next) != null && !outItems.get(next).isEmpty()) {
                throw new RuntimeException("Exceptions for normal successor of\n"
                                                + PrettyPrinter.basicBlockString(currentNode.getIR(), justProcessed,
                                                                                "\t", "\n") + " from BB"
                                                + justProcessed.getGraphNodeId() + " to BB" + next.getGraphNodeId());
            }
        }

        for (ISSABasicBlock next : cfg.getExceptionalSuccessors(justProcessed)) {
            if (outItems.get(next) != null) {
                PreciseExceptionResults results = ((PreciseExceptionInterproceduralDataFlow) interProc)
                                                .getAnalysisResults();
                results.replaceExceptions(outItems.get(next).getThrowables(), justProcessed, next, currentNode);
            }
        }

        super.postBasicBlock(inItems, cfg, justProcessed, outItems);
    }

    @Override
    protected PreciseExceptionAbsVal flowBinaryOp(SSABinaryOpInstruction i, Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowComparison(SSAComparisonInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowConversion(SSAConversionInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Could be incoming exceptions
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowGetStatic(SSAGetInstruction i, Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowInstanceOf(SSAInstanceofInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowPhi(SSAPhiInstruction i, Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowPutStatic(SSAPutInstruction i, Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowUnaryNegation(SSAUnaryOpInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowArrayLength(SSAArrayLengthInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return computeResults(i, !nonNullResults.isNonNull(i.getArrayRef(), i, currentNode), cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowArrayLoad(SSAArrayLoadInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        Collection<TypeReference> throwables = new LinkedHashSet<>(PreciseExceptionResults.implicitExceptions(i));

        // handle the array dimensions
        if (isSafeArrayIndex(i.getArrayRef(), i.getIndex())) {
            throwables.remove(TypeReference.JavaLangArrayIndexOutOfBoundsException);
        }

        // if there are multiple dimensions then the assignee is also an array
        // use the dimensions from the parent array for the assignee
        List<Integer> dims = arrayDimensions.get(i.getArrayRef());
        if (dims.size() > 1) {
            List<Integer> subDims = dims.subList(1, dims.size());
            arrayDimensions.put(i.getDef(), subDims);
        }

        if (nonNullResults.isNonNull(i.getArrayRef(), i, currentNode)) {
            throwables.remove(TypeReference.JavaLangNullPointerException);
        }

        return computeResults(throwables, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowArrayStore(SSAArrayStoreInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        Collection<TypeReference> throwables = new LinkedHashSet<>(PreciseExceptionResults.implicitExceptions(i));
        if (nonNullResults.isNonNull(i.getArrayRef(), i, currentNode)) {
            throwables.remove(TypeReference.JavaLangNullPointerException);
        }

        IClass elementType = cha.lookupClass(i.getElementType());
        IClass storedType = cha.lookupClass(TypeRepository.getType(i.getUse(2), currentNode.getIR()));
        if (cha.isSubclassOf(storedType, elementType)) {
            throwables.remove(TypeReference.JavaLangArrayStoreException);
        }

        // handle the array dimensions
        if (isSafeArrayIndex(i.getArrayRef(), i.getIndex())) {
            throwables.remove(TypeReference.JavaLangArrayIndexOutOfBoundsException);
        }

        return computeResults(throwables, cfg, current);
    }
    
    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowBinaryOpWithException(SSABinaryOpInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowCheckCast(SSACheckCastInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        Collection<TypeReference> throwables = PreciseExceptionResults.implicitExceptions(i);

        // Upcasts and casts of null are always safe, see if either case holds
        // TODO track "definitely null" in the non-null analysis
        boolean castAlwaysSucceeds = true;
        IClass checked = cha.lookupClass(i.getDeclaredResultTypes()[0]);
        if (!currentNode.getIR().getSymbolTable().isNullConstant(i.getVal())) {
            for (InstanceKey hContext : ptg.getPointsToSet(interProc.getReplica(i.getVal(), currentNode))) {
                IClass actual = hContext.getConcreteType();
                if (!cha.isSubclassOf(actual, checked)) {
                    castAlwaysSucceeds = false;
                    break;
                }
            }
        }

        if (castAlwaysSucceeds) {
            // This is either a cast of the "null" literal or an upcast and
            // cannot throw a ClassCastException
            throwables = new LinkedHashSet<>(throwables);
            throwables.remove(TypeReference.JavaLangClassCastException);
        }
        return computeResults(throwables, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowGetField(SSAGetInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return computeResults(i, !nonNullResults.isNonNull(i.getRef(), i, currentNode), cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowInvokeInterface(SSAInvokeInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowInvokeSpecial(SSAInvokeInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowInvokeStatic(SSAInvokeInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowInvokeVirtual(SSAInvokeInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowGoto(SSAGotoInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowLoadMetadata(SSALoadMetadataInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowMonitor(SSAMonitorInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return computeResults(i, !nonNullResults.isNonNull(i.getRef(), i, currentNode), cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowNewArray(SSANewInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        Collection<TypeReference> throwables = PreciseExceptionResults.implicitExceptions(i);
        boolean constantArraySize = true;

        List<Integer> dimensions = new ArrayList<>(i.getNumberOfUses());
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            // see if jth dimension is a positive integer constant
            Integer jthSize = null;
            if (currentNode.getIR().getSymbolTable().isConstant(i.getUse(j))
                                            && currentNode.getIR().getSymbolTable().getIntValue(i.getUse(j)) >= 0) {
                jthSize = currentNode.getIR().getSymbolTable().getIntValue(i.getUse(j));
            }
            dimensions.add(jthSize);
            constantArraySize &= (jthSize != null);
        }

        arrayDimensions.put(i.getDef(), dimensions);

        if (constantArraySize) {
            throwables = new LinkedHashSet<>(throwables);
            throwables.remove(TypeReference.JavaLangNegativeArraySizeException);
        }

        return computeResults(throwables, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowNewObject(SSANewInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowPutField(SSAPutInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return computeResults(i, !nonNullResults.isNonNull(i.getRef(), i, currentNode), cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowReturn(SSAReturnInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowSwitch(SSASwitchInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowThrow(SSAThrowInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        Collection<TypeReference> throwables = new LinkedHashSet<>(PreciseExceptionResults.implicitExceptions(i));
        throwables.add(TypeRepository.getType(i.getException(), currentNode.getIR()));
        return computeResults(i, !nonNullResults.isNonNull(i.getException(), i, currentNode), cfg, current);
    }

    /**
     * Many of the instructions can be computed in the same way, look up the set
     * of implicit exceptions this exception could throw and copy elements of
     * that set on each exceptions edge that could throw it
     * 
     * @param i
     *            instruction
     * @param canThrowNPE
     *            whether i could throw a null pointer exception
     * @param cfg
     *            control flow graph
     * @param current
     *            current call graph node
     * @return map from successor basic block number to exceptions thrown on
     *         that edge
     */
    private Map<ISSABasicBlock, PreciseExceptionAbsVal> computeResults(SSAInstruction i, boolean canThrowNPE,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        Collection<TypeReference> types = PreciseExceptionResults.implicitExceptions(i);
        if (!canThrowNPE) {
            types = new LinkedHashSet<>(types);
            types.remove(TypeReference.JavaLangNullPointerException);
        }

        return computeResults(types, cfg, current);
    }

    /**
     * Create an exit map from the given set of exceptions
     * 
     * @param types
     *            exceptions that can be thrown
     * @param cfg
     *            control flow graph
     * @param current
     *            current call graph node
     * @return map from successor basic block number to exceptions thrown on
     *         that edge
     */
    private Map<ISSABasicBlock, PreciseExceptionAbsVal> computeResults(Collection<TypeReference> types,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        Map<ISSABasicBlock, Set<TypeReference>> typesForSuccs = new HashMap<>();
        for (TypeReference type : types) {
            Set<ISSABasicBlock> succs = getSuccessorsForExceptionType(type, cfg, current, cha);
            for (ISSABasicBlock succ : succs) {
                Set<TypeReference> typesForSucc = typesForSuccs.get(succ);
                if (typesForSucc == null) {
                    typesForSucc = new HashSet<>();
                    typesForSuccs.put(succ, typesForSucc);
                }
                typesForSucc.add(type);
            }
        }

        Map<ISSABasicBlock, PreciseExceptionAbsVal> results = new HashMap<>();
        Collection<ISSABasicBlock> exSuccs = cfg.getExceptionalSuccessors(current);
        for (ISSABasicBlock succ : exSuccs) {
            Set<TypeReference> typesForSucc = typesForSuccs.get(succ);
            if (typesForSucc == null) {
                // No exceptions on this edges
                results.put(succ, PreciseExceptionAbsVal.EMPTY);
            } else {
                results.put(succ, new PreciseExceptionAbsVal(typesForSucc));
            }
        }

        Collection<ISSABasicBlock> normalSuccs = cfg.getNormalSuccessors(current);
        for (ISSABasicBlock succ : normalSuccs) {
            results.put(succ, PreciseExceptionAbsVal.EMPTY);
        }

        return results;
    }

    /**
     * Decide whether the given index is definitely in bounds for the given
     * array
     * 
     * @param arrayValNumber
     *            value number for array
     * @param indexValNumber
     *            value number for index (not the actual index)
     * @return true if this access cannot throw an
     *         {@link ArrayIndexOutOfBoundsException}
     */
    private boolean isSafeArrayIndex(int arrayValNumber, int indexValNumber) {
        // TODO track array indices inter-procedurally
        if (arrayDimensions.containsKey(arrayValNumber)) {
            List<Integer> dims = arrayDimensions.get(arrayValNumber);
            if (dims.get(0) != null) {
                // constant dimension
                int size = dims.get(0);
                int index = !currentNode.getIR().getSymbolTable().isConstant(indexValNumber) ? -1 : currentNode.getIR()
                                                .getSymbolTable().getIntValue(indexValNumber);
                if (index >= 0 && index < size) {
                    // safe index
                    return true;
                }
            }
        }
        return false;
    }
}
