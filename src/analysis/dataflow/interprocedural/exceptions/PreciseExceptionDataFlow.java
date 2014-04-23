package analysis.dataflow.interprocedural.exceptions;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.InterproceduralDataFlowManager;
import analysis.dataflow.interprocedural.nonnull.NonNullResults;
import analysis.dataflow.util.ExitType;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.CGNode;
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

public class PreciseExceptionDataFlow extends InterproceduralDataFlow<PreciseExceptionAbsVal> {

    /**
     * Holds the results of the analysis
     */
    private final PreciseExceptionResults preciseEx;
    /**
     * Class hierarchy
     */
    private final IClassHierarchy cha;
    /**
     * Results of a non-null analysis
     */
    private NonNullResults nonNullResults;

    public PreciseExceptionDataFlow(NonNullResults nonNullResults, CGNode currentNode,
                                    InterproceduralDataFlowManager<PreciseExceptionAbsVal> manager, IClassHierarchy cha) {
        super(currentNode, manager);
        this.nonNullResults = nonNullResults;
        this.cha = cha;
        preciseEx = new PreciseExceptionResults();
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> call(SSAInvokeInstruction i, Set<PreciseExceptionAbsVal> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb) {
        Collection<TypeReference> throwables = PreciseExceptionResults.implicitExceptions(i);
        if (!i.isStatic() && nonNullResults.isNonNull(i.getReceiver(), i, currentNode)) {
            throwables.remove(TypeReference.JavaLangNullPointerException);
        }

        PreciseExceptionAbsVal exceptionResult = null;
        for (CGNode callee : cg.getPossibleTargets(currentNode, i.getCallSite())) {
            Map<ExitType, PreciseExceptionAbsVal> out = manager.getResults(currentNode, callee,
                                            PreciseExceptionAbsVal.EMPTY);

            if (!out.get(ExitType.NORM_TERM).isBottom()) {
                throw new RuntimeException("Exceptions for normal termination from "
                                                + PrettyPrinter.parseCGNode(callee) + " called from "
                                                + PrettyPrinter.parseCGNode(currentNode));
            }

            exceptionResult = confluence(exceptionResult, out.get(ExitType.EXCEPTION));
        }

        throwables.addAll(exceptionResult.getThrowables());
        return computeResults(throwables, cfg, bb);
    }

    @Override
    protected PreciseExceptionAbsVal confluence(Set<PreciseExceptionAbsVal> items) {
        PreciseExceptionAbsVal val = null;
        for (PreciseExceptionAbsVal item : items) {
            val = item.join(val);
        }
        if (val == null) {
            throw new RuntimeException("Got null absval in confluence.");
        }
        return val;
    }

    @Override
    protected void postBasicBlock(Set<PreciseExceptionAbsVal> inItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock justProcessed,
                                    Map<Integer, PreciseExceptionAbsVal> outItems) {
        Iterator<ISSABasicBlock> nextBlocks = cfg.getSuccNodes(justProcessed);
        while (nextBlocks.hasNext()) {
            ISSABasicBlock next = nextBlocks.next();
            checkResults(justProcessed, next, outItems);
            Set<TypeReference> throwables = outItems.get(next.getGraphNodeId()).getThrowables();
            ((PreciseExceptionManager) manager).replaceExceptions(throwables, justProcessed, next, currentNode);
            if (throwables.isEmpty()) {
                ((PreciseExceptionManager) manager).addImpossibleSuccessor(justProcessed, next, currentNode);
            }
        }
        super.postBasicBlock(inItems, cfg, justProcessed, outItems);
    }

    /**
     * Verify the results
     * 
     * @param justProcessed
     *            basic block that was just processed
     * @param next
     *            successor basic block
     * @param outItems
     *            output after analyzing the basic block
     */
    private void checkResults(ISSABasicBlock justProcessed, ISSABasicBlock next,
                                    Map<Integer, PreciseExceptionAbsVal> outItems) {
        if (outItems.get(next.getGraphNodeId()) == null
                                        && !preciseEx.getImpossibleSuccessors(justProcessed, currentNode)
                                                                        .contains(next)) {
            throw new RuntimeException("No item for successor of\n"
                                            + PrettyPrinter.basicBlockString(currentNode.getIR(), justProcessed, "\t",
                                                                            "\n") + " from BB"
                                            + justProcessed.getGraphNodeId() + " to BB" + next.getGraphNodeId());
        }
    }

    @Override
    protected PreciseExceptionAbsVal flowBinaryOp(SSABinaryOpInstruction i, Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowComparison(SSAComparisonInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowConversion(SSAConversionInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
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
        assert confluence(previousItems).getThrowables().isEmpty();
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowInstanceOf(SSAInstanceofInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowPhi(SSAPhiInstruction i, Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowPutStatic(SSAPutInstruction i, Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected PreciseExceptionAbsVal flowUnaryNegation(SSAUnaryOpInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowArrayLength(SSAArrayLengthInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return computeResults(i, !nonNullResults.isNonNull(i.getArrayRef(), i, currentNode), cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowArrayLoad(SSAArrayLoadInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return computeResults(i, !nonNullResults.isNonNull(i.getArrayRef(), i, currentNode), cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowArrayStore(SSAArrayStoreInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        Collection<TypeReference> throwables = PreciseExceptionResults.implicitExceptions(i);
        if (nonNullResults.isNonNull(i.getArrayRef(), i, currentNode)) {
            throwables.remove(TypeReference.JavaLangNullPointerException);
        }

        IClass elementType = cha.lookupClass(i.getElementType());
        IClass storedType = cha.lookupClass(TypeRepository.getType(i.getDef(), currentNode.getIR()));
        if (cha.isSubclassOf(storedType, elementType)) {
            throwables.remove(TypeReference.JavaLangArrayStoreException);
        }

        return computeResults(throwables, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowCheckCast(SSACheckCastInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        Collection<TypeReference> throwables = PreciseExceptionResults.implicitExceptions(i);
        if (currentNode.getIR().getSymbolTable().isNullConstant(i.getVal())) {
            // TODO Keep "definitely null" in non-null analysis

            // Null objects can be cast to anything
            throwables.remove(TypeReference.JavaLangClassCastException);
        }

        IClass typeOfValue = cha.lookupClass(TypeRepository.getType(i.getVal(), currentNode.getIR()));
        IClass checkedType = cha.lookupClass(i.getDeclaredResultTypes()[0]);
        if (cha.isSubclassOf(typeOfValue, checkedType)) {
            // upcasts are always safe
            throwables.remove(TypeReference.JavaLangArrayStoreException);
        }
        return computeResults(throwables, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowGetField(SSAGetInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return computeResults(i, !nonNullResults.isNonNull(i.getRef(), i, currentNode), cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowInvokeInterface(SSAInvokeInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowInvokeSpecial(SSAInvokeInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowInvokeStatic(SSAInvokeInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowInvokeVirtual(SSAInvokeInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowGoto(SSAGotoInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowLoadMetadata(SSALoadMetadataInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowMonitor(SSAMonitorInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return computeResults(i, !nonNullResults.isNonNull(i.getRef(), i, currentNode), cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowNewArray(SSANewInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        Collection<TypeReference> throwables = PreciseExceptionResults.implicitExceptions(i);
        boolean constantArraySize = true;
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            // see if jth dimension is a positive integer constant
            constantArraySize &= (currentNode.getIR().getSymbolTable().isConstant(i.getUse(j)) && currentNode.getIR()
                                            .getSymbolTable().getIntValue(i.getUse(j)) > 0);
        }

        if (constantArraySize) {
            throwables.remove(TypeReference.JavaLangNegativeArraySizeException);
        }

        return computeResults(throwables, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowNewObject(SSANewInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowPutField(SSAPutInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return computeResults(i, !nonNullResults.isNonNull(i.getRef(), i, currentNode), cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowReturn(SSAReturnInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowSwitch(SSASwitchInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        return computeResults(i, false, cfg, current);
    }

    @Override
    protected Map<Integer, PreciseExceptionAbsVal> flowThrow(SSAThrowInstruction i,
                                    Set<PreciseExceptionAbsVal> previousItems,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert confluence(previousItems).getThrowables().isEmpty();
        Collection<TypeReference> throwables = PreciseExceptionResults.implicitExceptions(i);
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
    private Map<Integer, PreciseExceptionAbsVal> computeResults(SSAInstruction i, boolean canThrowNPE,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        Collection<TypeReference> types = PreciseExceptionResults.implicitExceptions(i);
        if (!canThrowNPE) {
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
    private Map<Integer, PreciseExceptionAbsVal> computeResults(Collection<TypeReference> types,
                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        Map<Integer, Set<TypeReference>> typesForSuccs = new HashMap<>();
        for (TypeReference type : types) {
            // TODO is it unsound to use the impossible successors here?
            Set<ISSABasicBlock> succs = getSuccessorsForExceptionType(type, cfg, current, cha,
                                            Collections.<ISSABasicBlock> emptySet());
            for (ISSABasicBlock succ : succs) {
                Set<TypeReference> typesForSucc = typesForSuccs.get(succ.getGraphNodeId());
                if (typesForSucc == null) {
                    typesForSucc = new HashSet<>();
                }
                typesForSucc.add(type);
            }
        }

        Map<Integer, PreciseExceptionAbsVal> results = new HashMap<>();
        for (Integer succNum : typesForSuccs.keySet()) {
            Set<TypeReference> typesForSucc = typesForSuccs.get(succNum);
            results.put(succNum, new PreciseExceptionAbsVal(typesForSucc));
        }

        Collection<ISSABasicBlock> normalSuccs = cfg.getNormalSuccessors(current);
        for (ISSABasicBlock succ : normalSuccs) {
            results.put(succ.getGraphNodeId(), PreciseExceptionAbsVal.EMPTY);
        }

        return results;
    }
}
