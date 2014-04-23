package analysis.dataflow.interprocedural.exceptions;

import java.util.Map;
import java.util.Set;

import analysis.dataflow.interprocedural.InterproceduralDataFlowManager;
import analysis.dataflow.util.ExitType;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.types.TypeReference;

public class PreciseExceptionManager extends InterproceduralDataFlowManager<PreciseExceptionAbsVal> {

    private final PreciseExceptionResults preciseEx;

    public PreciseExceptionManager(CallGraph cg, PointsToGraph ptg) {
        super(cg, ptg);
        preciseEx = new PreciseExceptionResults();
    }
    
    protected void replaceExceptions(Set<TypeReference> throwTypes, ISSABasicBlock bb, ISSABasicBlock successor, CGNode containingNode) {
        preciseEx.replaceExceptions(throwTypes, bb, successor, containingNode);
    }

    @Override
    protected Map<ExitType, PreciseExceptionAbsVal> analyze(CGNode n, PreciseExceptionAbsVal input) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ExitType, PreciseExceptionAbsVal> getDefaultOutput(PreciseExceptionAbsVal input) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PreciseExceptionAbsVal getInputForRoot() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PreciseExceptionAbsVal join(PreciseExceptionAbsVal fact1, PreciseExceptionAbsVal fact2) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected boolean outputChanged(Map<ExitType, PreciseExceptionAbsVal> previousOutput,
                                    Map<ExitType, PreciseExceptionAbsVal> currentOutput) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    protected boolean existingResultSuitable(
                                    PreciseExceptionAbsVal newInput,
                                    analysis.dataflow.interprocedural.InterproceduralDataFlowManager.AnalysisRecord<PreciseExceptionAbsVal> existingResults) {
        // TODO Auto-generated method stub
        return false;
    }
    
    /**
     * Add the given successor to the set of unreachable successors from
     * <code>source</code>
     * 
     * @param source
     *            source node
     * @param successor
     *            unreachable successor
     * @param containingNode
     *            call graph node containing the basic blocks
     */
    protected void addImpossibleSuccessor(ISSABasicBlock source, ISSABasicBlock successor, CGNode containingNode) {
        preciseEx.addImpossibleSuccessor(source, successor, containingNode);
    }

}
