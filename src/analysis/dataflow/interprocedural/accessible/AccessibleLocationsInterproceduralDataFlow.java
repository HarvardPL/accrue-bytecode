package analysis.dataflow.interprocedural.accessible;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;

import util.SingletonValueMap;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableCache;

import com.ibm.wala.ipa.callgraph.CGNode;

/**
 * Analysis that computes the abstract locations reachable from each call graph node
 */
public class AccessibleLocationsInterproceduralDataFlow extends InterproceduralDataFlow<AbstractLocationSet> {

    /**
     * Results after running the analysis
     */
    private final AccessibleLocationResults aResults = new AccessibleLocationResults();

    /**
     * Create a new analysis
     *
     * @param ptg points-to graph
     * @param reachable results of running a reachability analysis
     * @param rvCache cache of reference variables
     */
    public AccessibleLocationsInterproceduralDataFlow(PointsToGraph ptg, ReachabilityResults reachable,
                                                      ReferenceVariableCache rvCache) {
        super(ptg, reachable, rvCache);
    }

    @Override
    protected String getAnalysisName() {
        return "Accessible locations data-flow";
    }

    @Override
    public AccessibleLocationResults getAnalysisResults() {
        return aResults;
    }

    @Override
    protected Map<ExitType, AbstractLocationSet> analyze(CGNode n, AbstractLocationSet input) {
        AccessibleLocationsDataFlow df = new AccessibleLocationsDataFlow(n, this);
        return df.dataflow(input);
    }

    /**
     * Static map from exit type to an empty set that cannot be modified
     */
    static final Map<ExitType, AbstractLocationSet> EMPTY_MAP = new SingletonValueMap<>(new LinkedHashSet<>(Arrays.asList(ExitType.values())),
                                                                                        AbstractLocationSet.EMPTY);

    @Override
    protected Map<ExitType, AbstractLocationSet> analyzeMissingCode(CGNode n, AbstractLocationSet input) {
        return EMPTY_MAP;
    }

    @Override
    protected Map<ExitType, AbstractLocationSet> getDefaultOutput(AbstractLocationSet input) {
        return EMPTY_MAP;
    }

    @Override
    protected AbstractLocationSet getInputForEntryPoint() {
        return AbstractLocationSet.EMPTY;
    }

    @Override
    protected boolean outputChanged(Map<ExitType, AbstractLocationSet> previousOutput,
                                    Map<ExitType, AbstractLocationSet> currentOutput) {
        return !previousOutput.equals(currentOutput);
    }

    @Override
    protected boolean existingResultSuitable(AbstractLocationSet newInput,
                                             AnalysisRecord<AbstractLocationSet> existingResults) {
        return existingResults != null && newInput.leq(existingResults.getInput());
    }

}
