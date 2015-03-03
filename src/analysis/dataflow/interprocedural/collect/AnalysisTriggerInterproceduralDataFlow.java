package analysis.dataflow.interprocedural.collect;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;

import util.SingletonValueMap;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.dataflow.util.Unit;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableCache;

import com.ibm.wala.ipa.callgraph.CGNode;

/**
 * Interprocedural analysis for which the results for a callee are not used by the caller, but for which each call graph
 * node must be analyzed exactly once. Calling
 * {@link AnalysisTriggerInterproceduralDataFlow#getResults(CGNode, CGNode, Unit)} will trigger the callee to be
 * analyzed if it hasn't already been analyzed.
 */
public abstract class AnalysisTriggerInterproceduralDataFlow extends InterproceduralDataFlow<Unit> {

    /**
     * Map each exit type to UNIT
     */
    protected static final Map<ExitType, Unit> UNIT_MAP = new SingletonValueMap<>(new LinkedHashSet<>(
            Arrays.asList(ExitType.values())), Unit.VALUE);

    /**
     * Create an interprocedural analysis where the results for a callee are not used, but where each call graph node is
     * analyzed once.
     *
     * @param ptg points-to graph
     * @param reachable reachability results
     * @param rvCache cache of reference variables
     */
    public AnalysisTriggerInterproceduralDataFlow(PointsToGraph ptg, ReachabilityResults reachable,
                                                  ReferenceVariableCache rvCache) {
        super(ptg, reachable, rvCache);
    }

    @Override
    protected final Map<ExitType, Unit> getDefaultOutput(Unit input) {
        return UNIT_MAP;
    }

    @Override
    protected final Unit getInputForEntryPoint() {
        return Unit.VALUE;
    }

    @Override
    protected final boolean outputChanged(Map<ExitType, Unit> previousOutput, Map<ExitType, Unit> currentOutput) {
        return false;
    }

    @Override
    protected final boolean existingResultSuitable(Unit newInput,
                                             analysis.dataflow.interprocedural.InterproceduralDataFlow.AnalysisRecord<Unit> existingResults) {
        return existingResults.getOutput() != null;
    }

    @Override
    public final Map<ExitType, Unit> getResults(CGNode caller, CGNode callee, Unit input) {
        if (!currentlyProcessing.contains(callee) && !recordedResults.containsRecord(callee)) {
            recordedResults.setInitialRecord(callee, new AnalysisRecord<>(Unit.VALUE, null, true));
            processCallGraphNode(callee);
        }
        return UNIT_MAP;
    }
}
