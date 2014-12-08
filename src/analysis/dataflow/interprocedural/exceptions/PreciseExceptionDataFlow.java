package analysis.dataflow.interprocedural.exceptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.IntraproceduralDataFlow;
import analysis.dataflow.interprocedural.nonnull.NonNullResults;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
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
     * Results of a non-null analysis
     */
    private final NonNullResults nonNullResults;
    /**
     * Types of local variables
     */
    private final TypeRepository types;
    /**
     * Map from array to list of integer dimensions for the array (from outside in), value in the list is null if the
     * dimension is not a static integer
     */
    private final Map<Integer, List<Integer>> arrayDimensions = new HashMap<>();

    public PreciseExceptionDataFlow(NonNullResults nonNullResults, CGNode currentNode,
                                    InterproceduralDataFlow<PreciseExceptionAbsVal> interProc) {
        super(currentNode, interProc);
        this.nonNullResults = nonNullResults;
        this.types = new TypeRepository(currentNode.getIR());
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> call(SSAInvokeInstruction i,
                                                               Set<PreciseExceptionAbsVal> inItems,
                                                               ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                               ISSABasicBlock bb) {
        Set<TypeReference> throwables = new LinkedHashSet<>(PreciseExceptionResults.implicitExceptions(i));
        if (!i.isStatic() && nonNullResults.isNonNull(i.getReceiver(), i, currentNode, types)) {
            throwables.remove(TypeReference.JavaLangNullPointerException);
        }

        Set<CGNode> targets = cg.getPossibleTargets(currentNode, i.getCallSite());
        if (targets.isEmpty()) {
            return guessResultsForMissingReceiver(i, new PreciseExceptionAbsVal(throwables), cfg, bb);
        }

        PreciseExceptionAbsVal exceptionResult = null;
        for (CGNode callee : targets) {
            Map<ExitType, PreciseExceptionAbsVal> out = interProc.getResults(currentNode,
                                                                             callee,
                                                                             PreciseExceptionAbsVal.EMPTY);

            // There should be no exceptions on normal exit
            assert out.get(ExitType.NORMAL) == null || out.get(ExitType.NORMAL).isBottom();

            Set<PreciseExceptionAbsVal> exceptions = new LinkedHashSet<>();
            if (exceptionResult != null) {
                exceptions.add(exceptionResult);
            }

            if (out.get(ExitType.EXCEPTIONAL) != null) {
                exceptions.add(out.get(ExitType.EXCEPTIONAL));
            }

            if (!exceptions.isEmpty()) {
                exceptionResult = confluence(exceptions, bb);
            }
        }

        if (exceptionResult != null) {
            throwables.addAll(exceptionResult.getThrowables());
        }
        return computeResults(throwables, cfg, bb);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> guessResultsForMissingReceiver(SSAInvokeInstruction i,
                                                                                         PreciseExceptionAbsVal input,
                                                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                         ISSABasicBlock bb) {
        if (outputLevel >= 1) {
            System.err.println("No calls to " + PrettyPrinter.methodString(i.getDeclaredTarget()) + " from "
                    + PrettyPrinter.cgNodeString(currentNode));
        }
        // Assume this can throw RuntimeException and any declared exceptions
        // TODO Unsound since catch blocks for sub-types will not be reachable along edges that throw the super-type
        // Also unsound since we have to try to resolve as best we can, but might miss some declared exceptions
        Set<TypeReference> throwables = new LinkedHashSet<>(input.getThrowables());
        throwables.add(TypeReference.JavaLangRuntimeException);

        IMethod resolved = AnalysisUtil.getClassHierarchy().resolveMethod(i.getDeclaredTarget());
        if (resolved == null) {
            System.err.println("Could not resolve method with missing receiver. Precise exceptions will be unsound.");
        }
        else {
            try {
                throwables.addAll(Arrays.asList(resolved.getDeclaredExceptions()));
            }
            catch (UnsupportedOperationException | InvalidClassFileException e) {
                System.err.println("Could not resolve method with missing receiver. Precise exceptions will be unsound.");
            }
        }
        return computeResults(throwables, cfg, bb);
    }

    @Override
    protected PreciseExceptionAbsVal confluence(Set<PreciseExceptionAbsVal> items, ISSABasicBlock bb) {
        assert !items.isEmpty();
        PreciseExceptionAbsVal val = null;
        for (PreciseExceptionAbsVal item : items) {
            assert item != null;
            val = item.join(val);
        }
        return val;
    }

    @Override
    protected void postBasicBlock(ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock justProcessed,
                                  Map<ISSABasicBlock, PreciseExceptionAbsVal> outItems) {

        for (ISSABasicBlock next : getNormalSuccs(justProcessed, cfg)) {
            if (outItems.get(next) != null && !outItems.get(next).isEmpty()) {
                throw new RuntimeException("Exceptions for normal successor of " + " BB"
                        + justProcessed.getGraphNodeId() + " to BB" + next.getGraphNodeId());
            }
        }

        for (ISSABasicBlock next : getExceptionalSuccs(justProcessed, cfg)) {
            if (outItems.get(next) != null) {
                PreciseExceptionResults results = ((PreciseExceptionInterproceduralDataFlow) interProc).getAnalysisResults();
                results.replaceExceptions(outItems.get(next).getThrowables(), justProcessed, next, currentNode);
            }
        }

        super.postBasicBlock(cfg, justProcessed, outItems);
    }

    @Override
    protected PreciseExceptionAbsVal flowBinaryOp(SSABinaryOpInstruction i, Set<PreciseExceptionAbsVal> previousItems,
                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                  ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowComparison(SSAComparisonInstruction i,
                                                    Set<PreciseExceptionAbsVal> previousItems,
                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                    ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowConversion(SSAConversionInstruction i,
                                                    Set<PreciseExceptionAbsVal> previousItems,
                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                    ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
                                                            Set<PreciseExceptionAbsVal> previousItems,
                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                            ISSABasicBlock current) {
        // Could be incoming exceptions
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowGetStatic(SSAGetInstruction i, Set<PreciseExceptionAbsVal> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowInstanceOf(SSAInstanceofInstruction i,
                                                    Set<PreciseExceptionAbsVal> previousItems,
                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                    ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowPhi(SSAPhiInstruction i, Set<PreciseExceptionAbsVal> previousItems,
                                             ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                             ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowPutStatic(SSAPutInstruction i, Set<PreciseExceptionAbsVal> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowUnaryNegation(SSAUnaryOpInstruction i,
                                                       Set<PreciseExceptionAbsVal> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowArrayLength(SSAArrayLengthInstruction i,
                                                                          Set<PreciseExceptionAbsVal> previousItems,
                                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                          ISSABasicBlock current) {
        return computeResults(i, !nonNullResults.isNonNull(i.getArrayRef(), i, currentNode, types), cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowArrayLoad(SSAArrayLoadInstruction i,
                                                                        Set<PreciseExceptionAbsVal> previousItems,
                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                        ISSABasicBlock current) {
        Collection<TypeReference> throwables = new LinkedHashSet<>(PreciseExceptionResults.implicitExceptions(i));

        // handle the array dimensions
        if (isSafeArrayIndex(i.getArrayRef(), i.getIndex())) {
            throwables.remove(TypeReference.JavaLangArrayIndexOutOfBoundsException);
        }

        // if there are multiple dimensions then the assignee is also an array
        // use the dimensions from the parent array for the assignee
        List<Integer> dims = arrayDimensions.get(i.getArrayRef());
        if (dims != null && dims.size() > 1) {
            List<Integer> subDims = dims.subList(1, dims.size());
            arrayDimensions.put(i.getDef(), subDims);
        }

        if (nonNullResults.isNonNull(i.getArrayRef(), i, currentNode, types)) {
            throwables.remove(TypeReference.JavaLangNullPointerException);
        }

        return computeResults(throwables, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowArrayStore(SSAArrayStoreInstruction i,
                                                                         Set<PreciseExceptionAbsVal> previousItems,
                                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                         ISSABasicBlock current) {
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();

        Collection<TypeReference> throwables = new LinkedHashSet<>(PreciseExceptionResults.implicitExceptions(i));
        if (nonNullResults.isNonNull(i.getArrayRef(), i, currentNode, types)) {
            throwables.remove(TypeReference.JavaLangNullPointerException);
        }

        TypeReference elementType = i.getElementType();
        TypeReference storedType = types.getType(i.getUse(2));
        if (!elementType.isPrimitiveType() && currentNode.getIR().getSymbolTable().isNullConstant(i.getUse(2))) {
            // Null can be passed into any non-primitive array
            throwables.remove(TypeReference.JavaLangArrayStoreException);
        }
        else if (elementType.isPrimitiveType() && isWideningPrimitiveConversion(storedType, elementType)) {
            // Implicitly castable primitive
            throwables.remove(TypeReference.JavaLangArrayStoreException);
        }
        else if (!elementType.isPrimitiveType()
                && cha.isAssignableFrom(cha.lookupClass(storedType), cha.lookupClass(elementType))) {
            // Storing a subtype
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
                                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                    ISSABasicBlock current) {
        Collection<TypeReference> throwables = PreciseExceptionResults.implicitExceptions(i);

        Integer arg = currentNode.getIR().getSymbolTable().isConstant(i.getUse(1))
                ? currentNode.getIR().getSymbolTable().getIntValue(i.getUse(1)) : null;
        if (arg != null && arg != 0) {
            throwables = Collections.emptySet();
        }
        return computeResults(throwables, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowCheckCast(SSACheckCastInstruction i,
                                                                        Set<PreciseExceptionAbsVal> previousItems,
                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                        ISSABasicBlock current) {
        Collection<TypeReference> throwables = PreciseExceptionResults.implicitExceptions(i);

        // Upcasts and casts of null are always safe, see if either case holds
        // TODO track "definitely null" in the non-null analysis
        boolean castAlwaysSucceeds = true;
        IClass checked = AnalysisUtil.getClassHierarchy().lookupClass(i.getDeclaredResultTypes()[0]);
        assert checked != null;
        if (!currentNode.getIR().getSymbolTable().isNullConstant(i.getVal())) {
            Iterator<? extends InstanceKey> iter = ptg.pointsToIterator(interProc.getReplica(i.getVal(), currentNode));
            while (iter.hasNext()) {
                InstanceKey hContext = iter.next();
                IClass actual = hContext.getConcreteType();
                if (!AnalysisUtil.getClassHierarchy().isAssignableFrom(checked, actual)) {
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
                                                                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                                ISSABasicBlock current) {
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowGetField(SSAGetInstruction i,
                                                                       Set<PreciseExceptionAbsVal> previousItems,
                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                       ISSABasicBlock current) {
        return computeResults(i, !nonNullResults.isNonNull(i.getRef(), i, currentNode, types), cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowInvokeInterface(SSAInvokeInstruction i,
                                                                              Set<PreciseExceptionAbsVal> previousItems,
                                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                              ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowInvokeSpecial(SSAInvokeInstruction i,
                                                                            Set<PreciseExceptionAbsVal> previousItems,
                                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                            ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowInvokeStatic(SSAInvokeInstruction i,
                                                                           Set<PreciseExceptionAbsVal> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowInvokeVirtual(SSAInvokeInstruction i,
                                                                            Set<PreciseExceptionAbsVal> previousItems,
                                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                            ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowGoto(SSAGotoInstruction i,
                                                                   Set<PreciseExceptionAbsVal> previousItems,
                                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                   ISSABasicBlock current) {
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowLoadMetadata(SSALoadMetadataInstruction i,
                                                                           Set<PreciseExceptionAbsVal> previousItems,
                                                                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                           ISSABasicBlock current) {
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowMonitor(SSAMonitorInstruction i,
                                                                      Set<PreciseExceptionAbsVal> previousItems,
                                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                      ISSABasicBlock current) {
        return computeResults(i, !nonNullResults.isNonNull(i.getRef(), i, currentNode, types), cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowNewArray(SSANewInstruction i,
                                                                       Set<PreciseExceptionAbsVal> previousItems,
                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                       ISSABasicBlock current) {
        Collection<TypeReference> throwables = PreciseExceptionResults.implicitExceptions(i);
        boolean constantArraySize = true;

        List<Integer> dimensions = new ArrayList<>(i.getNumberOfUses());
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            // see if jth dimension is a non-negative integer constant
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
                                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                        ISSABasicBlock current) {
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowPutField(SSAPutInstruction i,
                                                                       Set<PreciseExceptionAbsVal> previousItems,
                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                       ISSABasicBlock current) {
        return computeResults(i, !nonNullResults.isNonNull(i.getRef(), i, currentNode, types), cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowReturn(SSAReturnInstruction i,
                                                                     Set<PreciseExceptionAbsVal> previousItems,
                                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                     ISSABasicBlock current) {
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowSwitch(SSASwitchInstruction i,
                                                                     Set<PreciseExceptionAbsVal> previousItems,
                                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                     ISSABasicBlock current) {
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, PreciseExceptionAbsVal> flowThrow(SSAThrowInstruction i,
                                                                    Set<PreciseExceptionAbsVal> previousItems,
                                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                    ISSABasicBlock current) {
        Collection<TypeReference> throwables = new LinkedHashSet<>(PreciseExceptionResults.implicitExceptions(i));
        if (nonNullResults.isNonNull(i.getException(), i, currentNode, types)) {
            // The exception cannot be null so this cannot _implicitly_ throw a NullPointerException
            throwables = new LinkedHashSet<>(throwables);
            throwables.remove(TypeReference.JavaLangNullPointerException);
        }
        throwables.add(types.getType(i.getException()));

        return computeResults(throwables, cfg, current);
    }

    /**
     * Many of the instructions can be computed in the same way, look up the set of implicit exceptions this exception
     * could throw and copy elements of that set on each exceptions edge that could throw it
     *
     * @param i instruction
     * @param canThrowNPE whether i could throw a null pointer exception
     * @param cfg control flow graph
     * @param current current call graph node
     * @return map from successor basic block number to exceptions thrown on that edge
     */
    private Map<ISSABasicBlock, PreciseExceptionAbsVal> computeResults(SSAInstruction i,
                                                                       boolean canThrowNPE,
                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                       ISSABasicBlock current) {
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
     * @param types exceptions that can be thrown
     * @param cfg control flow graph
     * @param current current call graph node
     * @return map from successor basic block number to exceptions thrown on that edge
     */
    private Map<ISSABasicBlock, PreciseExceptionAbsVal> computeResults(Collection<TypeReference> types,
                                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                       ISSABasicBlock current) {
        Map<ISSABasicBlock, Set<TypeReference>> typesForSuccs = new HashMap<>();
        for (TypeReference type : types) {
            Set<ISSABasicBlock> succs = getSuccessorsForExceptionType(type, cfg, current);
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
        for (ISSABasicBlock succ : getExceptionalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                Set<TypeReference> typesForSucc = typesForSuccs.get(succ);
                if (typesForSucc == null) {
                    // No exceptions on this edges
                    results.put(succ, PreciseExceptionAbsVal.EMPTY);
                }
                else {
                    results.put(succ, new PreciseExceptionAbsVal(typesForSucc));
                }
            }
        }

        for (ISSABasicBlock succ : getNormalSuccs(current, cfg)) {
            if (!isUnreachable(current, succ)) {
                results.put(succ, PreciseExceptionAbsVal.EMPTY);
            }
        }

        return results;
    }

    /**
     * Decide whether the given index is definitely in bounds for the given array
     *
     * @param arrayValNumber value number for array
     * @param indexValNumber value number for index (not the actual index)
     * @return true if this access cannot throw an {@link ArrayIndexOutOfBoundsException}
     */
    private boolean isSafeArrayIndex(int arrayValNumber, int indexValNumber) {
        // TODO track array indices inter-procedurally
        if (arrayDimensions.containsKey(arrayValNumber)) {
            List<Integer> dims = arrayDimensions.get(arrayValNumber);
            if (dims.get(0) != null) {
                // constant dimension
                int size = dims.get(0);
                int index = currentNode.getIR().getSymbolTable().isConstant(indexValNumber)
                        ? currentNode.getIR().getSymbolTable().getIntValue(indexValNumber) : -1;
                if (index >= 0 && index < size) {
                    // safe index
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Is the conversion between primitive types from sourceType to targetType a widening conversion (meaning it can be
     * done implicitly). See JLS 5.1.2.
     * <p>
     * i.e is target = source valid with no cast
     * <ul>
     * <li>byte to short, int, long, float, or double</li>
     * <li>short to int, long, float, or double</li>
     * <li>char to int, long, float, or double</li>
     * <li>int to long, float, or double</li>
     * <li>long to float or double</li>
     * <li>float to double</li>
     * </ul>
     *
     * @param targetType type of the target variable
     * @param sourceType type of the source expression
     * @return true if it is safe to assign a primitive of type sourceType to one of type targetType with no explicit
     *         cast
     */
    private static boolean isWideningPrimitiveConversion(TypeReference sourceType, TypeReference targetType) {
        assert sourceType.isPrimitiveType();
        assert targetType.isPrimitiveType();

        if (sourceType.equals(targetType)) {
            return true;
        }

        if (sourceType == TypeReference.Byte) {
            if (targetType == TypeReference.Short || targetType == TypeReference.Int
                    || targetType == TypeReference.Long || targetType == TypeReference.Float
                    || targetType == TypeReference.Double) {
                return true;
            }
        }

        if (sourceType.equals(TypeReference.Short)) {
            if (targetType == TypeReference.Int || targetType == TypeReference.Long
                    || targetType == TypeReference.Float || targetType == TypeReference.Double) {
                return true;
            }
        }

        if (sourceType.equals(TypeReference.Int)) {
            if (targetType == TypeReference.Long || targetType == TypeReference.Float
                    || targetType == TypeReference.Double) {
                return true;
            }
        }

        if (sourceType.equals(TypeReference.Long)) {
            if (targetType == TypeReference.Float || targetType == TypeReference.Double) {
                return true;
            }
        }

        if (sourceType.equals(TypeReference.Float)) {
            if (targetType == TypeReference.Double) {
                return true;
            }
        }
        return false;
    }
}
